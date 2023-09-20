package org.scion;

import java.io.IOException;
import java.net.*;
import java.util.Enumeration;

public class DemoFactory {

  public static void main(String[] args) throws SocketException, IOException {
    // TODO NetworkInterface??? -> Provide SCION info? Or do it in separate API?
    Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
    while (en.hasMoreElements()) {
      NetworkInterface ni = en.nextElement();
      System.out.println(" Display Name = " + ni.getDisplayName());
      System.out.println(" MTU = " + ni.getMTU());
      Enumeration<InetAddress> addresses = ni.getInetAddresses();
      while (addresses.hasMoreElements()) {
        System.out.println("   Address = " + addresses.nextElement());
      }
    }

    registerSocketFactory();

    // API for UDP
    ScionDatagramSocket socket;

    // API for PathService
    // TODO rename
    ScionPathService pathService;

    // TODO -- TCP

    // TODO -- HTTP
    // Java 11: HTTPClient
    // Apache HttpClient, OkHttpClient, Spring WebClient ???
  }

  // http://www.java2s.com/example/java-src/pkg/org/jboss/as/capedwarf/extension/capedwarfsubsystemadd-c4d5a.html
  protected static void registerSocketFactory() throws IOException {
    Socket.setSocketImplFactory(SCIONSocketFactory.INSTANCE);
    DatagramSocket.setDatagramSocketImplFactory(SCIONSocketFactory.INSTANCE);
  }
 }

class SCIONSocketFactory implements SocketImplFactory, DatagramSocketImplFactory {
    static SCIONSocketFactory INSTANCE = null;

    @Override
    public SocketImpl createSocketImpl() {
        return null;
    }

    @Override
    public DatagramSocketImpl createDatagramSocketImpl() {
        return null;
    }
}