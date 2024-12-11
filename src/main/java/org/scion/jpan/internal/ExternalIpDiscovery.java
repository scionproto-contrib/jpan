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
import java.nio.channels.DatagramChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.scion.jpan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides utility functions to detect the external IP address of a device that is used
 * to connect to a given border router.
 */
public class ExternalIpDiscovery {

  private static final AtomicReference<ExternalIpDiscovery> singleton = new AtomicReference<>();
  private static final Logger log = LoggerFactory.getLogger(ExternalIpDiscovery.class);

  private DatagramChannel ifDiscoveryChannel = null;
  private final Map<String, InetAddress> externalIPs = new HashMap<>();

  private static ExternalIpDiscovery getInstance() {
    synchronized (singleton) {
      if (singleton.get() == null) {
        singleton.set(new ExternalIpDiscovery());
      }
      return singleton.get();
    }
  }

  private ExternalIpDiscovery() {}

  /**
   * Determine the network interface and external IP used for connecting to the specified address.
   *
   * @param path Path
   * @param localIsdAs Local ISD/AS
   * @return External IP address
   * @see NatMapping#createMapping(long, DatagramChannel, List)
   */
  public static synchronized InetAddress getExternalIP(Path path, long localIsdAs) {
    return getInstance().getIp(path.getFirstHopAddress(), localIsdAs);
  }

  public static synchronized InetAddress getExternalIP(
      InetSocketAddress firstHop, long localIsdAs) {
    return getInstance().getIp(firstHop, localIsdAs);
  }

  private InetAddress getIp(InetSocketAddress firstHop, long localIsdAs) {
    // We currently keep a map with BR+ISD/AS->externalIP. This may be overkill, probably all BR in
    // a given AS are reachable via the same interface.
    return externalIPs.computeIfAbsent(
        toKeyExternalIP(firstHop, localIsdAs),
        key -> {
          try {
            if (ifDiscoveryChannel == null) {
              ifDiscoveryChannel = DatagramChannel.open();
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
