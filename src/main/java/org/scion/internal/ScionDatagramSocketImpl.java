package org.scion.internal;


import java.io.IOException;
import java.net.*;

public class ScionDatagramSocketImpl extends DatagramSocketImpl {


    @Override
    protected void create() throws SocketException {

    }

    @Override
    protected void bind(int i, InetAddress inetAddress) throws SocketException {

    }

    @Override
    protected void send(DatagramPacket datagramPacket) throws IOException {

    }

    @Override
    protected int peek(InetAddress inetAddress) throws IOException {
        return 0;
    }

    @Override
    protected int peekData(DatagramPacket datagramPacket) throws IOException {
        return 0;
    }

    @Override
    protected void receive(DatagramPacket datagramPacket) throws IOException {

    }

    @Override
    protected void setTTL(byte b) throws IOException {

    }

    @Override
    protected byte getTTL() throws IOException {
        return 0;
    }

    @Override
    protected void setTimeToLive(int i) throws IOException {

    }

    @Override
    protected int getTimeToLive() throws IOException {
        return 0;
    }

    @Override
    protected void join(InetAddress inetAddress) throws IOException {

    }

    @Override
    protected void leave(InetAddress inetAddress) throws IOException {

    }

    @Override
    protected void joinGroup(SocketAddress socketAddress, NetworkInterface networkInterface) throws IOException {

    }

    @Override
    protected void leaveGroup(SocketAddress socketAddress, NetworkInterface networkInterface) throws IOException {

    }

    @Override
    protected void close() {

    }

    @Override
    public void setOption(int i, Object o) throws SocketException {

    }

    @Override
    public Object getOption(int i) throws SocketException {
        return null;
    }
}

