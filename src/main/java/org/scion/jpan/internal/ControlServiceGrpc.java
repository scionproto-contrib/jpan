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
import java.util.concurrent.TimeUnit;

import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.control_plane.SegmentLookupServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControlServiceGrpc {

  private static final Logger LOG = LoggerFactory.getLogger(ControlServiceGrpc.class.getName());

  private final LocalTopology localAS;
  private ManagedChannel channel;
  private SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub grpcStub;
  private int deadLineMs;

  public static ControlServiceGrpc create(LocalTopology localAS) {
    return new ControlServiceGrpc(localAS);
  }

  private ControlServiceGrpc(LocalTopology localAS) {
    this.localAS = localAS;
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
      if (channel != null) {
        channel = null;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScionRuntimeException(e);
    }
  }

  private void initChannel(String csHost) {
    closeChannel(); // close existing channel
    LOG.info("Bootstrapping with control service: {}", csHost);
    // TODO InsecureChannelCredentials: Implement authentication!
    // We are using OkHttp instead of Netty for Android compatibility
    channel = OkHttpChannelBuilder.forTarget(csHost, InsecureChannelCredentials.create()).build();
    grpcStub = SegmentLookupServiceGrpc.newBlockingStub(channel);
  }

  private SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub getStub() {
      // This is a deadline, not a timeout. It counts from the time the "with..." is called.
      // See also https://github.com/grpc/grpc-java/issues/1495
      // and https://github.com/grpc/grpc-java/issues/4305#issuecomment-378770067
      return grpcStub.withDeadlineAfter(deadLineMs, TimeUnit.MILLISECONDS);
  }

  public synchronized Seg.SegmentsResponse segments(Seg.SegmentsRequest request) {
    if (channel == null) {
      return segmentsTryAll(request);
    }
    try {
      return getStub().segments(request);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode().equals(Status.Code.UNAVAILABLE)) {
        closeChannel();
        return segmentsTryAll(request);
      }
      if (e.getStatus().getCode().equals(Status.Code.UNKNOWN)) {
        String srcIsdAs = ScionUtil.toStringIA(request.getSrcIsdAs());
        String dstIsdAs = ScionUtil.toStringIA(request.getDstIsdAs());
        if (e.getMessage().contains("TRC not found")) {
          String msg = srcIsdAs + " / " + dstIsdAs;
          throw new ScionRuntimeException(
              "Error while getting Segments: unknown src/dst ISD-AS: " + msg, e);
        }
        if (e.getMessage().contains("invalid request")) {
          // AS not found
          LOG.info(
              "Requesting segments: {} -> {} failed (AS unreachable?): {}",
              srcIsdAs,
              dstIsdAs,
              e.getMessage());
          // Return empty result
          return Seg.SegmentsResponse.newBuilder().build();
        }
      }
      throw new ScionRuntimeException("Error while getting Segment info: " + e.getMessage(), e);
    }
  }

  private Seg.SegmentsResponse segmentsTryAll(Seg.SegmentsRequest request) {
    for (LocalTopology.ServiceNode node : localAS.getControlServices()) {
      initChannel(node.ipString);
      try {
        return getStub().segments(request);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.UNAVAILABLE)) {
          LOG.warn("Error connecting to control service: {}", node.ipString);
          closeChannel();
          continue;
        }
        // Rethrow the exception if it's not UNAVAILABLE
        throw new ScionRuntimeException("Error getting segments from control service.", e);
      }
    }
    throw new ScionRuntimeException(
        "Error while connecting to SCION network, no control service available");
  }
}
