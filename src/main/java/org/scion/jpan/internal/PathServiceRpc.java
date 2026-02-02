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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.bootstrap.LocalAS;
import org.scion.jpan.internal.util.Config;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.proto.control_plane.SegmentLookupServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PathServiceRpc {

  private static final Logger LOG = LoggerFactory.getLogger(PathServiceRpc.class.getName());

  private final List<ControlService> services = new ArrayList<>();
  private final int deadLineMs;

  public static PathServiceRpc create(LocalAS localAS) {
    return new PathServiceRpc(localAS);
  }

  private PathServiceRpc(LocalAS localAS) {
    this.deadLineMs = Config.getControlPlaneTimeoutMs();
    for (LocalAS.ServiceNode node : localAS.getControlServices()) {
      services.add(new ControlService(node.getIpString()));
    }
  }

  public void close() {
    services.forEach(ControlService::close);
  }

  public synchronized Seg.SegmentsResponse segments(Seg.SegmentsRequest request) {
    String error = "No control services found in topology";
    for (int i = 0; i < services.size(); i++) {
      ControlService cs = services.get(0); // Always get the first one!
      cs.init();
      try {
        return cs.getStub().segments(request);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode().equals(Status.Code.UNKNOWN)) {
          String srcIsdAs = ScionUtil.toStringIA(request.getSrcIsdAs());
          String dstIsdAs = ScionUtil.toStringIA(request.getDstIsdAs());
          String msg = "Error while requesting segments: " + srcIsdAs + " -> " + dstIsdAs;
          if (e.getMessage().contains("TRC not found")) {
            msg += " -> TRC not found: " + e.getMessage();
            LOG.error(msg);
            throw new ScionRuntimeException(msg, e);
          }
          if (e.getMessage().contains("invalid request")) {
            msg += " -> failed (AS unreachable?): " + e.getMessage();
            LOG.info(msg);
            throw new ScionRuntimeException(msg, e);
          }
        }
        error = e.getStatus().getCode().toString();
        LOG.warn("Error connecting control service {}: {}", cs.ipString, e.getStatus().getCode());
        cs.close();
        // Move CS to end of list
        services.add(services.remove(0));
      }
    }
    throw new ScionRuntimeException(
        "Error while connecting to SCION network, no control service available: " + error);
  }

  private class ControlService {
    private final String ipString;
    private ManagedChannel channel;
    private SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub grpcStub;

    public ControlService(String ipString) {
      this.ipString = ipString;
    }

    void init() {
      if (channel != null) {
        return;
      }
      LOG.info("Bootstrapping with control service: {}", ipString);
      // TODO InsecureChannelCredentials: Implement authentication!
      // We are using OkHttp instead of Netty for Android compatibility
      channel =
          OkHttpChannelBuilder.forTarget(ipString, InsecureChannelCredentials.create()).build();
      grpcStub = SegmentLookupServiceGrpc.newBlockingStub(channel);
    }

    SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub getStub() {
      // This is a deadline, not a timeout. It counts from the time the "with..." is called.
      // See also https://github.com/grpc/grpc-java/issues/1495
      // and https://github.com/grpc/grpc-java/issues/4305#issuecomment-378770067
      return grpcStub.withDeadlineAfter(deadLineMs, TimeUnit.MILLISECONDS);
    }

    void close() {
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
  }
}
