// Copyright 2025 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.jpan.internal;

import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockNetwork;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathMetadataTest {

    @Test
    void timestamp() throws UnknownHostException {
        MockNetwork.startTiny(MockNetwork.Mode.AS_ONLY);
        InetAddress ia = InetAddress.getByAddress(ExamplePacket.DST_HOST);
        Scion.newServiceWithTopologyFile("topologies/tiny4/ASff00_0_112/topology.json");
        List<Path> paths = Scion.defaultService().getPaths(ExamplePacket.DST_IA, ia, 12345);
        Path path = paths.get(0);
        long exp = path.getMetadata().getExpiration();
        System.out.println("as: " + Scion.defaultService().getLocalIsdAs());
        System.out.println("as: " + ExamplePacket.DST_IA);
        System.out.println("exp: " + exp);
        assertTrue(exp > 0);
    }

}
