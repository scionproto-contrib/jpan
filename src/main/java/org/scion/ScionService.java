// Copyright 2023 ETH Zurich
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

package org.scion;

import static org.scion.Constants.DEFAULT_DAEMON_HOST;
import static org.scion.Constants.DEFAULT_DAEMON_PORT;
import static org.scion.Constants.ENV_BOOTSTRAP_HOST;
import static org.scion.Constants.ENV_BOOTSTRAP_NAPTR_NAME;
import static org.scion.Constants.ENV_BOOTSTRAP_TOPO_FILE;
import static org.scion.Constants.ENV_DAEMON_HOST;
import static org.scion.Constants.ENV_DAEMON_PORT;
import static org.scion.Constants.PROPERTY_BOOTSTRAP_HOST;
import static org.scion.Constants.PROPERTY_BOOTSTRAP_NAPTR_NAME;
import static org.scion.Constants.PROPERTY_BOOTSTRAP_TOPO_FILE;
import static org.scion.Constants.PROPERTY_DAEMON_HOST;
import static org.scion.Constants.PROPERTY_DAEMON_PORT;

import io.grpc.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.scion.internal.DNSHelper;
import org.scion.internal.ScionBootstrapper;
import org.scion.internal.Segments;
import org.scion.proto.control_plane.SegmentLookupServiceGrpc;
import org.scion.proto.daemon.Daemon;
import org.scion.proto.daemon.DaemonServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ScionService provides information such as: <br>
 * - Paths from A to B - The local ISD/AS numbers <br>
 * - Lookup op ISD/AS for host names via DNS. <br>
 *
 * <p>The ScionService is intended as singleton. There should usually be only one instance that is
 * shared by all users. However, it may sometimes be desirable to have multiple instances, e.g. for
 * connecting to a different daemon or for better concurrency.
 *
 * <p>The default instance is of type ScionService. All other ScionService are of type {@code
 * Scion.CloseableService} which extends {@code AutoCloseable}.
 *
 * @see Scion.CloseableService
 */
public class ScionService {

  private static final Logger LOG = LoggerFactory.getLogger(ScionService.class.getName());

  private static final String DNS_TXT_KEY = "scion";
  private static final Object LOCK = new Object();
  private static final String ERR_INVALID_TXT = "Invalid TXT entry: ";
  private static ScionService defaultService = null;

  private final ScionBootstrapper bootstrapper;
  private final DaemonServiceGrpc.DaemonServiceBlockingStub daemonStub;
  private final SegmentLookupServiceGrpc.SegmentLookupServiceBlockingStub segmentStub;

  private final ManagedChannel channel;
  private static final long ISD_AS_NOT_SET = -1;
  private final AtomicLong localIsdAs = new AtomicLong(ISD_AS_NOT_SET);
  private Thread shutdownHook;

  protected enum Mode {
    DAEMON,
    BOOTSTRAP_SERVER_IP,
    BOOTSTRAP_VIA_DNS,
    BOOTSTRAP_TOPO_FILE
  }

  protected ScionService(String addressOrHost, Mode mode) {
    if (mode == Mode.DAEMON) {
      channel = Grpc.newChannelBuilder(addressOrHost, InsecureChannelCredentials.create()).build();
      daemonStub = DaemonServiceGrpc.newBlockingStub(channel);
      segmentStub = null;
      bootstrapper = null;
      LOG.info("Path service started with daemon {} {}", channel, addressOrHost);
    } else {
      if (mode == Mode.BOOTSTRAP_VIA_DNS) {
        bootstrapper = ScionBootstrapper.createViaDns(addressOrHost);
      } else if (mode == Mode.BOOTSTRAP_SERVER_IP) {
        bootstrapper = ScionBootstrapper.createViaBootstrapServerIP(addressOrHost);
      } else if (mode == Mode.BOOTSTRAP_TOPO_FILE) {
        java.nio.file.Path file = Paths.get(addressOrHost);
        bootstrapper = ScionBootstrapper.createViaTopoFile(file);
      } else {
        throw new UnsupportedOperationException();
      }
      String csHost = bootstrapper.getControlServerAddress();
      localIsdAs.set(bootstrapper.getLocalIsdAs());
      // TODO InsecureChannelCredentials: Implement authentication!
      channel = Grpc.newChannelBuilder(csHost, InsecureChannelCredentials.create()).build();
      daemonStub = null;
      segmentStub = SegmentLookupServiceGrpc.newBlockingStub(channel);
      LOG.info("Path service started with control service {} {}", channel, csHost);
    }
    shutdownHook = addShutdownHook();
    try {
      getLocalIsdAs(); // Init
    } catch (RuntimeException e) {
      // If this fails for whatever reason we want to make sure that the channel is closed.
      try {
        close();
      } catch (IOException ex) {
        // Ignore, we just want to get out.
      }
      throw e;
    }
  }

  /**
   * Returns the default instance of the ScionService. The default instance is connected to the
   * daemon that is specified by the default properties or environment variables.
   *
   * @return default instance
   */
  static ScionService defaultService() {
    synchronized (LOCK) {
      // This is not 100% thread safe, but the worst that can happen is that
      // we call close() on a Service that has already been closed.
      if (defaultService != null) {
        return defaultService;
      }
      // try bootstrap service IP
      String fileName =
          ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_TOPO_FILE, ENV_BOOTSTRAP_TOPO_FILE);
      if (fileName != null) {
        defaultService = new ScionService(fileName, Mode.BOOTSTRAP_TOPO_FILE);
        return defaultService;
      }

      String server = ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_HOST, ENV_BOOTSTRAP_HOST);
      if (server != null) {
        defaultService = new ScionService(server, Mode.BOOTSTRAP_SERVER_IP);
        return defaultService;
      }

      String naptrName =
          ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_NAPTR_NAME, ENV_BOOTSTRAP_NAPTR_NAME);
      if (naptrName != null) {
        defaultService = new ScionService(naptrName, Mode.BOOTSTRAP_VIA_DNS);
        return defaultService;
      }

      // try daemon
      String daemonHost =
          ScionUtil.getPropertyOrEnv(PROPERTY_DAEMON_HOST, ENV_DAEMON_HOST, DEFAULT_DAEMON_HOST);
      String daemonPort =
          ScionUtil.getPropertyOrEnv(PROPERTY_DAEMON_PORT, ENV_DAEMON_PORT, DEFAULT_DAEMON_PORT);
      try {
        defaultService = new ScionService(daemonHost + ":" + daemonPort, Mode.DAEMON);
        return defaultService;
      } catch (ScionRuntimeException e) {
        throw new ScionRuntimeException(
            "Could not connect to daemon, DNS or bootstrap resource.", e);
      }
    }
  }

  public static void closeDefault() {
    synchronized (LOCK) {
      if (defaultService != null) {
        try {
          defaultService.close();
        } catch (IOException e) {
          throw new ScionRuntimeException(e);
        } finally {
          defaultService = null;
        }
      }
    }
  }

  private Thread addShutdownHook() {
    Thread hook =
        new Thread(
            () -> {
              try {
                if (defaultService != null) {
                  defaultService.shutdownHook = null;
                  defaultService.close();
                }
              } catch (IOException e) {
                // Ignore, we just want to get out.
              }
            });
    Runtime.getRuntime().addShutdownHook(hook);
    return hook;
  }

  public void close() throws IOException {
    try {
      if (!channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)) {
        if (!channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)) {
          LOG.error("Failed to shut down ScionService gRPC ManagedChannel");
        }
      }
      if (shutdownHook != null) {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  // NOTE for 0.1.0 reviewers: THis method is used internally in ScionService, see review part 1.
  // Protobuf is not exposed in the public API.
  private List<Daemon.Path> getPathList(long srcIsdAs, long dstIsdAs) {
    if (daemonStub != null) {
      return getPathListDaemon(srcIsdAs, dstIsdAs);
    }
    return getPathListCS(srcIsdAs, dstIsdAs);
  }

  // do not expose proto types on API
  List<Daemon.Path> getPathListDaemon(long srcIsdAs, long dstIsdAs) {
    Daemon.PathsRequest request =
        Daemon.PathsRequest.newBuilder()
            .setSourceIsdAs(srcIsdAs)
            .setDestinationIsdAs(dstIsdAs)
            .build();

    Daemon.PathsResponse response;
    try {
      response = daemonStub.paths(request);
    } catch (StatusRuntimeException e) {
      throw new ScionRuntimeException(e);
    }

    return response.getPathsList();
  }

  // Do not expose protobuf types on API!
  List<Daemon.Path> getPathListCS(long srcIsdAs, long dstIsdAs) {
    return Segments.getPaths(segmentStub, bootstrapper, srcIsdAs, dstIsdAs);
  }
}
