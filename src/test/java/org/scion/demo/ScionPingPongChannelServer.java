package org.scion.demo;

import org.scion.ScionDatagramChannel;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;

public class ScionPingPongChannelServer {
  public static ScionDatagramChannel startServer() throws IOException {
    //InetSocketAddress address = new InetSocketAddress("localhost", 44444);
    InetSocketAddress address = new InetSocketAddress("::1", 44444);
    ScionDatagramChannel server = ScionDatagramChannel.open().bind(address);

    System.out.println("Server started at: " + address);

    return server;
  }

  public static void sendMessage(ScionDatagramChannel channel, String msg, InetSocketAddress serverAddress)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
    channel.send(buffer, serverAddress);
    System.out.println("Sent to client at: " + serverAddress + "  message: " + msg);
  }

  public static InetSocketAddress receiveMessage(ScionDatagramChannel server) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    System.out.println("Waiting ...");
    InetSocketAddress remoteAddress = (InetSocketAddress) server.receive(buffer);
    System.out.println("Received from client at: limit=" + buffer.limit() + " pos=" + buffer.position());
    String message = extractMessage(buffer);

    System.out.println("Received from client at: " + remoteAddress + "  message: " + message);

    return remoteAddress;
  }

  private static String extractMessage(ByteBuffer buffer) {
    buffer.flip();

    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);

    return new String(bytes);
  }

  public static void main(String[] args) throws IOException {
    ScionDatagramChannel channel = startServer();
    InetSocketAddress remoteAddress = receiveMessage(channel);
    sendMessage(channel, "Re: Hello scion", remoteAddress);
  }
}
