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

package org.scion.demo.jdk;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.*;

public class PingPongSocketServer {
    private final DatagramSocket socket;
    private final List<String> listData = new ArrayList<>();
    private final Random random;

    public PingPongSocketServer(int port) throws SocketException {
        socket = new DatagramSocket(port);
        random = new Random();
        listData.add("Pong 1");
        listData.add("Pong 22");
        listData.add("Pong 333");
    }

    public static void main(String[] args) {
        int port = 13579;

        try {
            PingPongSocketServer server = new PingPongSocketServer(port);
            server.service();
        } catch (SocketException ex) {
            System.out.println("Socket error: " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("I/O error: " + ex.getMessage());
        }
    }

    private void service() throws IOException {
        while (true) {
            DatagramPacket request = new DatagramPacket(new byte[1], 1);
            socket.receive(request);

            String quote = getRandomPong();
            byte[] buffer = quote.getBytes();

            InetAddress clientAddress = request.getAddress();
            int clientPort = request.getPort();

            DatagramPacket response = new DatagramPacket(buffer, buffer.length, clientAddress, clientPort);
            socket.send(response);
        }
    }

    private String getRandomPong() {
        int randomIndex = random.nextInt(listData.size());
        return listData.get(randomIndex);
    }
}