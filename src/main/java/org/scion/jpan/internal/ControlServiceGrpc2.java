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

import io.grpc.*;
import io.grpc.okhttp.OkHttpChannelBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.control_plane.SegmentLookupServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControlServiceGrpc2 {

  private static final Logger LOG = LoggerFactory.getLogger(ControlServiceGrpc2.class.getName());

  private final LocalTopology localAS;
  private ManagedChannel channel;
  private SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub grpcStub;

  public static ControlServiceGrpc2 create(LocalTopology localAS) {
    return new ControlServiceGrpc2(localAS);
  }

  private ControlServiceGrpc2(LocalTopology localAS) {
    this.localAS = localAS;
  }

  public void close() throws IOException {
    closeChannel();
  }

  private void closeChannel() throws IOException {
    try {
      if (channel != null
          && !channel.shutdown().awaitTermination(1, TimeUnit.SECONDS)
          && !channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS)) {
        // TODO remove exception
        LOG.error("Failed to shut down ScionService gRPC ManagedChannel", new RuntimeException());
        channel = null;
        grpcStub = null;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  private void initChannel(String csHost) {
    try {
      closeChannel(); // close existing channel
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
    LOG.info("Bootstrapping with control service: {}", csHost);
    // TODO InsecureChannelCredentials: Implement authentication!
    // We are using OkHttp instead of Netty for Android compatibility
    channel = OkHttpChannelBuilder.forTarget(csHost, InsecureChannelCredentials.create()).build();
    grpcStub = SegmentLookupServiceGrpc.newBlockingStub(channel);
  }

  public synchronized Seg.SegmentsResponse segments(Seg.SegmentsRequest request) {
    if (channel == null) {
      return segmentsTryAll(request);
    }
    try {
      return grpcStub.segments(request);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.UNAVAILABLE)) {
        // TODO
        LOG.error(
            "UNAVAILABLE: {} {} {}",
            channel.isShutdown(),
            channel.isTerminated(),
            channel.getState(false));
        return segmentsTryAll(request);
      }
      throw new ScionRuntimeException("Error while getting Segment info: " + e.getMessage(), e);
    }
  }

  private Seg.SegmentsResponse segmentsTryAll(Seg.SegmentsRequest request) {
    for (LocalTopology.ServiceNode node : localAS.getControlServices()) {
      initChannel(node.ipString);
      try {
        return grpcStub.segments(request);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.UNAVAILABLE)) {
          // TODO
          LOG.error(
              "UNAVAILABLE 2: {} {} {}",
              channel.isShutdown(),
              channel.isTerminated(),
              channel.getState(false));
          LOG.warn("Error connecting to control service: {}", node.ipString);
          continue;
        }
        // Rethrow the exception if it's not UNAVAILABLE
        throw e;
      }
    }
    throw new ScionRuntimeException(
        "Error while connecting to SCION network, not control service available");
  }

  public SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub getGrpcStub() {
    return grpcStub;
  }
}
