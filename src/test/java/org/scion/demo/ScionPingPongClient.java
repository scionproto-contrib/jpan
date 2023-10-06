package org.scion.demo;

import java.io.*;
import java.net.*;

import org.scion.ScionConstants;
import org.scion.ScionDatagramSocket;

public class ScionPingPongClient {

  public static void main(String[] args) throws IOException {
    DemoTopology.configureMock(); // Tiny111_112();
    ScionPingPongClient client = new ScionPingPongClient();
    client.run();
  }

  private void run() throws IOException {
    String serverHostname = "::1";
    int serverPort = 44444;

    try {
      InetAddress localAddress = InetAddress.getByName("127.0.0.1");
      InetAddress serverAddress = InetAddress.getByName(serverHostname);
      // ScionDatagramSocket socket = new ScionDatagramSocket(40507, localAddress);
      ScionDatagramSocket socket = new ScionDatagramSocket(null);
      socket.setDstIsdAs("1-ff00:0:112");

      while (true) {

        String msg = "Hello there!";
        byte[] sendBuf = msg.getBytes();
        DatagramPacket request =
            new DatagramPacket(sendBuf, sendBuf.length, serverAddress, serverPort);
        // TODO fix
        //  socket.connect(serverAddress, serverPort);
        socket.send(request);
        System.out.println("Sent!");

        System.out.println("Receiving ... (" + socket.getLocalSocketAddress() + ")");
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
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
