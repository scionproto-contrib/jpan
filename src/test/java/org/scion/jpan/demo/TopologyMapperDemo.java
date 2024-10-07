// Copyright 2023 ETH Zurich
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

package org.scion.jpan.demo;

import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;

import org.scion.jpan.*;
import org.scion.jpan.testutil.MockDNS;
import org.scion.jpan.testutil.Scenario;

/**
 * This demo mimics the "scion ping" command available in scionproto (<a
 * href="https://github.com/scionproto/scion">...</a>). This demo also demonstrates different ways
 * of connecting to a network: <br>
 * - JUNIT_MOCK shows how to use the mock network in this library (for JUnit tests) <br>
 * - SCION_PROTO shows how to connect to a local topology from the scionproto go implementation such
 * as "tiny". Note that the constants for "minimal" differ somewhat from the scionproto topology.
 * <br>
 * - PRODUCTION shows different ways how to connect to the production network. Note: While the
 * production network uses the dispatcher, the demo needs to use port 30041.
 *
 * <p>Commented out lines show alternative ways to connect or alternative destinations.
 */
public class TopologyMapperDemo {

    public static boolean PRINT = true;
    private static Network NETWORK = Network.PRODUCTION;
    private final int localPort;

    public enum Network {
        JUNIT_MOCK, // SCION Java JUnit mock network
        SCION_PROTO, // Try to connect to scionproto networks, e.g. "tiny"
        PRODUCTION // production network
    }

    public static void init(boolean print, TopologyMapperDemo.Network network) {
        PRINT = print;
        NETWORK = network;
    }

    public TopologyMapperDemo() {
        this(12345); // Any port is fine unless we connect to a dispatcher network
    }

    public TopologyMapperDemo(int localPort) {
        this.localPort = localPort;
    }

    public static void main(String[] args) throws IOException {
        switch (NETWORK) {
            case JUNIT_MOCK: {
                DemoTopology.configureMock();
                MockDNS.install("1-ff00:0:112", "ip6-localhost", "::1");
                TopologyMapperDemo demo = new TopologyMapperDemo();
                demo.runDemo(DemoConstants.ia110);
                DemoTopology.shutDown();
                break;
            }
            case SCION_PROTO: {
                // ./bazel-bin/scion/cmd/scion/scion_/scion showpaths 2-ff00:0:222 --sciond 127.0.0.100:30255

                // Use scenario builder to get access to relevant IP addresses
                Scenario scenario = Scenario.readFrom("topologies/scionproto-default");
                // long srcIsdAs = ScionUtil.parseIA("2-ff00:0:212");
                //long srcIsdAs = ScionUtil.parseIA("1-ff00:0:132"); // TODO FIX!!!
                long srcIsdAs = ScionUtil.parseIA("1-ff00:0:133");
                long dstIsdAs = ScionUtil.parseIA("2-ff00:0:222");

                if (!true) {
                    // Alternative #1: Bootstrap from topo file
                    System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE, "topologies/scionproto-default/ASff00_0_212/topology.json");
                } else {
                    // Alternative #2: Bootstrap from SCION daemon
                    System.setProperty(Constants.PROPERTY_DAEMON, scenario.getDaemon(srcIsdAs));
                }

//          // System.setProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE,
//          // "topologies/minimal/ASff00_0_1111/topology.json");
//          //System.setProperty(Constants.PROPERTY_DAEMON, "[fd00:f00d:cafe::7f00:33]:31060"); // 0;132
//          System.setProperty(Constants.PROPERTY_DAEMON, "127.0.0.99:31066"); // 0:133
                TopologyMapperDemo demo = new TopologyMapperDemo();
                //demo.runDemo(ScionUtil.parseIA("2-ff00:0:211"));
                demo.runDemo(ScionUtil.parseIA("2-ff00:0:222"));
                break;
            }
            case PRODUCTION: {
                // Local port must be 30041 for networks that expect a dispatcher
                TopologyMapperDemo demo = new TopologyMapperDemo(Constants.DISPATCHER_PORT);
                demo.runDemo(ScionUtil.parseIA("64-2:0:28"));
                // demo.runDemo(DemoConstants.iaAnapayaHK);
                // demo.runDemo(DemoConstants.iaOVGU);
                break;
            }
        }
        Scion.closeDefault();
    }

    private void runDemo(long destinationIA) throws IOException {
        ScionService service = Scion.defaultService();
        // dummy address
        InetSocketAddress destinationAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
        List<Path> paths = service.getPaths(destinationIA, destinationAddress);
        if (paths.isEmpty()) {
            String src = ScionUtil.toStringIA(service.getLocalIsdAs());
            String dst = ScionUtil.toStringIA(destinationIA);
            throw new IOException("No path found from " + src + " to " + dst);
        }

        println("Listening on port " + localPort + " ...");
        println("Available paths to " + ScionUtil.toStringIA(destinationIA));

        int id = 0;
        for (Path path : paths) {
            PathMetadata meta = path.getMetadata();
            println("[" + id++ + "] Hops: " + ScionUtil.toStringPath(meta));
        }
        analyze(paths);
    }

    private static void println(String msg) {
        if (PRINT) {
            System.out.println(msg);
        }
    }

    private static class AsInfo {
        final long isdAs;
        final Map<Long, AsLink> links = new HashMap<>();

        AsInfo(long isdAs) {
            this.isdAs = isdAs;
        }

        public void addLink(long id, AsLink link) {
            links.put(id, link);
        }
    }

    private static class AsLink {
        private final long as0;
        private final long as1;
        private final long id0;
        private final long id1;

        public AsLink(long isdAs, long nextIsdAs, long id0, long id1) {
            this.as0 = isdAs;
            this.as1 = nextIsdAs;
            this.id0 = id0;
            this.id1 = id1;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AsLink)) {
                return false;
            }
            AsLink asLink = (AsLink) o;
            return as0 == asLink.as0 && as1 == asLink.as1 && id0 == asLink.id0 && id1 == asLink.id1;
        }

        @Override
        public int hashCode() {
            return Objects.hash(as0, as1, id0, id1);
        }
    }

    private static void analyze(List<Path> paths) throws IOException {
        Map<Long, AsInfo> asMap = new HashMap<>();
        Set<AsLink> asLinks = new HashSet<>();
        for (Path p : paths) {
            PathMetadata pm = p.getMetadata();
            int nInterfaces = pm.getInterfacesList().size();
            AsInfo asPrev = null;
            long idPrev = -1;

            for (int i = 0; i < nInterfaces; i++) {
                PathMetadata.PathInterface pIf = pm.getInterfacesList().get(i);

                if (i % 2 == 0) {
                    long isdAs = pIf.getIsdAs();
                    asPrev = asMap.computeIfAbsent(isdAs, l -> l > 0 ? new AsInfo(l) : null);
                    idPrev = pIf.getId();
                    //          sb.append(ScionUtil.toStringIA(pIf.getIsdAs())).append(" ");
                    //          sb.append(pIf.getId()).append(">");
                } else {
                    long isdAs = pIf.getIsdAs();
                    long id1 = pIf.getId();
                    AsLink link = new AsLink(asPrev.isdAs, isdAs, idPrev, id1);
                    asLinks.add(link);
                    //          sb.append(pIf.getId()).append(" ");
                }
            }
        }

        write(asMap, asLinks);
    }

    private static void write(Map<Long, AsInfo> asMap, Set<AsLink> asLinks) throws IOException {
        // write
        FileWriter writer = new FileWriter("mytopo.topo");

        String NL = System.lineSeparator();
        writer.append("--- # My Topology").append(NL);
        writer.append("ASes:").append(NL);
        for (AsInfo as : asMap.values()) {
            writer.append("  \"").append(ScionUtil.toStringIA(as.isdAs)).append("\":").append(NL);
            if (coreMap.contains(as.isdAs)) {
                writer.append("    core: true").append(NL);
                writer.append("    voting: true").append(NL);
                writer.append("    authoritative: true").append(NL);
            }
            if (issuingMap.contains(as.isdAs)) {
                writer.append("    issuing: true").append(NL);
            } else {
                writer.append("    cert_issuer: 1-ff00:0:210").append(NL); // TODO
            }
        }
        writer.append("links:").append(NL);
        //   - {a: "1-ff00:0:1001#21", b: "1-ff00:0:1002#11",  linkAtoB: CORE}
        for (AsLink l : asLinks) {
            writer.append("  - {a: \"").append(ScionUtil.toStringIA(l.as0)).append("#").append(String.valueOf(l.id0));
            writer.append("\", b: \"").append(ScionUtil.toStringIA(l.as1)).append("#").append(String.valueOf(l.id1));
            writer.append("\",  linkAtoB: CORE}").append(NL);
        }

        writer.flush();
        writer.close();
    }

    private static final HashSet<Long> coreMap = new HashSet<>();
    private static final HashSet<Long> issuingMap = new HashSet<>();

    static {
//        64 - 559  //    SWITCH CH    Core
//        64 - 3303 //  Swisscom CH   Core, Issuing
//        64 - 6730  //  Sunrise CH    Core
//        64 - 12350 //    VTX CH    Core
//        64 - 13030  //  Init7 CH   Core
//        64 - 15623 //  Cyberlink        Core
//        64 - 2:0:13 //  Anapaya CONNECT Zurich   Core, Issuing
//        64 - 2:0:23 //    InterCloud CH Zurich      Core
//        64 - 2:0:56 //    Armasuisse Andermatt    Core
        coreMap.add(ScionUtil.parseIA("64-559"));           //    SWITCH CH    Core
        coreMap.add(ScionUtil.parseIA("64-3303")); //  Swisscom CH   Core, Issuing
        issuingMap.add(ScionUtil.parseIA("64-3303")); //  Swisscom CH   Core, Issuing
        coreMap.add(ScionUtil.parseIA("64-6730"));  //  Sunrise CH    Core
        coreMap.add(ScionUtil.parseIA("64-12350")); //    VTX CH    Core
        coreMap.add(ScionUtil.parseIA("64-13030"));  //  Init7 CH   Core
        coreMap.add(ScionUtil.parseIA("64-15623")); //  Cyberlink        Core
        coreMap.add(ScionUtil.parseIA("64-2:0:13")); //  Anapaya CONNECT Zurich   Core, Issuing
        issuingMap.add(ScionUtil.parseIA("64-2:0:13")); //  Anapaya CONNECT Zurich   Core, Issuing
        coreMap.add(ScionUtil.parseIA("64-2:0:23")); //    InterCloud CH Zurich      Core
        coreMap.add(ScionUtil.parseIA("64-2:0:56")); //    Armasuisse Andermatt    Core

    }
}
