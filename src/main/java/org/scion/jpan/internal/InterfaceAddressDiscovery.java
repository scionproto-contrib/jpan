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
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;

public class InterfaceAddressDiscovery {

  private static final AtomicReference<InterfaceAddressDiscovery> singleton =
      new AtomicReference<>();
  private static final String[] KNOWN_SERVERS = {
    "stun.l.google.com:19302",
    "stun1.l.google.com:19302",
    "stun2.l.google.com:19302",
    "stun3.l.google.com:19302",
    "stun4.l.google.com:19302"
  };

  private java.nio.channels.DatagramChannel ifDiscoveryChannel = null;
  private final Map<InetAddress, InetAddress> ifDiscoveryMap = new HashMap<>();
  private final Mode mode;

  private enum Mode {
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

  public static InterfaceAddressDiscovery getInstance() {
    synchronized (singleton) {
      if (singleton.get() == null) {
        singleton.set(new InterfaceAddressDiscovery());
      }
      return singleton.get();
    }
  }

  private InterfaceAddressDiscovery() {
    mode = getConfig();
    init();
  }

  private Mode getConfig() {
    // String v = ScionUtil.getPropertyOrEnv(Constants.PROPERTY_STUN, Constants.ENV_STUN, "AUTO");
    String v = ScionUtil.getPropertyOrEnv(Constants.PROPERTY_STUN, Constants.ENV_STUN, "OFF");
    switch (v) {
      case "OFF":
        return Mode.STUN_OFF;
      case "BR":
        return Mode.STUN_BR;
      case "PUBLIC":
        return Mode.STUN_PUBLIC;
      case "CUSTOM":
        return Mode.STUN_CUSTOM;
      case "AUTO":
        return Mode.STUN_AUTO;
      default:
        throw new IllegalArgumentException("Illegal argument for STUN: \"" + v + "\"");
    }
  }

  private void init() {}

  /**
   * Determine the network interface and external IP used for connecting to the specified address.
   *
   * @param firstHopAddress Reachable address.
   */
  public synchronized InetAddress getExternalIP(InetSocketAddress firstHopAddress) {
    // We currently keep a map with BR->externalIP. This may be overkill, probably all BR in
    // a given AS are reachable via the same interface.
    // TODO
    // Moreover, it DOES NOT WORK with multiple AS, because BR IPs are not unique across ASes.
    // However, switching ASes is not currently implemented...
    if (mode == Mode.STUN_OFF) {
      return ifDiscoveryMap.computeIfAbsent(
          firstHopAddress.getAddress(),
          firstHop -> {
            try {
              if (ifDiscoveryChannel == null) {
                ifDiscoveryChannel = java.nio.channels.DatagramChannel.open();
              }
              ifDiscoveryChannel.connect(firstHopAddress);
              SocketAddress address = ifDiscoveryChannel.getLocalAddress();
              ifDiscoveryChannel.disconnect();
              return ((InetSocketAddress) address).getAddress();
            } catch (IOException e) {
              throw new ScionRuntimeException(e);
            }
          });
    }
    throw new UnsupportedOperationException("Mode not supported: " + mode);
  }

  public synchronized void close() {
    synchronized (singleton) {
      try {
        if (ifDiscoveryChannel != null) {
          ifDiscoveryChannel.close();
        }
        ifDiscoveryChannel = null;
        ifDiscoveryMap.clear();
      } catch (IOException e) {
        throw new ScionRuntimeException(e);
      }
      singleton.set(null);
    }
  }
}
