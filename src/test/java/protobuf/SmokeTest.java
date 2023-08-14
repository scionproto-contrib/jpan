package protobuf;

import org.junit.jupiter.api.Test;
import org.scion.proto.Daemon;
import org.scion.proto.Drkey;

import static org.junit.jupiter.api.Assertions.*;

public class SmokeTest {

    @Test
    public void smoketest() {
        Drkey.Protocol protocol = Drkey.Protocol.forNumber(1);
        assertEquals(1, protocol.getNumber());

        Daemon.Service daemonService = Daemon.Service.newBuilder().build();
        assertNotNull(daemonService.getUri());
    }
}
