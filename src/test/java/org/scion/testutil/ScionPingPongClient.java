package org.scion.testutil;

import java.io.*;
import java.net.*;
import java.util.*;
import org.scion.ScionDatagramSocket;

public class ScionPingPongClient {

    public static void main(String[] args) {
        ScionPingPongClient client = new ScionPingPongClient();
        client.run();
    }

    private void run() {
        String hostname = "::1";
        int port = 8080;

        //        dstIA, err := addr.ParseIA("1-ff00:0:112")
        //        srcIA, err := addr.ParseIA("1-ff00:0:110")
        //        srcAddr, err := net.ResolveUDPAddr("udp", "127.0.0.2:100")
        //        dstAddr, err := net.ResolveUDPAddr("udp", "[::1]:8080")

        try {
            InetAddress address = InetAddress.getByName(hostname);
            ScionDatagramSocket socket = new ScionDatagramSocket();
            socket.setDstIsdAs( "1-ff00:0:112");

            while (true) {

                String msg = "Hello there!";
                byte[] sendBuf = msg.getBytes();
                DatagramPacket request = new DatagramPacket(sendBuf, sendBuf.length, address, port);
                socket.send(request);

                byte[] buffer = new byte[512];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);

                String pong = new String(buffer, 0, response.getLength());

                System.out.println(pong);

                Thread.sleep(1000);
            }

        } catch (SocketTimeoutException e) {
            System.out.println("Timeout error: " + e.getMessage());
            throw new RuntimeException(e);
        } catch (IOException e) {
            System.out.println("Client error: " + e.getMessage());
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}