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

import com.google.protobuf.Empty;
import io.grpc.*;
import io.grpc.okhttp.OkHttpChannelBuilder;
import java.util.concurrent.TimeUnit;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.proto.daemon.Daemon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DaemonServiceGrpc {

  private static final Logger LOG = LoggerFactory.getLogger(DaemonServiceGrpc.class.getName());

  private ManagedChannel channel;
  private org.scion.jpan.proto.daemon.DaemonServiceGrpc.DaemonServiceBlockingStub grpcStub;

  public static DaemonServiceGrpc create(String addressOrHost) {
    return new DaemonServiceGrpc(addressOrHost);
  }

  private DaemonServiceGrpc(String addressOrHost) {
    initChannel(addressOrHost);
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

  public Daemon.ASResponse aS(Daemon.ASRequest request) {
    return grpcStub.aS(request);
  }

  public Daemon.PortRangeResponse portRange(Empty defaultInstance) {
    return grpcStub.portRange(defaultInstance);
  }

  public Daemon.InterfacesResponse interfaces(Daemon.InterfacesRequest request) {
    return grpcStub.interfaces(request);
  }

  public Daemon.PathsResponse paths(Daemon.PathsRequest request) {
    return grpcStub.paths(request);
  }

  public org.scion.jpan.proto.daemon.DaemonServiceGrpc.DaemonServiceBlockingStub getGrpcStub() {
    return grpcStub;
  }
}
