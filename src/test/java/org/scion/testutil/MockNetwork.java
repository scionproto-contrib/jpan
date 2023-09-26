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

package org.scion.testutil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class MockNetwork {

    private static final Logger logger = LoggerFactory.getLogger(MockNetwork.class.getName());

    private static ArrayList<MockService> services = new ArrayList<>();
  private static ForkJoinPool pool = new ForkJoinPool();

    public static void startTiny(int br1Port, int br2Port, SocketAddress br1Dst, SocketAddress br2Dst) {
        if (pool.getPoolSize() != 0) {
            throw new IllegalStateException();
        }

        pool.execute(new MockService("Fwd-1", br1Port, new MockForwarder(br1Dst)));
        pool.execute(new MockService("Fwd-2", br2Port, new MockForwarder(br2Dst)));
    }

    public static void stopTiny() {
        try {
            logger.warn("Shutting down daemon");
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Threads did not terminate!");
            }
            logger.warn("Daemon shut down");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}

class MockForwarder implements Function<DatagramPacket, DatagramPacket> {
    private static final Logger logger = LoggerFactory.getLogger(MockForwarder.class.getName());

    private final SocketAddress destination;

    MockForwarder(SocketAddress destination) {
        this.destination = destination;
    }

    @Override
    public DatagramPacket apply(DatagramPacket datagramPacket) {
        logger.info("Forwarding to " + destination);
        datagramPacket.setSocketAddress(destination);
        return datagramPacket;
    }
}

class MockService implements Runnable {

  private final String name;
  private final int port;
  private final Function<DatagramPacket, DatagramPacket> logic;
  private DatagramSocket in;
  private DatagramSocket out;

  MockService(String name, int port, Function<DatagramPacket, DatagramPacket> logic) {
    this.name = name;
    this.port = port;
    this.logic = logic;
  }

  @Override
  public void run() {
    System.out.println("Running " + name + " on port " + port);
    try {
      in = new DatagramSocket(port);
      out = new DatagramSocket();
      while (true) {
        byte[] bytes = new byte[65000];
        DatagramPacket pIn = new DatagramPacket(bytes, 0);
        in.receive(pIn);
        DatagramPacket pOut = logic.apply(pIn);
        if (pOut != null) {
          out.send(pOut);
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (in != null) {
        in.close();
      }
      if (out != null) {
        out.close();
      }
    }
  }
}
