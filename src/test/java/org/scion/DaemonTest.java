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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.proto.daemon.Daemon;
import org.scion.testutil.MockDaemon;

public class DaemonTest {

  private static final InetSocketAddress DAEMON_ADDRESS =
      new InetSocketAddress("127.0.0.15", 30255);
  //  private static final InetSocketAddress DAEMON_ADDRESS =
  //          new InetSocketAddress("127.0.0.12", 30255); // from 110-topo

  private static String toHostAddrPort(InetSocketAddress address) {
    return address.getAddress().getHostAddress() + ":" + address.getPort();
  }

  @Test
  public void testWrongDaemonAddress() {
    ScionException thrown =
        assertThrows(
            ScionException.class,
            () -> {
              String daemonAddr = "127.0.0.112:65432"; // 30255";
              long srcIA = Util.ParseIA("1-ff00:0:110");
              long dstIA = Util.ParseIA("1-ff00:0:112");
              try (DaemonClient client = DaemonClient.create(daemonAddr)) {
                client.getPath(srcIA, dstIA);
              }
            });

    assertEquals("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception", thrown.getMessage());
  }

  @Test
  public void getPath() throws IOException {
    MockDaemon mock = MockDaemon.create(DAEMON_ADDRESS).start();

    // String daemonAddr = "127.0.0.12:30255"; // from 110-topo
    String daemonAddr = toHostAddrPort(DAEMON_ADDRESS);
    List<Daemon.Path> paths;
    long srcIA = Util.ParseIA("1-ff00:0:110");
    long dstIA = Util.ParseIA("1-ff00:0:112");
    try (DaemonClient client = DaemonClient.create(daemonAddr)) {
      paths = client.getPath(srcIA, dstIA);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // Expected:
    //    Paths found: 1
    //    Path: first hop = 127.0.0.10:31004
    //    0: 2 561850441793808
    //    0: 1 561850441793810

    assertEquals(1, paths.size());
    Daemon.Path path0 = paths.get(0);
    assertEquals("127.0.0.10:31004", path0.getInterface().getAddress().getAddress());

    //    System.out.println("Paths found: " + paths.size());
    //    for (Daemon.Path path : paths) {
    //      System.out.println("Path: first hop = " +
    // path.getInterface().getAddress().getAddress());
    //      int i = 0;
    //      for (Daemon.PathInterface segment : path.getInterfacesList()) {
    //        System.out.println("    " + i + ": " + segment.getId() + " " + segment.getIsdAs());
    //      }
    //    }

    mock.close();
  }
}
