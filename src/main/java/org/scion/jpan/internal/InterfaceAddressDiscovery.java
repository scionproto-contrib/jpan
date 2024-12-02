// Copyright 2024 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.jpan.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelector;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.scion.jpan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides utility functions to detect: <br>
 * - the external IP address of a device that is used to connect to a given border router<br>
 * - the external IP address of a potential NAT between the device and a border router<br>
 *
 * <p>TODO: - We may leave an AS and reenter it on a different NAT (Wifi access point??), we somehow
 * need to detect that case and deal with it.
 */
public class InterfaceAddressDiscovery {

  private static final AtomicReference<InterfaceAddressDiscovery> singleton =
      new AtomicReference<>();
  private static final int TIMEOUT_MS = 10; // milliseconds // TODO configurable!
  private static final int NAT_UDP_MAPPING_TIMEOUT = 300; // seconds
  private static final Logger log = LoggerFactory.getLogger(InterfaceAddressDiscovery.class);

  private java.nio.channels.DatagramChannel ifDiscoveryChannel = null;
  private final Map<String, InetAddress> externalIPs = new HashMap<>();
  private final Map<String, Entry> sourceIPs = new HashMap<>();
  private final ConfigMode configMode;

  // TODO use SimpleCache

  /** See {@link Constants#PROPERTY_STUN} for details. */
  private enum ConfigMode {
    /** No STUN discovery */
    STUN_OFF,
    /** Discovery using STUN interface of border routers */
    STUN_BR,
    /** Discovery using custom STUN servaer */
    STUN_CUSTOM,
    /**
     * Use auto detection.<br>
     * 1) Check for custom STUN setting and use if possible<br>
     * 2) Check border routers if they support STUN (timeout = 10ms)<br>
     * 3) If border router responds to traceroute/ping, do not use STUN at all<br>
     * 4) Try public stun server (optional: recheck with tr/ping, bail out if it fails)<br>
     */
    STUN_AUTO
  }

  private static class Entry {
    InetSocketAddress source;
    long lastUsed;

    Entry(InetSocketAddress source) {
      this.source = source;
      lastUsed = System.currentTimeMillis();
    }

    InetSocketAddress getSource() {
      lastUsed = System.currentTimeMillis();
      return source;
    }

    public boolean isExpired() {
      return (System.currentTimeMillis() - lastUsed) > (NAT_UDP_MAPPING_TIMEOUT * 1000);
    }

    public void updateSource(InetSocketAddress source) {
      lastUsed = System.currentTimeMillis();
      this.source = source;
    }
  }

  public static InterfaceAddressDiscovery getInstance() {
    synchronized (singleton) {
      if (singleton.get() == null) {
        singleton.set(new InterfaceAddressDiscovery());
      }
      return singleton.get();
    }
  }

  private InterfaceAddressDiscovery() {
    configMode = getConfig();
    init();
  }

  private ConfigMode getConfig() {
    String v = ScionUtil.getPropertyOrEnv(Constants.PROPERTY_STUN, Constants.ENV_STUN, "OFF");
    v = v.toUpperCase();
    switch (v) {
      case "OFF":
        return ConfigMode.STUN_OFF;
      case "BR":
        return ConfigMode.STUN_BR;
      case "CUSTOM":
        return ConfigMode.STUN_CUSTOM;
      case "AUTO":
        return ConfigMode.STUN_AUTO;
      default:
        throw new IllegalArgumentException("Illegal value for STUN: \"" + v + "\"");
    }
  }

  private void init() {}

  /**
   * Determine the network interface and external IP used for connecting to the specified address.
   *
   * @param path Path
   * @param localIsdAs Local ISD/AS
   * @return External IP address
   * @see #getSourceAddress(Path, InetAddress, int, long, DatagramChannel)
   */
  public synchronized InetAddress getExternalIP(Path path, long localIsdAs) {
    return getExternalIP(path.getFirstHopAddress(), localIsdAs);
  }

  private InetAddress getExternalIP(InetSocketAddress firstHop, long localIsdAs) {
    // We currently keep a map with BR->externalIP. This may be overkill, probably all BR in
    // a given AS are reachable via the same interface.
    // TODO
    // Moreover, it DOES NOT WORK with multiple AS, because BR IPs are not unique across ASes.
    // However, switching ASes is not currently implemented...
    return externalIPs.computeIfAbsent(
        toKeyExternalIP(firstHop, localIsdAs),
        key -> {
          try {
            if (ifDiscoveryChannel == null) {
              ifDiscoveryChannel = java.nio.channels.DatagramChannel.open();
            }
            ifDiscoveryChannel.connect(firstHop);
            SocketAddress address = ifDiscoveryChannel.getLocalAddress();
            ifDiscoveryChannel.disconnect();
            return ((InetSocketAddress) address).getAddress();
          } catch (IOException e) {
            throw new ScionRuntimeException(e);
          }
        });
  }

  public synchronized void prefetchMappings(
      long localIsdAs, DatagramChannel channel, List<String> borderRouterAddresses) {
    System.out.println("-------------- prefetchMappings() ---------------------------------------");
    // TODO we need to do this for every Interface....
    for (String brAddress : borderRouterAddresses) {
      try {
        InetSocketAddress firstHop = IPHelper.toInetSocketAddress(brAddress);
        InetSocketAddress localAddress = (InetSocketAddress) channel.getLocalAddress();
        int localPort = localAddress.getPort();
        String key =
            toKeySourceAddress(brAddress, localIsdAs, localAddress.getAddress(), localPort);
        Entry entry = sourceIPs.get(key);
        if (entry == null) {
          InetSocketAddress source = detectSourceAddress(firstHop, localIsdAs, channel);
          entry = new Entry(source);
          sourceIPs.put(key, entry);
          System.out.println("IAD-PM-new: " + key + " " + source); // TODO
        } else {
          InetSocketAddress source = detectSourceAddress(firstHop, localIsdAs, channel);
          entry.updateSource(source);
          System.out.println("IAD-PM-update: " + key + " " + source); // TODO
        }
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
    }
    System.out.println("-------------- prefetchMappings() ---DONE--------------------------------");
  }

  /**
   * Determine the IP that should be your as SRC address in a SCION header. This may differ from the
   * external IP in case we are behind a NAT. The source address should be the NAT mapped address.
   *
   * @param path Path
   * @param localPort Known local port
   * @param localIP Known local IP
   * @param localIsdAs Local ISD/AS
   * @param channel Underlying DatagramChannel
   * @return External address or NAT mapped address
   * @see #getExternalIP(Path, long)
   */
  public synchronized InetSocketAddress getSourceAddress(
      Path path, InetAddress localIP, int localPort, long localIsdAs, DatagramChannel channel) {
    System.out.println("-------------- getSourceAddress() ---------------------------------------");
    // TODO can't we get localAddress/port from the channel??? After we did the connect()?
    InetSocketAddress firstHop = path.getFirstHopAddress();
    String key = toKeySourceAddress(path, localIsdAs, localIP, localPort);
    Entry entry = sourceIPs.get(key);
    try {
      if (entry == null) {
        InetSocketAddress source = detectSourceAddress(firstHop, localIsdAs, channel);
        entry = new Entry(source);
        sourceIPs.put(key, entry);
      } else {
        InetSocketAddress source = detectSourceAddress(firstHop, localIsdAs, channel);
        entry.updateSource(source);
      }
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
    return entry.getSource();
  }

  /**
   * Determine the IP that should be your as SRC address in a SCION header. This may differ from the
   * external IP in case we are behind a NAT. The source address should be the NAT mapped address.
   *
   * @param firstHop Border router address
   * @return External address or NAT mapped address
   * @see #getExternalIP(Path, long)
   */
  private InetSocketAddress detectSourceAddress(
      InetSocketAddress firstHop, long localIsdAs, DatagramChannel channel) throws IOException {
    switch (configMode) {
      case STUN_OFF:
        int localPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        return new InetSocketAddress(getExternalIP(firstHop, localIsdAs), localPort);
      case STUN_AUTO:
        return autoDetect(firstHop, localIsdAs, channel);
      case STUN_CUSTOM:
        return tryCustomServer(channel, true);
      case STUN_BR:
        return tryStunBorderRouter(firstHop, channel);
      default:
        throw new UnsupportedOperationException("Unknown config mode: " + configMode);
    }
  }

  private InetSocketAddress tryCustomServer(DatagramChannel channel, boolean useDefault) {
    String defaultSrv = useDefault ? Constants.DEFAULT_STUN_SERVER : null;
    String custom =
        ScionUtil.getPropertyOrEnv(
            Constants.PROPERTY_STUN_SERVER, Constants.ENV_STUN_SERVER, defaultSrv);
    if (custom != null && !custom.isEmpty()) {
      return tryStunServer(custom, channel);
    }
    if (useDefault) {
      throw new IllegalArgumentException(
          "Please provide a valid STUN server address in "
              + "SCION_STUN_SERVER or org.scion.stun.server, was: \""
              + custom
              + "\"");
    }
    return null;
  }

  private InetSocketAddress autoDetect(
      InetSocketAddress firstHop, long localIsdAs, DatagramChannel channel) throws IOException {
    // Check CUSTOM
    InetSocketAddress source = tryCustomServer(channel, false);
    if (source != null) {
      return source;
    }

    // Check BR
    // - Check if BR is NAT enabled (gives correct address)
    // Try first with STUN, this should eventually be available everywhere
    source = tryStunBorderRouter(firstHop, channel);
    if (source != null) {
      return source;
    }

    // - Check if BR responds to tr/ping (is reachable)
    // At this point we should have a local address with port.
    int localPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
    source = new InetSocketAddress(getExternalIP(firstHop, localIsdAs), localPort);
    if (isBorderRouterReachable(source, firstHop, localIsdAs, channel)) {
      return source;
    }

    // Check DEFAULT servers
    // Check CUSTOM
    source = tryCustomServer(channel, true);
    if (source != null) {
      // - Verify with tr/ping BR -> fail if not reachable
      if (isBorderRouterReachable(source, firstHop, localIsdAs, channel)) {
        return source;
      }
    }
    log.error("Could not find a STUN/NAT solution for border router {}", firstHop);
    throw new ScionRuntimeException("Could not find a STUN/NAT solution for the border router.");
  }

  private InetSocketAddress tryStunServer(String stunAddress, DatagramChannel channel) {
    if (stunAddress == null || stunAddress.isEmpty()) {
      return null;
    }
    String[] servers = stunAddress.split(";");
    for (String server : servers) {
      InetSocketAddress address = null;
      try {
        address = doStunRequest(IPHelper.toInetSocketAddress(server), channel);
      } catch (IOException e) {
        log.warn("Could not connect to STUN_SERVER: \"{}\"", server);
      }
      if (address != null) {
        return address;
      }
    }
    return null;
  }

  private InetSocketAddress tryStunBorderRouter(InetSocketAddress firstHop, DatagramChannel channel)
      throws IOException {
    return doStunRequest(firstHop, channel);
  }

  private InetSocketAddress doStunRequest(InetSocketAddress server, DatagramChannel channel)
      throws IOException {
    // prepare receiver
    ExecutorService executor = Executors.newSingleThreadExecutor(); // TODO reuse? / Singleton?
    Receiver receiver = new Receiver(channel);

    // prepare send
    ByteBuffer out = ByteBuffer.allocate(1000); // TODO reuse?
    STUN.TransactionID id = STUN.writeRequest(out);
    receiver.ids.put(id, id);
    out.flip();

    // start receiver
    executor.submit(receiver);
    try {
      receiver.startUpBarrier.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Start sending
    channel.send(out, server);

    // Wait
    try {
      if (!executor.awaitTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
        if (!executor.awaitTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScionRuntimeException(e);
    }
    return receiver.queue.poll();
  }

  private boolean isBorderRouterReachable(
      InetSocketAddress src, InetSocketAddress firstHop, long localIsdAs, DatagramChannel channel)
      throws IOException {
    // prepare receiver
    ExecutorService executor = Executors.newSingleThreadExecutor(); // TODO reuse? / Singleton?
    ScmpReceiver receiver = new ScmpReceiver(channel);

    // prepare send
    ByteBuffer buffer = ByteBuffer.allocate(1000); // TODO -> class field?
    ScionHeaderParser.write(
        buffer,
        8,
        0,
        localIsdAs,
        src.getAddress().getAddress(),
        localIsdAs,
        firstHop.getAddress().getAddress(), // TODO correct?
        InternalConstants.HdrTypes.SCMP,
        0); // correct?
    ScmpParser.buildScmpPing(buffer, Scmp.Type.INFO_128, src.getPort(), 0, new byte[0]);
    buffer.flip();

    // start receiver
    executor.submit(receiver);
    try {
      receiver.startUpBarrier.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Start sending
    channel.send(buffer, firstHop);

    // Wait
    try {
      if (!executor.awaitTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
        if (!executor.awaitTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScionRuntimeException(e);
    }
    return receiver.success.get();
  }

  private String toKeySourceAddress(
      Path path, long localIsdAs, InetAddress localAddress, int localPort) {
    return toKeySourceAddress(
        path.getFirstHopAddress().toString(), localIsdAs, localAddress, localPort);
  }

  private String toKeySourceAddress(
      String brAddress, long localIsdAs, InetAddress localAddress, int localPort) {
    // The NAT mapped address depends on:
    // - the local port
    // - the local IP (local interface we are using)
    // - the AS we are connected to (a different local AS may have the same ports/IPs
    // - the border router port/IP
    return localIsdAs + "_" + localAddress.getHostAddress() + "_" + localPort + "_" + brAddress;
  }

  private String toKeyExternalIP(InetSocketAddress firstHop, long localIsdAs) {
    // The external IP depends on:
    // - the border router port/IP
    // - the local AS
    return localIsdAs + "_" + firstHop.toString();
  }

  public synchronized void close() {
    synchronized (singleton) {
      try {
        if (ifDiscoveryChannel != null) {
          ifDiscoveryChannel.close();
        }
        ifDiscoveryChannel = null;
        externalIPs.clear();
        sourceIPs.clear();
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
      singleton.set(null);
    }
  }

  public static void uninstall() {
    synchronized (singleton) {
      if (singleton.get() != null) {
        singleton.get().close();
      }
    }
  }

  private static class Receiver implements Runnable {
    final CountDownLatch startUpBarrier = new CountDownLatch(1);
    final ConcurrentLinkedQueue<InetSocketAddress> queue = new ConcurrentLinkedQueue<>();
    final ConcurrentHashMap<STUN.TransactionID, Object> ids = new ConcurrentHashMap<>();
    private final DatagramChannel channel;

    Receiver(DatagramChannel channel) {
      this.channel = channel;
    }

    public void run() {
      ByteBuffer buffer = ByteBuffer.allocate(1000); // TODO reuse
      boolean isBlocking = channel.isBlocking();
      try {
        AbstractSelector selector =
            channel.provider().openSelector(); // TODO is this creating a new selector????
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_READ, channel);

        startUpBarrier.countDown();
        while (true) {
          int i = selector.select(TIMEOUT_MS);
          if (i == 0) {
            selector.close(); // TODO remove this!!!!
            return;
          }
          Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
          while (iter.hasNext()) {
            SelectionKey key = iter.next();
            if (key.isReadable()) {

              DatagramChannel channelIn = (DatagramChannel) key.channel();
              channelIn.receive(buffer);
              buffer.flip();

              ByteUtil.MutRef<String> error = new ByteUtil.MutRef<>();
              InetSocketAddress external =
                  STUN.parseResponse(buffer, id -> ids.remove(id) != null, error);
              if (external != null && error.get() == null) {
                queue.add(external);
                if (ids.isEmpty()) {
                  return;
                }
              }
            }
          }
        }
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      } finally {
        try {
          channel.configureBlocking(isBlocking);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private static class ScmpReceiver implements Runnable {
    final CountDownLatch startUpBarrier = new CountDownLatch(1);
    final AtomicBoolean success = new AtomicBoolean(false);
    private final DatagramChannel channel;

    ScmpReceiver(DatagramChannel channel) {
      this.channel = channel;
    }

    public void run() {
      ByteBuffer buffer = ByteBuffer.allocate(1000); // TODO reuse
      boolean isBlocking = channel.isBlocking();
      try {
        AbstractSelector selector =
            channel.provider().openSelector(); // TODO is this creating a new selector????
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_READ, channel);

        startUpBarrier.countDown();
        while (true) {
          int i = selector.select(TIMEOUT_MS);
          if (i == 0) {
            selector.close(); // TODO remove this!!!!
            return;
          }
          Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
          while (iter.hasNext()) {
            SelectionKey key = iter.next();
            if (key.isReadable()) {
              DatagramChannel channelIn = (DatagramChannel) key.channel();
              channelIn.receive(buffer);
              buffer.flip();
              if (ScionHeaderParser.extractNextHeader(buffer) == InternalConstants.HdrTypes.SCMP
                  && ScmpParser.extractType(buffer) == Scmp.Type.INFO_129) {
                // TODO check sequence number????
                success.set(true);
                return;
              }
            }
          }
        }
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      } finally {
        try {
          channel.configureBlocking(isBlocking);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }
}
