package org.scion.demo;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

public class PingPongClient {

    public static void main(String[] args) {
        String hostname = "127.0.0.1";
        int port = 13579;

        try {
            InetAddress address = InetAddress.getByName(hostname);
            DatagramSocket socket = new DatagramSocket();

            while (true) {

                DatagramPacket request = new DatagramPacket(new byte[1], 1, address, port);
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
