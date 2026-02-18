// Copyright 2025 ETH Zurich, Anapaya Systems
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

package org.scion.jpan.ppl;

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Path;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.daemon.Daemon;

public class PathProvider {

  private final Graph g = Graph.fromDescription(DefaultGen.DefaultGraphDescription);

  // PathInterface is an interface of the path.
  private static class PathInterface {
    // ID is the ID of the interface.
    long ID;
    // IA is the ISD AS identifier of the interface.
    long IA;

    PathInterface(long ID, long IA) {
      this.ID = ID;
      this.IA = IA;
    }
  }

  // Path is an snet.Path with full metadata
  private static class SnetPath {
    long src;
    long dst;
    List<PathInterface> pathIntfs;

    SnetPath(long src, long dst, List<PathInterface> pathIntfs) {
      this.src = src;
      this.dst = dst;
      this.pathIntfs = pathIntfs;
    }
  }

  private List<SnetPath> getPaths(long src, long dst) {
    List<SnetPath> result = new ArrayList<>();
    List<List<Integer>> paths = g.getPaths(src, dst);
    for (List<Integer> ifIDs : paths) {
      List<PathInterface> pathIntfs = new ArrayList<>();
      for (int ifID : ifIDs) {
        long ia = g.getParent(ifID);
        pathIntfs.add(new PathInterface(ifID, ia));
      }
      long srcIA = 0;
      long dstIA = 0;
      if (!pathIntfs.isEmpty()) {
        srcIA = pathIntfs.get(0).IA;
        dstIA = pathIntfs.get(pathIntfs.size() - 1).IA;
      }
      result.add(new SnetPath(srcIA, dstIA, pathIntfs));
    }
    return result;
  }

  public List<Path> getPaths(String srcIsdAs, String dstIsdAs) {
    try {
      InetAddress addr = InetAddress.getByAddress(new byte[] {123, 123, 123, 123});
      InetSocketAddress dst = new InetSocketAddress(addr, 12321);
      return getPaths(dst, ScionUtil.parseIA(srcIsdAs), ScionUtil.parseIA(dstIsdAs));
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  public List<Path> getPaths(InetSocketAddress dst, long srcIsdAs, long dstIsdAs) {
    List<Path> paths = new ArrayList<>();
    for (SnetPath snetPath : getPaths(srcIsdAs, dstIsdAs)) {
      Daemon.Path path = protoPathFrom(snetPath);
      paths.add(PackageVisibilityHelper.createRequestPath(path, srcIsdAs, dstIsdAs, dst));
    }
    return paths;
  }

  private Daemon.Path protoPathFrom(SnetPath snetPath) {
    Daemon.Path.Builder path = Daemon.Path.newBuilder();
    Random rnd = new Random();
    long now = System.currentTimeMillis() / 1000;
    path.setExpiration(Timestamp.newBuilder().setSeconds(now + rnd.nextInt(3600)).build());
    path.setMtu(1000 + rnd.nextInt(1000));
    // path.setInterface(Daemon.PathInterface.newBuilder().setAddress(Daemon.Address.getDefaultInstance()));
    for (PathInterface pathIntf : snetPath.pathIntfs) {
      path.addInterfaces(
          Daemon.PathInterface.newBuilder().setId(pathIntf.ID).setIsdAs(pathIntf.IA));
      if (rnd.nextInt(20) > 2) {
        // We should add them per AS, not per interface....!!!
        path.addLatency(Duration.newBuilder().setNanos(rnd.nextInt(1_000_000_000)).build());
        // We should add them per AS, not per interface....!!!
        path.addBandwidth(rnd.nextInt(1_000_000_000));
      } else {
        path.addLatency(Duration.newBuilder().setNanos(-1).build());
        path.addBandwidth(0);
      }
    }
    return path.build();
  }
}
