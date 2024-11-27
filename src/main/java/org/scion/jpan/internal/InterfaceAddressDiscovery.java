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
import java.util.Map;
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
  private static final String[] KNOWN_SERVERS = {
    "stun:stun.cloudflare.com:3478",
    "stun.l.google.com:19302",
    "stun1.l.google.com:19302",
    "stun2.l.google.com:19302",
    "stun3.l.google.com:19302",
    "stun4.l.google.com:19302"
  };
  private static final int TIMEOUT_MS = 10; // milliseconds
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
    /** Discovery using known public STUN servers */
    STUN_PUBLIC,
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
    switch (v) {
      case "OFF":
        return ConfigMode.STUN_OFF;
      case "BR":
        return ConfigMode.STUN_BR;
      case "PUBLIC":
        return ConfigMode.STUN_PUBLIC;
      case "CUSTOM":
        return ConfigMode.STUN_CUSTOM;
      case "AUTO":
        return ConfigMode.STUN_AUTO;
      default:
        throw new IllegalArgumentException("Illegal argument for STUN: \"" + v + "\"");
    }
  }

  private void init() {}

  /**
   * Determine the network interface and external IP used for connecting to the specified address.
   *
   * @param path Path
   * @return External IP address
   * @see #getSourceAddress(Path, int, long, DatagramChannel)
   */
  public synchronized InetAddress getExternalIP(Path path) {
    // We currently keep a map with BR->externalIP. This may be overkill, probably all BR in
    // a given AS are reachable via the same interface.
    // TODO
    // Moreover, it DOES NOT WORK with multiple AS, because BR IPs are not unique across ASes.
    // However, switching ASes is not currently implemented...
    return externalIPs.computeIfAbsent(
        toKey(path),
        firstHop -> {
          try {
            if (ifDiscoveryChannel == null) {
              ifDiscoveryChannel = java.nio.channels.DatagramChannel.open();
            }
            ifDiscoveryChannel.connect(path.getFirstHopAddress());
            SocketAddress address = ifDiscoveryChannel.getLocalAddress();
            ifDiscoveryChannel.disconnect();
            return ((InetSocketAddress) address).getAddress();
          } catch (IOException e) {
            throw new ScionRuntimeException(e);
          }
        });
  }

  /**
   * Determine the IP that should be your as SRC address in a SCION header. This may differ from the
   * external IP in case we are behind a NAT. The source address should be the NAT mapped address.
   *
   * @param path Path
   * @param knownLocalPort Known local port
   * @return External address or NAT mapped address
   * @see #getExternalIP(Path)
   */
  public synchronized InetSocketAddress getSourceAddress(
      Path path, int knownLocalPort, long locasIsdAs, DatagramChannel channel) {
    String key = toKey(path);
    Entry entry = sourceIPs.get(key);
    if (entry == null || entry.isExpired()) {
      InetSocketAddress source = detectSourceAddress(path, knownLocalPort, locasIsdAs, channel);
      if (entry == null) {
        entry = new Entry(source);
        sourceIPs.put(key, entry);
      } else {
        entry.updateSource(source);
      }
    }
    return entry.getSource();
  }

  /**
   * Determine the IP that should be your as SRC address in a SCION header. This may differ from the
   * external IP in case we are behind a NAT. The source address should be the NAT mapped address.
   *
   * @param path Path
   * @param knownLocalPort Known local port
   * @return External address or NAT mapped address
   * @see #getExternalIP(Path)
   */
  private InetSocketAddress detectSourceAddress(
      Path path, int knownLocalPort, long locasIsdAs, DatagramChannel channel) {
    switch (configMode) {
      case STUN_OFF:
        return new InetSocketAddress(getExternalIP(path), knownLocalPort);
      case STUN_AUTO:
        return autoDetect(path, knownLocalPort, locasIsdAs, channel);
      case STUN_CUSTOM:
        return tryCustomServer();
      case STUN_PUBLIC:
        return tryPublicServer();
      case STUN_BR:
        return tryStunBorderRouter(path);
      default:
        throw new UnsupportedOperationException("Unknown config mode: " + configMode);
    }
  }

  private InetSocketAddress tryCustomServer() {
    String custom =
        ScionUtil.getPropertyOrEnv(Constants.PROPERTY_STUN_SERVER, Constants.ENV_STUN_SERVER);
    if (custom == null) {
      throw new IllegalStateException(
          "No custom STUN server setting found. Please provide a server address via "
              + "SCION_STUN_SERVER or org.scion.stun.server .");
    }
    return tryStunServer(custom);
  }

  private InetSocketAddress tryPublicServer() {
    for (String server : KNOWN_SERVERS) {
      InetSocketAddress addr = tryStunServer(server);
      if (addr != null) {
        // TODO verify  that BR is reachable !?!!?!?!?
        return addr;
      }
    }
    throw new IllegalStateException(
        "Could not reach public STUN servers. Please make sure you are connected to the "
            + "public internet or configure a custom STUN server via SCION_STUN_SERVER or "
            + "org.scion.stun.server .");
  }

  private InetSocketAddress autoDetect(
      Path path, int knownLocalPort, long locasIsdAs, DatagramChannel channel) {
    // Check CUSTOM
    InetSocketAddress source = tryCustomServer();
    if (source != null) {
      return source;
    }

    // Check BR
    // - Check if BR is NAT enabled (gives correct address)
    // Try first with STUN, this should eventually be available everywhere
    source = tryStunBorderRouter(path);
    if (source != null) {
      return source;
    }

    // - Check if BR responds to tr/ping (is reachable)
    source = new InetSocketAddress(getExternalIP(path), knownLocalPort);
    if (isBorderRouterReachable(source, path, locasIsdAs, channel)) {
      return source;
    }

    // Check PUBLIC (or not?)
    source = tryPublicServer();
    if (source != null) {
      // - Verify with tr/ping BR -> fail if not reachable
      if (isBorderRouterReachable(source, path, locasIsdAs, channel)) {
        return source;
      }
    }
    log.error("Could not find a STUN/NAT solution for border router {}", path.getFirstHopAddress());
    throw new ScionRuntimeException("Could not find a STUN/NAT solution for the border router.");
  }

  private InetSocketAddress tryStunServer(String stunAddress) {
    return doStunRequest(IPHelper.toInetSocketAddress(stunAddress));
  }

  private InetSocketAddress tryStunBorderRouter(Path path) {
    return doStunRequest(path.getFirstHopAddress());
  }

  private InetSocketAddress doStunRequest(InetSocketAddress server) {
    ByteBuffer out = ByteBuffer.allocate(1000); // TODO reuse?
    STUN.TransactionID id = STUN.writeRequest(out);
    out.flip();
    try (DatagramChannel channel = DatagramChannel.open()) {
      int sent = channel.send(out, server);
      System.out.println("Sent bytes to BR: " + sent);

      System.out.println("Waiting ...");
      ByteBuffer in = ByteBuffer.allocate(1000); // TODO reuse
      InetSocketAddress server2 = (InetSocketAddress) channel.receive(in);
      System.out.println("Received from: " + server2);
      in.flip();

      boolean isSTUN = STUN.isStunResponse(in, id);
      System.out.println("Is stun: " + isSTUN);
      if (isSTUN) {
        InetSocketAddress external = STUN.parseResponse(in, id);
        System.out.println("Address: " + external);
        return external;
      }
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
    return null;
  }

  private boolean isBorderRouterReachable(
      InetSocketAddress src, Path path, long localIsdAs, DatagramChannel channel) {
    ByteBuffer buffer = ByteBuffer.allocate(1000); // TODO -> class field?
    ScionHeaderParser.write(
        buffer,
        8,
        0,
        localIsdAs,
        src.getAddress().getAddress(),
        localIsdAs,
        path.getFirstHopAddress().getAddress().getAddress(), // TODO correct?
        InternalConstants.HdrTypes.SCMP,
        0); // correct?
    ScmpParser.buildScmpPing(buffer, Scmp.Type.INFO_128, src.getPort(), 0, new byte[0]);
    buffer.flip();
    try {
      channel.send(buffer, path.getFirstHopAddress());
      buffer.flip();
      AbstractSelector selector =
          channel.provider().openSelector(); // TODO is this creating a new selector????
      while (true) {
        int i = selector.select(TIMEOUT_MS);
        if (i == 0) {
          return false;
        }
        Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
        while (iter.hasNext()) {
          SelectionKey key = iter.next();
          if (key.isReadable()) {
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
    }
  }

  private String toKey(Path path) {
    return path.getRemoteIsdAs() + path.getFirstHopAddress().toString();
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
}
