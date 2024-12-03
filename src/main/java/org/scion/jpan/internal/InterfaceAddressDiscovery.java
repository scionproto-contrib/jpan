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
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    final InetSocketAddress firstHop;
    long lastUsed;
    String brAddress;

    Entry(InetSocketAddress source, InetSocketAddress firstHop) {
      this.source = source;
      this.firstHop = firstHop;
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

    public void setFixedSource(InetSocketAddress source) {
      lastUsed = Long.MAX_VALUE;
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
    if (borderRouterAddresses.isEmpty()) {
      return;
    }

    // determine local address
    InetSocketAddress localAddress;
    try {
      localAddress = (InetSocketAddress) channel.getLocalAddress();
      if (localAddress.getAddress().isAnyLocalAddress()) {
        InetSocketAddress firstHop = IPHelper.toInetSocketAddress(borderRouterAddresses.get(0));
        localAddress =
            new InetSocketAddress(getExternalIP(firstHop, localIsdAs), localAddress.getPort());
        if (localAddress.getAddress().isAnyLocalAddress()) {
          throw new IllegalStateException();
        }
      }
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
    int localPort = localAddress.getPort();

    // prepare entries
    List<Entry> newEntries = new ArrayList<>();
    for (String brAddress : borderRouterAddresses) {
      String key = toKeySourceAddress(brAddress, localIsdAs, localAddress.getAddress(), localPort);
      Entry entry =
          sourceIPs.computeIfAbsent(
              key, k -> new Entry(null, IPHelper.toInetSocketAddress(brAddress)));
      newEntries.add(entry);
    }

    // detect addresses
    try {
      detectSourceAddress(newEntries, localIsdAs, channel);
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
  }

  private void detectSourceAddress(List<Entry> entries, long localIsdAs, DatagramChannel channel)
      throws IOException {
    switch (configMode) {
      case STUN_OFF:
        int localPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        for (Entry e : entries) {
          e.setFixedSource(new InetSocketAddress(getExternalIP(e.firstHop, localIsdAs), localPort));
        }
        break;
      case STUN_AUTO:
        for (Entry e : entries) {
          e.updateSource(autoDetect(e.firstHop, localIsdAs, channel)); // TODO
        }
        break;
      case STUN_CUSTOM:
        InetSocketAddress addr = tryCustomServer(channel, true);
        if (addr != null) {
          for (Entry e : entries) {
            e.updateSource(addr);
          }
        } // TODO else error? -> BR not available, block from usage in path? Could be temporary...
        break;
      case STUN_BR:
        tryStunBorderRouter(entries, channel);
        break;
      default:
        throw new UnsupportedOperationException("Unknown config mode: " + configMode);
    }
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
  // TODO pass in InetSocketAddress
  public synchronized InetSocketAddress getSourceAddress(
      Path path, InetAddress localIP, int localPort, long localIsdAs, DatagramChannel channel) {
    if (localIP.isAnyLocalAddress()) {
      // TODO report this back to the channel so we don't call it all the time (even though it is
      // just a map lookup)
      localIP = getExternalIP(path.getFirstHopAddress(), localIsdAs);
    }
    // TODO can't we get localAddress/port from the channel??? After we did the connect()?
    String key = toKeySourceAddress(path, localIsdAs, localIP, localPort);
    Entry entry = sourceIPs.get(key);
    try {
      if (entry == null) {
        // This is not a known border router, the destination is presumably in the local AS
        if (path.getRemoteIsdAs() == localIsdAs || configMode == ConfigMode.STUN_OFF) {
          return new InetSocketAddress(localIP, localPort);
        }
        throw new IllegalArgumentException("Unknown border router: " + path.getFirstHopAddress());
      } else {
        InetSocketAddress source = detectSourceAddress(entry.firstHop, localIsdAs, channel);
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

  private void tryStunBorderRouter(List<Entry> entries, DatagramChannel channel)
      throws IOException {
    boolean isBlocking = channel.isBlocking();
    // TODO is this creating a new selector????
    try (AbstractSelector selector = channel.provider().openSelector()) {
      channel.configureBlocking(false);
      // start receiver
      channel.register(selector, SelectionKey.OP_READ, channel);
      doStunRequest(entries, channel, selector);
    } finally {
      channel.configureBlocking(isBlocking);
    }
  }

  private void doStunRequest(List<Entry> servers, DatagramChannel channel, Selector selector)
      throws IOException {
    // prepare receiver
    final ConcurrentHashMap<STUN.TransactionID, Entry> ids = new ConcurrentHashMap<>();

    // prepare send

    // Start sending
    ByteBuffer out = ByteBuffer.allocate(1000); // TODO reuse?
    for (Entry e : servers) {
      out.clear();
      STUN.TransactionID id = STUN.writeRequest(out);
      ids.put(id, e);
      out.flip();
      channel.send(out, e.firstHop);
    }

    // Wait
    ByteBuffer buffer = ByteBuffer.allocate(1000); // TODO reuse
    while (selector.select(TIMEOUT_MS) > 0) {
      Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
      while (iter.hasNext()) {
        SelectionKey key = iter.next();
        iter.remove();
        if (key.isReadable()) {
          DatagramChannel channelIn = (DatagramChannel) key.channel();
          buffer.clear();
          channelIn.receive(buffer);
          buffer.flip();

          ByteUtil.MutRef<String> error = new ByteUtil.MutRef<>();
          final ByteUtil.MutRef<STUN.TransactionID> id = new ByteUtil.MutRef<>();
          InetSocketAddress external = STUN.parseResponse(buffer, ids::containsKey, id, error);
          Entry e = ids.remove(id.get());
          e.updateSource(external);
          if (external != null && error.get() == null) {
            if (ids.isEmpty()) {
              return;
            }
          }
        }
      }
    }
  }

  private InetSocketAddress tryStunBorderRouter(InetSocketAddress firstHop, DatagramChannel channel)
      throws IOException {
    return doStunRequest(firstHop, channel);
  }

  private InetSocketAddress doStunRequest(InetSocketAddress server, DatagramChannel channel)
      throws IOException {
    // prepare receiver
    final ConcurrentLinkedQueue<InetSocketAddress> queue = new ConcurrentLinkedQueue<>();
    final ConcurrentHashMap<STUN.TransactionID, Object> ids = new ConcurrentHashMap<>();

    // prepare send
    ByteBuffer out = ByteBuffer.allocate(1000); // TODO reuse?
    STUN.TransactionID id = STUN.writeRequest(out);
    ids.put(id, id);
    out.flip();

    // start receiver
    ByteBuffer buffer = ByteBuffer.allocate(1000); // TODO reuse
    boolean isBlocking = channel.isBlocking();
    AbstractSelector selector =
        channel.provider().openSelector(); // TODO is this creating a new selector????
    channel.configureBlocking(false);
    channel.register(selector, SelectionKey.OP_READ, channel);

    // Start sending
    channel.send(out, server);

    // Wait
    try {
      while (selector.select(TIMEOUT_MS) > 0) {
        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        while (iter.hasNext()) {
          SelectionKey key = iter.next();
          iter.remove();
          if (key.isReadable()) {
            DatagramChannel channelIn = (DatagramChannel) key.channel();
            buffer.clear();
            channelIn.receive(buffer);
            buffer.flip();

            ByteUtil.MutRef<String> error = new ByteUtil.MutRef<>();
            InetSocketAddress external =
                STUN.parseResponse(buffer, id2 -> ids.remove(id2) != null, error);
            if (external != null && error.get() == null) {
              queue.add(external);
              if (ids.isEmpty()) {
                return queue.poll();
              }
            }
          }
        }
      }
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    } finally {
      selector.close(); // TODO remove this?!?!?!
      try {
        channel.configureBlocking(isBlocking);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return null;
  }

  private boolean isBorderRouterReachable(
      InetSocketAddress src, InetSocketAddress firstHop, long localIsdAs, DatagramChannel channel)
      throws IOException {
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
    boolean isBlocking = channel.isBlocking();
    AbstractSelector selector =
        channel.provider().openSelector(); // TODO is this creating a new selector????
    channel.configureBlocking(false);
    channel.register(selector, SelectionKey.OP_READ, channel);

    // Start sending
    channel.send(buffer, firstHop);

    // Wait
    try {
      while (true) {
        int i = selector.select(TIMEOUT_MS);
        if (i == 0) {
          selector.close(); // TODO remove this!!!!
          return false; // TODO??
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
              return true;
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

  private String toKeySourceAddress(
      Path path, long localIsdAs, InetAddress localAddress, int localPort) {
    // remove leading "/"
    String firstHop = path.getFirstHopAddress().toString().substring(1);
    return toKeySourceAddress(firstHop, localIsdAs, localAddress, localPort);
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
}
