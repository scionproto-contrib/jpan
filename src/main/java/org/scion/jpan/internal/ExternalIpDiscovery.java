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

/**
 * This class provides utility functions to detect the external IP address of a device that is used
 * to connect to a given border router.
 */
public class ExternalIpDiscovery {

  private static final AtomicReference<ExternalIpDiscovery> singleton = new AtomicReference<>();
  private DatagramChannel ifDiscoveryChannel = null;

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
   * @param firstHop First address on the path
   * @return External IP address
   * @see NatMapping#createMapping(long, DatagramChannel, List)
   */
  public static synchronized InetAddress getExternalIP(InetSocketAddress firstHop) {
    return getInstance().getIp(firstHop);
  }

  private InetAddress getIp(InetSocketAddress firstHop) {
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
  }

  public synchronized void close() {
    synchronized (singleton) {
      try {
        if (ifDiscoveryChannel != null) {
          ifDiscoveryChannel.close();
        }
        ifDiscoveryChannel = null;
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
