package org.scion.demo;

import org.scion.ScionDatagramSocket;

import java.io.*;
import java.net.*;

public class ScionPingPongServer {
    private final ScionDatagramSocket socket;

    public ScionPingPongServer(int port) throws SocketException {
        socket = new ScionDatagramSocket(port);
    }

    public ScionPingPongServer(int port, InetAddress localAddress) throws SocketException {
        socket = new ScionDatagramSocket(port, localAddress);
    }

    //TODO FIX PATH to go 1.20 -> Reinstall update-alternatives
    // TODO Run shion.sh run
    // - ps -ef | grep dispatcher
    // lsof -ni | grep 63972
    // kill -15 63972
    // start Java scion server


    public static void main(String[] args) throws UnknownHostException {
        //int port = 30255;
        //int port = 30041;
        //int port = 40043;
        int port = 44444;

        InetAddress localAddress = InetAddress.getByName("::1");
        //InetAddress localAddress = InetAddress.getByName("127.0.0.1");
        //InetAddress localAddress = InetAddress.getByName("127.0.0.10");
        //System.out.println("IPv4: " + (localAddress instanceof Inet4Address));
        //InetAddress localAddress = InetAddress.getByName("fd00:f00d:cafe::7f00:c");
        try {
            ScionPingPongServer server = new ScionPingPongServer(port, localAddress);
            //ScionPingPongServer server = new ScionPingPongServer(port);
            server.service();
        } catch (SocketException ex) {
            System.out.println("Socket error: " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("I/O error: " + ex.getMessage());
        }
    }

    private void service() throws IOException {
        while (true) {
            // TODO avoid byte[]? Or use byte[] internally?  --> May be to small!!!  -> Not transparently plugable!
            //      -> users need to adapt array size. Without adaptation: requires copy.....
            //      -> Copy is alright, but high performance user may want a a way to avoid the copy....
            //      -> Make this configurable via OPTIONS?
            DatagramPacket request = new DatagramPacket(new byte[65536], 65536);
            System.out.println("--- USER - Waiting for packet ----------------------");
            socket.receive(request);
//            for (int i = 0; i < request.getLength(); i++) {
//                System.out.print(Integer.toHexString(Byte.toUnsignedInt(request.getData()[request.getOffset() + i])) + ", ");
//            }
//            System.out.println();
            String msg = new String(request.getData(), request.getOffset(), request.getLength());
            System.out.println("Received (from " + request.getSocketAddress() + "): " + msg);

            byte[] buffer = ("Re: " + msg).getBytes();

            InetAddress clientAddress = request.getAddress();
            int clientPort = request.getPort();

            //DatagramPacket response = new DatagramPacket(buffer, buffer.length, clientAddress, clientPort);
            // IPv6 border router port???
            System.out.println("--- USER - Sending packet ----------------------");
            DatagramPacket response = new DatagramPacket(buffer, buffer.length, clientAddress, 31012);
            socket.send(response);
        }
    }
}