package org.scion.demo;

import java.io.*;
import java.net.*;

import org.scion.ScionDatagramSocket;

public class ScionPingPongClient {

    public static void main(String[] args) throws IOException {
        ScionPingPongClient client = new ScionPingPongClient();
        client.run();
    }

    private void run() throws IOException {
        String serverHostname = "::1";
        // int port = 8080;
        int serverPort = 44444;

        //        dstIA, err := addr.ParseIA("1-ff00:0:112")
        //        srcIA, err := addr.ParseIA("1-ff00:0:110")
        //        srcAddr, err := net.ResolveUDPAddr("udp", "127.0.0.2:100")
        //        dstAddr, err := net.ResolveUDPAddr("udp", "[::1]:8080")

        try {
            InetAddress serverAddress = InetAddress.getByName(serverHostname);
            ScionDatagramSocket socketSend = new ScionDatagramSocket();
            socketSend.setDstIsdAs("1-ff00:0:112");
            // TODO remove this! We need a socket that directly listens on 40041 for incoming traffic because we
            //      do not use the dispatcher.
            //ScionDatagramSocket socketReceive = new ScionDatagramSocket(40041);
            //InetAddress rcvAddress = InetAddress.getByName("127.0.0.10");
            //ScionDatagramSocket socketReceive = new ScionDatagramSocket(rcvAddress, 55555);

            while (true) {

                String msg = "Hello there!";
                byte[] sendBuf = msg.getBytes();
                DatagramPacket request = new DatagramPacket(sendBuf, sendBuf.length, serverAddress, serverPort);
                socketSend.send(request);
                System.out.println("Sent!");

                System.out.println("Receiving ...");
                byte[] buffer = new byte[512];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                //socketReceive.receive(response);
                socketSend.receive(response);

                String pong = new String(buffer, 0, response.getLength());

                System.out.println(pong);

                Thread.sleep(1000);
            }

        } catch (SocketTimeoutException e) {
            System.out.println("Timeout error: " + e.getMessage());
            throw new RuntimeException(e);
//        } catch (IOException e) {
//            System.out.println("Client error: " + e.getMessage());
//            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}