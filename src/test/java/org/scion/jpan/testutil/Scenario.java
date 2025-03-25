// Copyright 2024 ETH Zurich
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

package org.scion.jpan.testutil;

import static org.scion.jpan.internal.LocalTopology.BorderRouter;
import static org.scion.jpan.internal.LocalTopology.BorderRouterInterface;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.LocalTopology;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.control_plane.SegExtensions;
import org.scion.jpan.proto.crypto.Signed;

public class Scenario {

  protected static final long ZERO = ScionUtil.parseIA("0-0:0:0");
  private static final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();
  private final Map<Long, String> daemons = new HashMap<>();
  private final Map<Long, LocalTopology> topologies = new HashMap<>();
  private final Map<Long, StaticInfo> staticInfo = new HashMap<>();
  /// isd/as -> ingress -> egress -> hopcount
  private final List<SegmentEntry> segmentDb = new ArrayList<>();

  private static class StaticInfo {
    private final Map<Long, SegExtensions.GeoCoordinates> geo = new HashMap<>();
    private final Map<Long, Long> bandwidthInter = new HashMap<>();
    private final Map<Long, Map<Long, Long>> bandwidthIntra = new HashMap<>();
    private final Map<Long, Integer> latencyInter = new HashMap<>();
    private final Map<Long, Map<Long, Integer>> latencyIntra = new HashMap<>();
    private final Map<Long, SegExtensions.LinkType> linkTypes = new HashMap<>();
    private final Map<Long, Map<Long, Integer>> internalHops = new HashMap<>();
    private String notes;

    SegExtensions.StaticInfoExtension build(long id1, long id2) {
      SegExtensions.StaticInfoExtension.Builder builder = SegExtensions.StaticInfoExtension.newBuilder();
      SegExtensions.LatencyInfo.Builder lb = SegExtensions.LatencyInfo.newBuilder();
      if (id1 > 0) {
        lb.putInter(id1, latencyInter.get(id1));
        if (id2 > 0) {
          lb.putIntra(id2, latencyIntra.get(id2).get(id1));
        }
      }
      builder.setLatency(lb);
      SegExtensions.BandwidthInfo.Builder bb = SegExtensions.BandwidthInfo.newBuilder();
      if (id1 > 0) {
        bb.putInter(id1, bandwidthInter.get(id1));
        if (id2 > 0) {
          bb.putIntra(id2, bandwidthIntra.get(id2).get(id1));
        }
      }
      builder.setBandwidth(bb);
      if (geo.containsKey(id1)) {
        builder.putGeo(id1, geo.get(id1));
      }
      if (geo.containsKey(id2)) {
        builder.putGeo(id2, geo.get(id2));
      }
      if (internalHops.containsKey(id1) && internalHops.get(id1).containsKey(id2)) {
          builder.putInternalHops(id2, internalHops.get(id1).get(id2));
      }
      if (linkTypes.containsKey(id1)) {
        builder.putLinkType(id1, linkTypes.get(id1));
      }
      if (notes != null) {
        builder.setNote(notes);
      }
      return builder.build();
    }
  }

  private static class SegmentEntry {
    final long localAS;
    final long originAS;
    final boolean isCoreSegment;
    Seg.PathSegment segment;

    SegmentEntry(long localAS, long originAS, Seg.PathSegment segment, boolean isCoreSegment) {
      this.localAS = localAS;
      this.originAS = originAS;
      this.isCoreSegment = isCoreSegment;
      this.segment = segment;
    }
  }

  public static Scenario readFrom(String pathName) {
    return readFrom(Paths.get(pathName));
  }

  public static Scenario readFrom(Path pathName) {
    Path resolved = resolve(pathName);
    return scenarios.computeIfAbsent(resolved.toString(), path -> new Scenario(resolved));
  }

  private static Path resolve(Path pathName) {
    if (!Files.exists(pathName)) {
      // fallback, try resource folder
      ClassLoader classLoader = Scenario.class.getClassLoader();
      URL resource = classLoader.getResource(pathName.toString());
      if (resource != null) {
        try {
          return Paths.get(resource.toURI());
        } catch (URISyntaxException e) {
          throw new ScionRuntimeException(e);
        }
      }
    }
    return pathName;
  }

  public InetSocketAddress getControlServer(long isdAs) {
    LocalTopology topo = topologies.get(isdAs);
    String addr = topo.getControlServerAddress();
    int separate = addr.lastIndexOf(':');
    String ip = addr.substring(0, separate);
    String port = addr.substring(separate + 1);
    return new InetSocketAddress(ip, Integer.parseInt(port));
  }

  public String getDaemon(long isdAs) {
    return daemons.get(isdAs) + ":30255";
  }

  public List<Seg.PathSegment> getSegments(long srcIsdAs, long dstIsdAs) {
    List<Seg.PathSegment> result = new ArrayList<>();
    for (SegmentEntry se : segmentDb) {
      if (se.localAS == srcIsdAs && se.originAS == dstIsdAs) {
        result.add(se.segment);
      }
    }
    return result;
  }

  private Scenario(Path file) {
    File parent = file.toFile();
    if (!parent.isDirectory()) {
      throw new IllegalStateException();
    }

    Path addresses = Paths.get(parent.getPath(), "sciond_addresses.json");
    parseSciondAddresses(readFile(addresses));

    try {
      Files.list(file)
          .filter(path -> path.getFileName().toString().startsWith("AS"))
          .forEach(this::readAS);
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }

    buildSegments();
  }

  private void readAS(Path asPath) {
    Path topoFile = Paths.get(asPath.toString(), "topology.json");
    LocalTopology topo = LocalTopology.create(readFile(topoFile));
    topologies.put(topo.getIsdAs(), topo);

    Path infoFile = Paths.get(asPath.toString(), "staticInfoConfig.json");
    if (infoFile.toFile().exists()) {
      System.out.println("Parsing info file: " + infoFile); // TODO
      parseStaticInfo(topo.getIsdAs(), readFile(infoFile));
    }
  }

  private static String readFile(Path file) {
    StringBuilder contentBuilder = new StringBuilder();
    try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
      stream.forEach(s -> contentBuilder.append(s).append("\n"));
    } catch (IOException e) {
      throw new ScionRuntimeException("Error reading file: " + file.toAbsolutePath(), e);
    }
    return contentBuilder.toString();
  }

  private void parseSciondAddresses(String content) {
    JsonElement jsonTree = JsonParser.parseString(content);
    JsonObject entry = jsonTree.getAsJsonObject();
    for (Map.Entry<String, JsonElement> e : entry.entrySet()) {
      String addr = e.getValue().getAsString();
      if (addr.contains(":") && !addr.contains("[") && !addr.contains(".")) {
        addr = "[" + addr + "]";
      }
      daemons.put(ScionUtil.parseIA(e.getKey()), addr);
    }
  }

  private void buildSegments() {
    List<LocalTopology> cores = new ArrayList<>();
    topologies.values().stream().filter(LocalTopology::isCoreAs).forEach(cores::add);
    for (LocalTopology core : cores) {
      for (BorderRouter br : core.getBorderRouters()) {
        for (BorderRouterInterface brIf : br.getInterfaces()) {
          LocalTopology nextAs = topologies.get(brIf.getIsdAs());
          // Choose some "random" segment ID
          int segmentId = 10000 + brIf.getId();
          if (nextAs.isCoreAs()) {
            buildSegment(core, brIf, BorderRouterInterface.CORE, segmentId);
          } else {
            buildSegment(core, brIf, BorderRouterInterface.CHILD, segmentId);
          }
        }
      }
    }
  }

  private void buildSegment(
      LocalTopology parent, BorderRouterInterface parentIf, String linkType, int segmentId) {
    long now = Instant.now().getEpochSecond();
    Seg.SegmentInformation info =
        Seg.SegmentInformation.newBuilder().setSegmentId(segmentId).setTimestamp(now).build();
    Seg.PathSegment.Builder builder =
        Seg.PathSegment.newBuilder().setSegmentInfo(info.toByteString());
    buildChild(builder, linkType, parent.getIsdAs(), parent, 0, parentIf);
  }

  private void buildChild(
      Seg.PathSegment.Builder builder,
      String linkType,
      long rootIsdAs,
      LocalTopology prevAs,
      int prevIngress,
      BorderRouterInterface parentIf) {
    LocalTopology local = topologies.get(parentIf.getIsdAs());
    boolean reversed = false;
    long isdAs = local.getIsdAs();
    PRINT =
        (isdAs == ScionUtil.parseIA("1-ff00:0:110")
                && rootIsdAs == ScionUtil.parseIA("1-ff00:0:120"))
            || (isdAs == ScionUtil.parseIA("1-ff00:0:120")
                && rootIsdAs == ScionUtil.parseIA("1-ff00:0:110"));

    // Build ingoing entry
    Seg.HopEntry he0 = buildHopEntry(0, buildHopField(63, prevIngress, parentIf.getId()));
    Seg.ASEntry as0 = buildASEntry(prevAs.getIsdAs(), local.getIsdAs(), prevAs.getMtu(), he0, reversed);
    builder.addAsEntries(as0);

    Set<Long> visited =
        builder.getAsEntriesList().stream()
            .map(Scenario::getBody)
            .map(Seg.ASEntrySignedBody::getIsdAs)
            .collect(Collectors.toSet());

    // Find ingress interface
    int ingress = -1;
    for (BorderRouter br : local.getBorderRouters()) {
      for (BorderRouterInterface brIf : br.getInterfaces()) {
        if (prevAs.getIsdAs() == brIf.getIsdAs()
            && parentIf.getRemoteUnderlay().equals(brIf.getPublicUnderlay())) {
          ingress = brIf.getId();
        }
      }
    }
    if (ingress == -1) {
      throw new IllegalStateException();
    }

    // Traverse children
    for (BorderRouter br : local.getBorderRouters()) {
      for (BorderRouterInterface brIf : br.getInterfaces()) {
        if (linkType.equals(brIf.getLinkTo()) && !visited.contains(brIf.getIsdAs())) {
          Seg.PathSegment.Builder childBuilder =
              Seg.PathSegment.newBuilder(builder.build()); // TODO buildPartial() ?
          buildChild(childBuilder, linkType, rootIsdAs, local, ingress, brIf);
        }
      }
    }

    // Add ingress interface
    Seg.HopEntry he01 = buildHopEntry(parentIf.getMtu(), buildHopField(63, ingress, 0));
    Seg.ASEntry ase01 = buildASEntry(local.getIsdAs(), ZERO, local.getMtu(), he01, reversed);
    builder.addAsEntries(ase01);
    boolean isCore = BorderRouterInterface.CORE.equals(linkType);
    segmentDb.add(new SegmentEntry(local.getIsdAs(), rootIsdAs, builder.build(), isCore));
    println(
        "Added segment: " + ScionUtil.toStringIA(isdAs) + " -> " + ScionUtil.toStringIA(rootIsdAs));
    SegmentEntry se = segmentDb.get(segmentDb.size() - 1);
    println("   ASEntries: " + se.segment.getAsEntriesList().size());

    StringBuilder sb = new StringBuilder();
    for (Seg.ASEntry ase : se.segment.getAsEntriesList()) {
        Seg.ASEntrySignedBody b = getBody(ase);
      long eg = b.getHopEntry().getHopField().getEgress();
      long ing = b.getHopEntry().getHopField().getIngress();
      if (sb.length() > 0) {
        sb.append("#").append(ing).append(" --- ");
      }
        sb.append(ScionUtil.toStringIA(b.getIsdAs()));
        sb.append(" ").append(eg).append(" > ");
        sb.append(ScionUtil.toStringIA(b.getNextIsdAs()));
    }
    System.out.println("Segment: " + sb.toString()); // TODO
  }

  private static Seg.HopField buildHopField(int expiry, int ingress, int egress) {
    ByteString mac = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6});
    return Seg.HopField.newBuilder()
        .setExpTime(expiry)
        .setIngress(ingress)
        .setEgress(egress)
        .setMac(mac)
        .build();
  }

  private static Seg.HopEntry buildHopEntry(int mtu, Seg.HopField hf) {
    return Seg.HopEntry.newBuilder().setIngressMtu(mtu).setHopField(hf).build();
  }

  private Seg.ASEntry buildASEntry(long isdAs, long nextIA, int mtu, Seg.HopEntry he, boolean reversed) {
    Signed.Header header =
        Signed.Header.newBuilder()
            .setSignatureAlgorithm(Signed.SignatureAlgorithm.SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256)
            .setTimestamp(now())
            .build();

    String ia1 = ScionUtil.toStringIA(isdAs);
    String ia2 = ScionUtil.toStringIA(nextIA);

    SegExtensions.PathSegmentExtensions.Builder ext =
        SegExtensions.PathSegmentExtensions.newBuilder();
    if (staticInfo.containsKey(isdAs)) {
      boolean x =
              (isdAs == ScionUtil.parseIA("1-ff00:0:110")
                      && nextIA == ScionUtil.parseIA("1-ff00:0:120"))
                      || (isdAs == ScionUtil.parseIA("1-ff00:0:120")
                      && nextIA == ScionUtil.parseIA("1-ff00:0:110"));
      if (x) {
        println("      bASE: " + ia1 + " -> " + ia2);
      }
      Seg.HopField hf = he.getHopField();
      if (reversed) {
        ext.setStaticInfo(staticInfo.get(isdAs).build(hf.getEgress(), hf.getIngress()));
      } else {
        ext.setStaticInfo(staticInfo.get(isdAs).build(hf.getIngress(), hf.getEgress()));
      }
    }

    Seg.ASEntrySignedBody body =
        Seg.ASEntrySignedBody.newBuilder()
            .setIsdAs(isdAs)
            .setNextIsdAs(nextIA)
            .setMtu(mtu)
            .setHopEntry(he)
            .setExtensions(ext.build())
            .build();
    Signed.HeaderAndBodyInternal habi =
        Signed.HeaderAndBodyInternal.newBuilder()
            .setHeader(header.toByteString())
            .setBody(body.toByteString())
            .build();
    Signed.SignedMessage sm =
        Signed.SignedMessage.newBuilder().setHeaderAndBody(habi.toByteString()).build();
    return Seg.ASEntry.newBuilder().setSigned(sm).build();
  }

  private static Seg.PathSegment buildPath(int id, Seg.ASEntry... entries) {
    long now = Instant.now().getEpochSecond();
    Seg.SegmentInformation info =
        Seg.SegmentInformation.newBuilder().setSegmentId(id).setTimestamp(now).build();
    Seg.PathSegment.Builder builder =
        Seg.PathSegment.newBuilder().setSegmentInfo(info.toByteString());
    builder.addAllAsEntries(Arrays.asList(entries));
    return builder.build();
  }

  private static Seg.SegmentsResponse buildResponse(
      Seg.SegmentType type, Seg.PathSegment... paths) {
    Seg.SegmentsResponse.Builder replyBuilder = Seg.SegmentsResponse.newBuilder();
    Seg.SegmentsResponse.Segments segments =
        Seg.SegmentsResponse.Segments.newBuilder().addAllSegments(Arrays.asList(paths)).build();
    replyBuilder.putSegments(type.getNumber(), segments);
    return replyBuilder.build();
  }

  private static Timestamp now() {
    Instant now = Instant.now();
    return Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();
  }

  private static Seg.ASEntrySignedBody getBody(Seg.ASEntry asEntry) {
    if (!asEntry.hasSigned()) {
      throw new UnsupportedOperationException("Unsigned entries are not supported");
    }
    Signed.SignedMessage sm = asEntry.getSigned();
    try {
      Signed.HeaderAndBodyInternal habi =
          Signed.HeaderAndBodyInternal.parseFrom(sm.getHeaderAndBody());
      return Seg.ASEntrySignedBody.parseFrom(habi.getBody());
    } catch (InvalidProtocolBufferException e) {
      throw new ScionRuntimeException(e);
    }
  }

  private void parseStaticInfo(long isdAs, String content) {
    JsonElement jsonTree = JsonParser.parseString(content);
    JsonObject entry = jsonTree.getAsJsonObject();
    PRINT =
        isdAs == ScionUtil.parseIA("1-ff00:0:120") || isdAs == ScionUtil.parseIA("1-ff00:0:110");
    StaticInfo sie = new StaticInfo();
    for (Map.Entry<String, JsonElement> e : entry.entrySet()) {
      switch (e.getKey()) {
        case "Bandwidth":
          println("Parsing Bandwidth");
          for (Map.Entry<String, JsonElement> e2 : e.getValue().getAsJsonObject().entrySet()) {
            for (Map.Entry<String, JsonElement> e3 : e2.getValue().getAsJsonObject().entrySet()) {
              if (e3.getKey().equals("Intra")) {
                for (Map.Entry<String, JsonElement> e4 :
                    e3.getValue().getAsJsonObject().entrySet()) {
                  Map<Long, Long> bwIn = sie.bandwidthIntra.computeIfAbsent(Long.parseLong(e2.getKey()), l -> new HashMap<>());
                  bwIn.put(Long.parseLong(e4.getKey()), e4.getValue().getAsLong());
                }
              } else if (e3.getKey().equals("Inter")) {
                println("Parsing bw Inter: " + e2.getKey() + " " + e3.getValue().getAsLong());
                sie.bandwidthInter.put(Long.parseLong(e2.getKey()), e3.getValue().getAsLong());
              }
            }
          }
          break;
        case "Latency":
          println("Parsing Latency");
          for (Map.Entry<String, JsonElement> e2 : e.getValue().getAsJsonObject().entrySet()) {
            for (Map.Entry<String, JsonElement> e3 : e2.getValue().getAsJsonObject().entrySet()) {
              if (e3.getKey().equals("Intra")) {
                for (Map.Entry<String, JsonElement> e4 :
                    e3.getValue().getAsJsonObject().entrySet()) {
                  Map<Long, Integer> latIn = sie.latencyIntra.computeIfAbsent(Long.parseLong(e2.getKey()), l -> new HashMap<>());
                  latIn.put(Long.parseLong(e4.getKey()), getMicros(e4.getValue()));
                }
              } else if (e3.getKey().equals("Inter")) {
                println("Parsing lat Inter: " + e2.getKey() + " " + e3.getValue().getAsString());
                sie.latencyInter.put(Long.parseLong(e2.getKey()), getMicros(e3.getValue()));
              }
            }
          }
          break;
        case "Linktype":
          println("Parsing LinkType");
          for (Map.Entry<String, JsonElement> e2 : e.getValue().getAsJsonObject().entrySet()) {
            sie.linkTypes.put(Long.parseLong(e2.getKey()), toLinkType(e2.getValue().getAsString()));
          }
          break;
        case "Geo":
          println("Parsing Geo");
          for (Map.Entry<String, JsonElement> e2 : e.getValue().getAsJsonObject().entrySet()) {
            SegExtensions.GeoCoordinates.Builder gc = SegExtensions.GeoCoordinates.newBuilder();
            for (Map.Entry<String, JsonElement> e3 : e2.getValue().getAsJsonObject().entrySet()) {
              switch (e3.getKey()) {
                case "Latitude":
                  gc.setLatitude(e3.getValue().getAsFloat());
                  break;
                case "Longitude":
                  gc.setLongitude(e3.getValue().getAsFloat());
                  break;
                case "Address":
                  gc.setAddress(e3.getValue().getAsString());
                  break;
                default:
                  throw new UnsupportedOperationException();
              }
            }
            sie.geo.put(Long.parseLong(e2.getKey()), gc.build());
          }
          break;
        case "Hops":
          println("Parsing Hops");
          for (Map.Entry<String, JsonElement> e2 : e.getValue().getAsJsonObject().entrySet()) {
            for (Map.Entry<String, JsonElement> e3 : e2.getValue().getAsJsonObject().entrySet()) {
              if (e3.getKey().equals("Intra")) {
                for (Map.Entry<String, JsonElement> e4 :
                    e3.getValue().getAsJsonObject().entrySet()) {
                  long id1 = Long.parseLong(e2.getKey());
                  long id2 = Long.parseLong(e4.getKey());
                  int hc = e4.getValue().getAsInt();
                  sie.internalHops.computeIfAbsent(id1, k -> new HashMap<>()).put(id2, hc);
                  sie.internalHops.computeIfAbsent(id2, k -> new HashMap<>()).put(id1, hc);
                }
              } else {
                throw new UnsupportedOperationException();
              }
            }
          }
          break;
        case "Note":
          println("Parsing Notes");
          sie.notes = e.getValue().getAsString();
          break;
        default:
          throw new UnsupportedOperationException("Unknown: " + e.getKey());
      }
    }
    staticInfo.put(isdAs, sie);
  }

  private static boolean PRINT = true;

  private static void println(String msg) {
    if (PRINT) {
      System.out.println(msg);
    }
  }

  private static int getMicros(JsonElement e) {
    String lat = e.getAsString();
    if (!e.getAsString().endsWith("ms")) {
      throw new IllegalArgumentException("Bad latency entry: " + lat);
    }
    return Integer.parseInt(lat.substring(0, lat.length() - 2)) * 1000;
  }

  private static SegExtensions.LinkType toLinkType(String lt) {
    switch (lt) {
      case "unspecified":
        return SegExtensions.LinkType.LINK_TYPE_UNSPECIFIED;
      case "direct":
        return SegExtensions.LinkType.LINK_TYPE_DIRECT;
      case "multihop":
        return SegExtensions.LinkType.LINK_TYPE_MULTI_HOP;
      case "opennet":
        return SegExtensions.LinkType.LINK_TYPE_OPEN_NET;
      default:
        throw new IllegalArgumentException("Linktype: " + lt);
    }
  }
}
