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

package org.scion.jpan.internal.paths;

import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import io.grpc.*;
import io.grpc.okhttp.OkHttpChannelBuilder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.scion.jpan.PathMetadata;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.internal.util.Config;
import org.scion.jpan.proto.daemon.Daemon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DaemonServiceGrpc {

  private static final Logger LOG = LoggerFactory.getLogger(DaemonServiceGrpc.class.getName());

  private ManagedChannel channel;
  private org.scion.jpan.proto.daemon.DaemonServiceGrpc.DaemonServiceBlockingStub grpcStub;
  private final int deadLineMs;

  public static DaemonServiceGrpc create(String addressOrHost) {
    return new DaemonServiceGrpc(addressOrHost);
  }

  private DaemonServiceGrpc(String addressOrHost) {
    initChannel(addressOrHost);
    this.deadLineMs = Config.getControlPlaneTimeoutMs();
  }

  public void close() {
    closeChannel();
  }

  private void closeChannel() {
    try {
      if (channel != null
          && !channel.shutdown().awaitTermination(1, TimeUnit.SECONDS)
          && !channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS)) {
        LOG.error("Failed to shut down ScionService gRPC ManagedChannel");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScionRuntimeException(e);
    }
  }

  private void initChannel(String addressOrHost) {
    closeChannel(); // close existing channel
    LOG.info("Bootstrapping with daemon: {}", addressOrHost);
    // TODO InsecureChannelCredentials: Implement authentication!
    // We are using OkHttp instead of Netty for Android compatibility
    channel =
        OkHttpChannelBuilder.forTarget(addressOrHost, InsecureChannelCredentials.create()).build();
    grpcStub = org.scion.jpan.proto.daemon.DaemonServiceGrpc.newBlockingStub(channel);
  }

  private org.scion.jpan.proto.daemon.DaemonServiceGrpc.DaemonServiceBlockingStub getStub() {
    // This is a deadline, not a timeout. It counts from the time the "with..." is called.
    // See also https://github.com/grpc/grpc-java/issues/1495
    // and https://github.com/grpc/grpc-java/issues/4305#issuecomment-378770067
    return grpcStub.withDeadlineAfter(deadLineMs, TimeUnit.MILLISECONDS);
  }

  public Daemon.ASResponse aS(Daemon.ASRequest request) {
    return getStub().aS(request);
  }

  public Daemon.PortRangeResponse portRange(Empty defaultInstance) {
    return getStub().portRange(defaultInstance);
  }

  public Daemon.InterfacesResponse interfaces(Daemon.InterfacesRequest request) {
    return getStub().interfaces(request);
  }

  public Daemon.PathsResponse paths(Daemon.PathsRequest request) {
    return getStub().paths(request);
  }

  public org.scion.jpan.proto.daemon.DaemonServiceGrpc.DaemonServiceBlockingStub getGrpcStub() {
    return getStub();
  }

  public Daemon.PathsResponse paths(long srcIsdAs, long dstIsdAs) {
    Daemon.PathsRequest request =
        Daemon.PathsRequest.newBuilder()
            .setSourceIsdAs(srcIsdAs)
            .setDestinationIsdAs(dstIsdAs)
            .build();
    try {
      return getStub().paths(request);
    } catch (StatusRuntimeException e) {
      throw new ScionRuntimeException(e);
    }
  }

  public List<PathMetadata> pathsAsMetadata(long srcIsdAs, long dstIsdAs) {
    List<Daemon.Path> dList = paths(srcIsdAs, dstIsdAs).getPathsList();
    return dList.stream().map(DaemonServiceGrpc::toPathMetadata).collect(Collectors.toList());
  }

  private static PathMetadata toPathMetadata(Daemon.Path path) {
    PathMetadata.Builder b = PathMetadata.newBuilder();
    b.setRaw(path.getRaw().toByteArray());
    path.getInterfacesList().stream()
        .map(pi -> PathMetadata.PathInterface.create(pi.getIsdAs(), pi.getId()))
        .forEach(b::addInterfaces);
    b.setLocalInterface(
        PathMetadata.Interface.create(path.getInterface().getAddress().getAddress()));
    b.setMtu(path.getMtu());
    b.setExpiration(path.getExpiration().getSeconds());
    for (Duration time : path.getLatencyList()) {
      int l =
          (time.getSeconds() < 0 || time.getNanos() < 0)
              ? -1
              : (int) (time.getSeconds() * 1_000 + time.getNanos() / 1_000_000);
      b.addLatency(l);
    }
    path.getBandwidthList().forEach(b::addBandwidth);
    for (Daemon.GeoCoordinates g : path.getGeoList()) {
      b.addGeo(
          PathMetadata.GeoCoordinates.create(g.getLatitude(), g.getLongitude(), g.getAddress()));
    }
    for (Daemon.LinkType linkType : path.getLinkTypeList()) {
      b.addLinkType(PathMetadata.LinkType.values()[linkType.getNumber()]);
    }
    path.getInternalHopsList().forEach(b::addInternalHops);
    path.getNotesList().forEach(b::addNotes);
    b.setEpicAuths(
        PathMetadata.EpicAuths.create(
            path.getEpicAuths().getAuthPhvf().toByteArray(),
            path.getEpicAuths().getAuthLhvf().toByteArray()));
    return b.build();
  }
}
