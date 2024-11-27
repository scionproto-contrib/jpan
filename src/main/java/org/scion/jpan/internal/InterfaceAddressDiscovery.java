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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.scion.jpan.Constants;
import org.scion.jpan.Path;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;

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

  private java.nio.channels.DatagramChannel ifDiscoveryChannel = null;
  private final Map<String, InetAddress> externalIPs = new HashMap<>();
  private final Map<String, InetSocketAddress> sourceIPs = new HashMap<>();
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

  private enum State {
    NOT_INITIALIZED,
    HAS_NAT,
    HAS_NO_NAT
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
   * @see #getSourceAddress(Path, int)
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
  public synchronized InetSocketAddress getSourceAddress(Path path, int knownLocalPort) {
    // TODO return InetSocketAddress
    switch (configMode) {
      case STUN_OFF:
        return new InetSocketAddress(getExternalIP(path), knownLocalPort);
      case STUN_AUTO:
        return autoDetect(path);
      case STUN_CUSTOM:
        String custom =
            ScionUtil.getPropertyOrEnv(Constants.PROPERTY_STUN_SERVER, Constants.ENV_STUN_SERVER);
        if (custom == null) {
          throw new IllegalStateException(
              "No custom STUN server setting found. Please provide a server address via "
                  + "SCION_STUN_SERVER or org.scion.stun.server .");
        }
        return tryStunServer(custom);
      case STUN_PUBLIC:
        for (String server : KNOWN_SERVERS) {
          InetSocketAddress addr = tryStunServer(server);
          if (addr != null) {
            return addr;
          }
        }
        throw new IllegalStateException(
            "Could not reach public STUN servers. Please make sure you are connected to the "
                + "public internet or configure a custom STUN server via SCION_STUN_SERVER or "
                + "org.scion.stun.server .");
      case STUN_BR:
        return tryStunBorderRouter(path);
      default:
        throw new UnsupportedOperationException("Unknown config mode: " + configMode);
    }
  }

  private InetSocketAddress autoDetect(Path path) {
    // Check CUSTOM
    // Check BR
    // - Check if BR is NAT enabled (gives correct address)
    // - Check if BR responds to tr/ping (is reachable)
    // Check PUBLIC (or not?)
    // - Verify with tr/ping BR -> fail if not reachable
    // TODO
    throw new UnsupportedOperationException();
  }

  private InetSocketAddress tryStunServer(String stunAddress) {
    // TODO
    throw new UnsupportedOperationException();
  }

  private InetSocketAddress tryStunBorderRouter(Path path) {
    // TODO
    throw new UnsupportedOperationException();
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
