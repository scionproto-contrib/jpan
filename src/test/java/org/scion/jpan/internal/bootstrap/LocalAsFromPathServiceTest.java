// Copyright 2026 ETH Zurich
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

package org.scion.jpan.internal.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.endhost.Underlays;
import org.scion.jpan.testutil.SimpleHttpServer;

/**
 * Covers the local-AS derivation branches in {@link LocalAsFromPathService#create}: preferring a
 * SNAP entry's ISD-AS over the native UDP underlay's when SNAP is preferred, falling back to the
 * UDP underlay when no usable SNAP entry is advertised, and failing loudly when neither is usable
 * -- as opposed to the pre-fix behavior of silently adopting an unrelated native AS.
 */
class LocalAsFromPathServiceTest {

  private static final long UDP_ISD_AS = ScionUtil.parseIA("64-2:0:9");
  private static final long SNAP_ISD_AS = ScionUtil.parseIA("64-2:0:0");

  private MockEndhostApi mock;

  @AfterEach
  void afterEach() {
    System.clearProperty(Constants.PROPERTY_UNDERLAY_MODE);
    if (mock != null) {
      mock.stop();
    }
  }

  @Test
  void create_snapPreferred_usableSnapEntry_prefersSnapIsdAs() throws IOException {
    // Both a UDP router AND a usable SNAP entry are present; SNAP must win because it is
    // preferred, even though a native AS is also available (this is the exact scenario that
    // previously caused JPAN to silently pick the wrong AS for SNAP-tunneled traffic).
    Underlays.ListUnderlaysResponse response =
        Underlays.ListUnderlaysResponse.newBuilder()
            .setUdp(
                Underlays.UdpUnderlay.newBuilder()
                    .addRouters(
                        Underlays.Router.newBuilder()
                            .setIsdAs(UDP_ISD_AS)
                            .setAddress("10.0.0.1:31000")
                            .build())
                    .build())
            .setSnap(
                Underlays.SnapUnderlay.newBuilder()
                    .addSnaps(
                        Underlays.Snap.newBuilder()
                            .setAddress("https://snap.example.com:5001")
                            .addIsdAses(SNAP_ISD_AS)
                            .build())
                    .build())
            .build();
    mock = MockEndhostApi.start(response);
    System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");

    LocalAS localAS = LocalAsFromPathService.create(mock.getUrl(), TrcStore.createEmpty());

    assertEquals(Collections.singleton(SNAP_ISD_AS), localAS.getIsdAses());
  }

  @Test
  void create_snapPreferred_noUsableSnapEntry_fallsBackToUdpIsdAs() throws IOException {
    // Matches the real-world observation: the "snap" field is present but its list is empty (no
    // usable SNAP entry). With native UDP routers present, JPAN falls back to them rather than
    // failing outright.
    Underlays.ListUnderlaysResponse response =
        Underlays.ListUnderlaysResponse.newBuilder()
            .setUdp(
                Underlays.UdpUnderlay.newBuilder()
                    .addRouters(
                        Underlays.Router.newBuilder()
                            .setIsdAs(UDP_ISD_AS)
                            .setAddress("10.0.0.1:31000")
                            .build())
                    .build())
            .setSnap(Underlays.SnapUnderlay.newBuilder().build())
            .build();
    mock = MockEndhostApi.start(response);
    System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");

    LocalAS localAS = LocalAsFromPathService.create(mock.getUrl(), TrcStore.createEmpty());

    assertEquals(Collections.singleton(UDP_ISD_AS), localAS.getIsdAses());
  }

  @Test
  void create_snapPreferred_noUsableSnapOrUdp_throws() throws IOException {
    // TODO
    // Neither underlay is usable. The old code path here was a stub that threw
    // UnsupportedOperationException unconditionally, regardless of input (`if (true) throw ...`).
    // The new code only throws for this specific case (no usable SNAP entry AND no UDP routers);
    // it must fail with a clear ScionRuntimeException instead of that dead stub.
    Underlays.ListUnderlaysResponse response = Underlays.ListUnderlaysResponse.newBuilder().build();
    mock = MockEndhostApi.start(response);
    System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");

    String url = mock.getUrl();
    TrcStore trcStore = TrcStore.createEmpty();
    assertThrows(ScionRuntimeException.class, () -> LocalAsFromPathService.create(url, trcStore));
  }

  @Test
  void create_snapNotPreferred_ignoresSnapEntry_usesUdpIsdAs() throws IOException {
    // SNAP mode is not enabled: even though a usable SNAP entry is advertised, it must be
    // ignored and the native UDP AS used, exactly as before this change.
    Underlays.ListUnderlaysResponse response =
        Underlays.ListUnderlaysResponse.newBuilder()
            .setUdp(
                Underlays.UdpUnderlay.newBuilder()
                    .addRouters(
                        Underlays.Router.newBuilder()
                            .setIsdAs(UDP_ISD_AS)
                            .setAddress("10.0.0.1:31000")
                            .build())
                    .build())
            .setSnap(
                Underlays.SnapUnderlay.newBuilder()
                    .addSnaps(
                        Underlays.Snap.newBuilder()
                            .setAddress("https://snap.example.com:5001")
                            .addIsdAses(SNAP_ISD_AS)
                            .build())
                    .build())
            .build();
    mock = MockEndhostApi.start(response);
    // PROPERTY_UNDERLAY_MODE intentionally left unset (default is "udp").

    LocalAS localAS = LocalAsFromPathService.create(mock.getUrl(), TrcStore.createEmpty());

    assertEquals(Collections.singleton(UDP_ISD_AS), localAS.getIsdAses());
  }

  @Test
  void create_multipleSnapNodes_onlyFirstIsdAsSetIsUsed_knownLimitation() throws IOException {
    // Known limitation, not yet fixed: when the endhost API advertises more than one usable SNAP
    // node -- e.g. because this host can reach two different tenant ASes through two different
    // SNAP nodes -- JPAN only ever looks at the first one (LocalAsFromPathService uses
    // u.getSnap().getSnaps(0) exclusively). The second node's ISD/AS set is silently dropped from
    // localAS.getIsdAses(), even though both nodes are preserved in localAS.getSnapNodes(). A real
    // fix would need ScionService to resolve and hold a dataplane connection per SnapNode (keyed by
    // reachable ISD/AS), not a single one -- which is a bigger change than where this address is
    // stored (see LocalASTest for the fix to the separate, now-resolved "single global first-hop
    // address" problem).
    long secondSnapIsdAs = ScionUtil.parseIA("64-3:0:0");
    Underlays.ListUnderlaysResponse response =
        Underlays.ListUnderlaysResponse.newBuilder()
            .setSnap(
                Underlays.SnapUnderlay.newBuilder()
                    .addSnaps(
                        Underlays.Snap.newBuilder()
                            .setAddress("https://snap-a.example.com:5001")
                            .addIsdAses(SNAP_ISD_AS)
                            .build())
                    .addSnaps(
                        Underlays.Snap.newBuilder()
                            .setAddress("https://snap-b.example.com:5001")
                            .addIsdAses(secondSnapIsdAs)
                            .build())
                    .build())
            .build();
    mock = MockEndhostApi.start(response);
    System.setProperty(Constants.PROPERTY_UNDERLAY_MODE, "snap");

    LocalAS localAS = LocalAsFromPathService.create(mock.getUrl(), TrcStore.createEmpty());

    // Both SNAP nodes are preserved as raw data...
    assertEquals(2, localAS.getSnapNodes().size());
    // ...but only the first one's ISD/AS actually becomes reachable. The second SNAP node's AS is
    // silently unreachable through this LocalAS/ScionService instance.
    assertEquals(Collections.singleton(SNAP_ISD_AS), localAS.getIsdAses());
    assertFalse(localAS.getIsdAses().contains(secondSnapIsdAs));
  }

  @Test
  void create_multiCandidate_fallsThroughToSecondOnFirstFailure() throws IOException {
    // A ";"-joined candidate list (as produced by --discovery) must try the next candidate when
    // the first is unreachable, rather than failing outright.
    Underlays.ListUnderlaysResponse response =
        Underlays.ListUnderlaysResponse.newBuilder()
            .setUdp(
                Underlays.UdpUnderlay.newBuilder()
                    .addRouters(
                        Underlays.Router.newBuilder()
                            .setIsdAs(UDP_ISD_AS)
                            .setAddress("10.0.0.1:31000")
                            .build())
                    .build())
            .build();
    mock = MockEndhostApi.start(response);

    // "127.0.0.1:1" is unassigned/unroutable and should fail fast; the second candidate is the
    // real mock server.
    String candidates = "http://127.0.0.1:1;" + mock.getUrl();

    LocalAS localAS = LocalAsFromPathService.create(candidates, TrcStore.createEmpty());

    assertEquals(Collections.singleton(UDP_ISD_AS), localAS.getIsdAses());
  }

  /** Minimal mock for the {@code UnderlayService/ListUnderlays} RPC. */
  private static class MockEndhostApi extends SimpleHttpServer {
    private final Underlays.ListUnderlaysResponse response;

    private MockEndhostApi(Underlays.ListUnderlaysResponse response) {
      super(0);
      this.response = response;
    }

    static MockEndhostApi start(Underlays.ListUnderlaysResponse response) throws IOException {
      MockEndhostApi server = new MockEndhostApi(response);
      server.start();
      return server;
    }

    String getUrl() {
      return "http://127.0.0.1:" + getListeningPort();
    }

    @Override
    public Response serve(Session session) {
      byte[] body = response.toByteArray();
      return newFixedLengthResponse(
          Response.Status.OK, "application/proto", new ByteArrayInputStream(body), body.length);
    }
  }
}
