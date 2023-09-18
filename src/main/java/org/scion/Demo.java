package org.scion;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class Demo {


    public static void main(String[] args) throws SocketException {
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
}
