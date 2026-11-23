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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.*;
import org.scion.jpan.internal.bootstrap.LocalAS;
import org.scion.jpan.internal.bootstrap.TrcStore;
import org.scion.jpan.internal.util.IPHelper;
import org.scion.jpan.testutil.ExamplePacket;

class PathTest {

  @Test
  void copy() throws UnknownHostException {
    RequestPath p = PackageVisibilityHelper.createDummyPath();
    InetAddress ip = IPHelper.toInetAddress("127.0.0.25");
    RequestPath p2 = (RequestPath) p.copy(ip, 555);
    assertEquals(p.getRawPath(), p2.getRawPath());
    assertEquals(p.getFirstHopAddress(), p2.getFirstHopAddress());
    assertEquals(p.getRemoteIsdAs(), p2.getRemoteIsdAs());
    assertNotEquals(p.getRemoteSocketAddress(), p2.getRemoteSocketAddress());
    assertEquals(ip, p2.getRemoteAddress());
    assertEquals(555, p2.getRemotePort());
  }

  @Test
  void requestPathConstructor() {
    byte[] raw1 = {1, 2, 3};
    byte[] raw2 = {1, 2, 3, 4};
    PathMetadata meta =
        PathMetadata.newBuilder().setSrcIsdAs(123).setDstIsdAs(1234).setRaw(raw1).build();
    TestRequestPath path = new TestRequestPath(meta, raw2, null, 123, 1234, null, 1);
    assertArrayEquals(raw1, path.getMetadata().getRawPath());
    assertArrayEquals(raw2, path.getRawPath());

    Path path2 = path.copy(path.getRemoteAddress(), path.getRemotePort());
    assertArrayEquals(raw1, path2.getMetadata().getRawPath());
    assertArrayEquals(raw2, path2.getRawPath());
  }

  private static class TestRequestPath extends RequestPath {

    protected TestRequestPath(
        PathMetadata metadata,
        byte[] rawPath,
        InetSocketAddress firstHop,
        long srcIsdAs,
        long dstIsdAs,
        InetAddress dstIP,
        int dstPort) {
      super(metadata, rawPath, firstHop, srcIsdAs, dstIsdAs, dstIP, dstPort);
    }
  }

  @Test
  void request_equals_hashcode() throws UnknownHostException {
    RequestPath p1 = createRequestPath();

    // equality
    RequestPath p1b = createRequestPath();
    assertEquals(p1, p1b);
    assertEquals(p1.hashCode(), p1b.hashCode());

    // equality with copy
    RequestPath p1c = (RequestPath) p1.copy(p1.getRemoteAddress(), p1.getRemotePort());
    assertEquals(p1, p1c);
    assertEquals(p1.hashCode(), p1c.hashCode());

    // address diff
    InetAddress ip = IPHelper.toInetAddress("127.0.0.25");
    RequestPath p2Ip = (RequestPath) p1.copy(ip, p1.getRemotePort());
    assertNotEquals(p1, p2Ip);
    assertNotEquals(p1.hashCode(), p2Ip.hashCode());

    // port diff
    RequestPath p2Port = (RequestPath) p1.copy(p1.getRemoteAddress(), 555);
    assertNotEquals(p1, p2Port);
    assertNotEquals(p1.hashCode(), p2Port.hashCode());

    // path diff
    RequestPath p2Raw = createRequestPath();
    p2Raw.getRawPath()[5] = (byte) 0xff;
    assertNotEquals(p1, p2Raw);
    assertNotEquals(p1.hashCode(), p2Raw.hashCode());
  }

  private RequestPath createRequestPath() {
    InetSocketAddress dst = IPHelper.toInetSocketAddress("127.0.0.55:12321");
    PathMetadata meta =
        PathMetadata.newBuilder()
            .setSrcIsdAs(12345)
            .setDstIsdAs(54321)
            .setRaw(new byte[] {1, 2, 3, 4, 5, 6})
            .build();
    return PackageVisibilityHelper.createRequestPath(meta, dst);
  }

  @Test
  void response_equals_hashcode() throws UnknownHostException {
    ResponsePath p1 = createResponsePath();

    // equality
    ResponsePath p1b = createResponsePath();
    assertEquals(p1, p1b);
    assertEquals(p1.hashCode(), p1b.hashCode());

    // equality with copy
    ResponsePath p1c = (ResponsePath) p1.copy(p1.getRemoteAddress(), p1.getRemotePort());
    assertEquals(p1, p1c);
    assertEquals(p1.hashCode(), p1c.hashCode());

    // address diff
    InetAddress ip = IPHelper.toInetAddress("127.0.0.25");
    ResponsePath p2Ip = (ResponsePath) p1.copy(ip, p1.getRemotePort());
    assertNotEquals(p1, p2Ip);
    assertNotEquals(p1.hashCode(), p2Ip.hashCode());

    // port diff
    ResponsePath p2Port = (ResponsePath) p1.copy(p1.getRemoteAddress(), 555);
    assertNotEquals(p1, p2Port);
    assertNotEquals(p1.hashCode(), p2Port.hashCode());

    // path diff
    ResponsePath p2Raw = createResponsePath();
    p2Raw.getRawPath()[5] = (byte) 0xff;
    assertNotEquals(p1, p2Raw);
    assertNotEquals(p1.hashCode(), p2Raw.hashCode());
  }

  private ResponsePath createResponsePath() {
    return PackageVisibilityHelper.createDummyResponsePath(
        new byte[] {1, 2, 3, 4, 5, 6},
        12345,
        new byte[] {127, 2, 3, 4},
        11111,
        54321,
        new byte[] {127, 0, 0, 99},
        22222,
        IPHelper.toInetSocketAddress("127.0.0.55:12345"));
  }

  @Test
  void create_failOnInvalidBrId() throws UnknownHostException {
    // Ensure that we get an error when we create a path where raw path and metadata don't match
    int interfaceId = 123456;
    PathMetadata meta =
        PathMetadata.newBuilder()
            .setSrcIsdAs(123)
            .setDstIsdAs(456)
            .setRaw(ExamplePacket.PATH_RAW_TINY_110_112)
            .addInterfaces(PathMetadata.PathInterface.create(789, interfaceId))
            .build();
    LocalAS localAS =
        new MyLocalAs(
            Collections.emptyList(),
            false,
            0,
            LocalAS.DispatcherPortRange.createAll(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            null,
            null);
    InetAddress dstIP = IPHelper.toInetAddress("191.168.123.123");
    ScionRuntimeException e =
        assertThrows(
            ScionRuntimeException.class,
            () -> PackageVisibilityHelper.createRequestPath(meta, dstIP, 12345, localAS, false));
    assertTrue(e.getMessage().contains(Integer.toString(interfaceId)));
  }

  private static class MyLocalAs extends LocalAS {
    protected MyLocalAs(
        List<Long> localIsdAs,
        boolean isCoreAs,
        int localMtu,
        DispatcherPortRange portRange,
        List<ServiceNode> controlServices,
        List<ServiceNode> discoveryServices,
        List<BorderRouter> borderRouters,
        List<SnapControlNode> snapControlNodes,
        String snapAuthToken,
        TrcStore trcStore) {
      super(
          localIsdAs,
          isCoreAs,
          localMtu,
          portRange,
          controlServices,
          discoveryServices,
          borderRouters,
          snapControlNodes,
          snapAuthToken,
          trcStore);
    }
  }
}
