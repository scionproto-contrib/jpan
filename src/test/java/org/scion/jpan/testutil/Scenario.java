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
import org.scion.jpan.proto.crypto.Signed;

public class Scenario {

  protected static final long ZERO = ScionUtil.parseIA("0-0:0:0");
  private static final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();
  private final Map<Long, String> daemons = new HashMap<>();
  private final Map<Long, LocalTopology> topologies = new HashMap<>();
  private final List<SegmentEntry> segmentDb = new ArrayList<>();

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
          .map(path -> Paths.get(path.toString(), "topology.json"))
          .map(topoFile -> LocalTopology.create(readFile(topoFile)))
          .forEach(topo -> topologies.put(topo.getIsdAs(), topo));
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }

    buildSegments();
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
      daemons.put(ScionUtil.parseIA(e.getKey()), e.getValue().getAsString());
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

    // Build ingoing entry
    Seg.HopEntry he0 = buildHopEntry(0, buildHopField(63, prevIngress, parentIf.getId()));
    Seg.ASEntry as0 = buildASEntry(prevAs.getIsdAs(), local.getIsdAs(), prevAs.getMtu(), he0);
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
    Seg.ASEntry ase01 = buildASEntry(local.getIsdAs(), ZERO, local.getMtu(), he01);
    builder.addAsEntries(ase01);
    boolean isCore = BorderRouterInterface.CORE == linkType;
    segmentDb.add(new SegmentEntry(local.getIsdAs(), rootIsdAs, builder.build(), isCore));
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

  private static Seg.ASEntry buildASEntry(long isdAs, long nextIA, int mtu, Seg.HopEntry he) {
    Signed.Header header =
        Signed.Header.newBuilder()
            .setSignatureAlgorithm(Signed.SignatureAlgorithm.SIGNATURE_ALGORITHM_ECDSA_WITH_SHA256)
            .setTimestamp(now())
            .build();
    Seg.ASEntrySignedBody body =
        Seg.ASEntrySignedBody.newBuilder()
            .setIsdAs(isdAs)
            .setNextIsdAs(nextIA)
            .setMtu(mtu)
            .setHopEntry(he)
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
    // TODO correct? Set nanos?
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
}
