package org.scion.demo;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

public class PingPongChannelClient {

  private static String extractMessage(ByteBuffer buffer) {
    buffer.flip();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return new String(bytes);
  }

  public static DatagramChannel startClient() throws IOException {
    DatagramChannel client = DatagramChannel.open().bind(null);
    client.configureBlocking(false);
    return client;
  }

  public static void sendMessage(DatagramChannel client, String msg, SocketAddress serverAddress)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
    client.send(buffer, serverAddress);
    System.out.println("Sent to server at: " + serverAddress + "  message: " + msg);
  }

  public static String receiveMessage(DatagramChannel channel) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    System.out.println("Waiting ...");
    SocketAddress remoteAddress = channel.receive(buffer);
    String message = extractMessage(buffer);

    System.out.println("Received from server at: " + remoteAddress + "  message: " + message);

    return message;
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    DatagramChannel channel = startClient();
    String msg = "Hello scion";
    InetSocketAddress serverAddress = new InetSocketAddress("localhost", 44444);

    sendMessage(channel, msg, serverAddress);

    boolean finished = false;
    while (!finished) {
      String msg2 = receiveMessage(channel);
      if (!msg2.isEmpty()) {
        finished = true;
      }
      Thread.sleep(10);
    }
    channel.disconnect();
  }
}
