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
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.control_plane.SegmentLookupServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControlServiceGrpc {

  private static final Logger LOG = LoggerFactory.getLogger(ControlServiceGrpc.class.getName());

  private final LocalTopology localAS;
  private ManagedChannel channel;
  private SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub grpcStub;

  public static ControlServiceGrpc create(LocalTopology localAS) {
    return new ControlServiceGrpc(localAS);
  }

  private ControlServiceGrpc(LocalTopology localAS) {
    this.localAS = localAS;
  }

  public void close() throws IOException {
    closeChannel();
  }

  private void closeChannel() throws IOException {
    try {
      if (channel != null) {
        // Thread.sleep(200); // TODO??
        System.err.println("Shutting down.... " + channel);
      }
      //      if (channel != null
      //          && !channel.shutdown().awaitTermination(1, TimeUnit.SECONDS)
      //          && !channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS)) {
      //        // TODO remove exception
      //        LOG.error("Failed to shut down ScionService gRPC ManagedChannel", new
      // RuntimeException());
      //      }
      if (channel != null) {
        System.err.println("Close ------------0.... " + Instant.now() + "  " + channel);
        if (!channel.shutdown().awaitTermination(1, TimeUnit.SECONDS)) {
          System.err.println("Close ------------1.... " + Instant.now() + "  " + channel);
          if (!channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS)) {
            System.err.println("Close ------------2.... " + Instant.now() + "  " + channel);
            // TODO remove exception
            LOG.error(
                "Failed to shut down ScionService gRPC ManagedChannel", new RuntimeException());
          }
        }
      }
      if (channel != null) {
        System.err.println("              .... Done");
        // Thread.sleep(200); // TODO??
        channel = null;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    } finally {
      System.err.println("Close ------------ 3.... " + Instant.now() + "  " + channel);
    }
  }

  private void initChannel(String csHost) {
    System.err.println("init - 1 " + channel);
    try {
      closeChannel(); // close existing channel
    } catch (IOException e) {
      throw new ScionRuntimeException(e);
    }
    LOG.info("Bootstrapping with control service: {}", csHost);
    // TODO InsecureChannelCredentials: Implement authentication!
    // We are using OkHttp instead of Netty for Android compatibility
    System.err.println("init - 2 " + channel);
    // channel = OkHttpChannelBuilder.forTarget(csHost,
    // InsecureChannelCredentials.create()).build();
    channel = Grpc.newChannelBuilder(csHost, InsecureChannelCredentials.create()).build();
    System.err.println("init - 3 " + channel);
    grpcStub = SegmentLookupServiceGrpc.newBlockingStub(channel);
    System.err.println("init - 4 " + channel);
  }

  public synchronized Seg.SegmentsResponse segments(Seg.SegmentsRequest request) {
    if (channel == null) {
      return segmentsTryAll(request);
    }
    try {
      System.err.println("segments - 1 " + channel);
      return grpcStub.segments(request);
    } catch (StatusRuntimeException e) {
      System.err.println("segments - E " + e.getMessage());
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
    } finally {
      System.err.println("segments - 2 " + channel);
    }
  }

  private Seg.SegmentsResponse segmentsTryAll(Seg.SegmentsRequest request) {
    for (LocalTopology.ServiceNode node : localAS.getControlServices()) {
      initChannel(node.ipString);
      try {
        System.err.println("segmentsTryAll - 1 " + channel);
        return grpcStub.segments(request);
      } catch (StatusRuntimeException e) {
        System.err.println("segmentsTryAll - E " + e.getMessage());
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
      } finally {
        System.err.println("segmentsTryAll - 2 " + channel);
      }
    }
    throw new ScionRuntimeException(
        "Error while connecting to SCION network, no control service available");
  }

  //  public SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub getGrpcStub() {
  //    return grpcStub;
  //  }
}
