// Copyright 2023 ETH Zurich
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

package org.scion;

import java.io.IOException;
import java.net.*;
import java.util.Enumeration;

public class DemoFactory {

  public static void main(String[] args) throws SocketException, IOException {
    // TODO NetworkInterface??? -> Provide SCION info? Or do it in separate API?
    Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
    while (en.hasMoreElements()) {
      NetworkInterface ni = en.nextElement();
      System.out.println(" Display Name = " + ni.getDisplayName());
      System.out.println(" MTU = " + ni.getMTU());
      Enumeration<InetAddress> addresses = ni.getInetAddresses();
      while (addresses.hasMoreElements()) {
        System.out.println("   Address = " + addresses.nextElement());
      }
    }

    registerSocketFactory();

    // API for UDP
    DatagramSocket socket;

    // API for PathService
    // TODO rename
    ScionPathService pathService;

    // TODO -- TCP

    // TODO -- HTTP
    // Java 11: HTTPClient
    // Apache HttpClient, OkHttpClient, Spring WebClient ???
  }

  // http://www.java2s.com/example/java-src/pkg/org/jboss/as/capedwarf/extension/capedwarfsubsystemadd-c4d5a.html
  protected static void registerSocketFactory() throws IOException {
    Socket.setSocketImplFactory(SCIONSocketFactory.INSTANCE);
    java.net.DatagramSocket.setDatagramSocketImplFactory(SCIONSocketFactory.INSTANCE);
  }
 }

class SCIONSocketFactory implements SocketImplFactory, DatagramSocketImplFactory {
    static SCIONSocketFactory INSTANCE = null;

    @Override
    public SocketImpl createSocketImpl() {
        return null;
    }

    @Override
    public DatagramSocketImpl createDatagramSocketImpl() {
        return null;
    }
}