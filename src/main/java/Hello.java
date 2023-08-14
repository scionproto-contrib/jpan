import org.scion.proto.Daemon;
import org.scion.proto.Drkey;

public class Hello {
    public static void main(String[] args) {
        Drkey.Protocol protocol = Drkey.Protocol.forNumber(1);
        System.out.println("Protocol: " + protocol);

        Daemon.Service daemonService = Daemon.Service.newBuilder().build();
        System.out.println("DaemonService: " + daemonService.getUri());
    }
}
