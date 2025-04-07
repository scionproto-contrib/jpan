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
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.proto.daemon.Daemon;
import org.scion.jpan.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DaemonService {

  private static final Logger LOG = LoggerFactory.getLogger(DaemonService.class.getName());

  private final String addressOrHost;
  private ManagedChannel channel;
  private DaemonServiceGrpc.DaemonServiceBlockingStub daemonStub;

  public static DaemonService create(String addressOrHost) {
    return new DaemonService(addressOrHost);
  }

  private DaemonService(String addressOrHost) {
    this.addressOrHost = addressOrHost; // TODO remove?
    initChannel(addressOrHost);
  }

  public void close() throws IOException {
    closeChannel();
  }

  private void closeChannel() throws IOException {
    try {
      if (channel != null) {
        if (!channel.shutdown().awaitTermination(1, TimeUnit.SECONDS)
            && !channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS)) {
          LOG.error("Failed to shut down ScionService gRPC ManagedChannel");
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  private void initChannel(String addressOrHost) {
    try {
      closeChannel(); // close existing channel
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
    LOG.info("Bootstrapping with daemon: {}", addressOrHost);
    // TODO InsecureChannelCredentials: Implement authentication!
    // We are using OkHttp instead of Netty for Android compatibility
    channel =
        OkHttpChannelBuilder.forTarget(addressOrHost, InsecureChannelCredentials.create()).build();
    daemonStub = DaemonServiceGrpc.newBlockingStub(channel);
  }

  public Daemon.ASResponse aS(Daemon.ASRequest request) {
    return daemonStub.aS(request);
  }

  public Daemon.PortRangeResponse portRange(Empty defaultInstance) {
    return daemonStub.portRange(defaultInstance);
  }

  public Daemon.InterfacesResponse interfaces(Daemon.InterfacesRequest request) {
    return daemonStub.interfaces(request);
  }

  public Daemon.PathsResponse paths(Daemon.PathsRequest request) {
    return daemonStub.paths(request);
  }

  public DaemonServiceGrpc.DaemonServiceBlockingStub getDaemonConnection() {
    // TODO Remove GRPC from ScionService
    return daemonStub;
  }
}
