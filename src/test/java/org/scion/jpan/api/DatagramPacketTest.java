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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Path;
import org.scion.jpan.ScionDatagramPacket;
import org.scion.jpan.internal.util.IPHelper;

/** Test usage of ScionDatagramPacket. */
class DatagramPacketTest {

  @Test
  void create_fails() {
    Path path = PackageVisibilityHelper.createDummyPath();
    assertThrows(NullPointerException.class, () -> new ScionDatagramPacket(null, 10, path));
    assertThrows(
        IllegalArgumentException.class, () -> new ScionDatagramPacket(new byte[10], 10, null));
    assertThrows(
        IllegalArgumentException.class, () -> new ScionDatagramPacket(new byte[10], 0, 10, null));

    assertThrows(
        IllegalArgumentException.class, () -> new ScionDatagramPacket(new byte[10], 11, path));
    assertThrows(
        IllegalArgumentException.class, () -> new ScionDatagramPacket(new byte[10], -1, path));
    assertThrows(
        IllegalArgumentException.class, () -> new ScionDatagramPacket(new byte[10], 10, 10, path));
    assertThrows(
        IllegalArgumentException.class, () -> new ScionDatagramPacket(new byte[10], 9, 8, path));
    assertThrows(
        IllegalArgumentException.class, () -> new ScionDatagramPacket(new byte[10], -1, 10, path));
  }

  @Test
  void set_fails() {
    Path path = PackageVisibilityHelper.createDummyPath();
    ScionDatagramPacket sdp = new ScionDatagramPacket(new byte[10], 10, path);

    assertThrows(IllegalArgumentException.class, () -> sdp.setPath(null));
    assertThrows(IllegalArgumentException.class, () -> sdp.setLength(11));
    assertThrows(IllegalArgumentException.class, () -> sdp.setLength(-1));
    assertThrows(NullPointerException.class, () -> sdp.setData(null));
    assertThrows(IllegalArgumentException.class, () -> sdp.setData(new byte[3], 0, 4));
    assertThrows(IllegalArgumentException.class, () -> sdp.setData(new byte[3], 0, -1));
    assertThrows(IllegalArgumentException.class, () -> sdp.setData(new byte[3], 4, 2));
    assertThrows(IllegalArgumentException.class, () -> sdp.setData(new byte[3], -1, 2));
    assertThrows(IllegalArgumentException.class, () -> sdp.setData(new byte[3], 3, 2));
  }

  @Test
  void setData() {
    Path path = PackageVisibilityHelper.createDummyPath();
    ScionDatagramPacket sdp = new ScionDatagramPacket(new byte[10], 3, 7, path);
    assertEquals(7, sdp.getLength());
    assertEquals(3, sdp.getOffset());
    assertEquals(10, sdp.getData().length);

    sdp.setData(new byte[42]);
    assertEquals(42, sdp.getLength());
    assertEquals(0, sdp.getOffset());
    assertEquals(42, sdp.getData().length);

    sdp.setData(new byte[45], 5, 17);
    assertEquals(17, sdp.getLength());
    assertEquals(5, sdp.getOffset());
    assertEquals(45, sdp.getData().length);
  }

  @Test
  void setPath() {
    InetSocketAddress addr1 = IPHelper.toInetSocketAddress("192.168.1.1:11111");
    InetSocketAddress addr2 = IPHelper.toInetSocketAddress("192.168.2.2:22222");
    Path path1 = PackageVisibilityHelper.createDummyPath(addr1);
    Path path2 = PackageVisibilityHelper.createDummyPath(addr2);
    ScionDatagramPacket sdp = new ScionDatagramPacket(new byte[10], 3, 7, path1);
    assertEquals(path1, sdp.getPath());
    assertEquals(addr1.getAddress(), sdp.getAddress());
    assertEquals(11111, sdp.getPort());

    sdp.setPath(path2);
    assertEquals(path2, sdp.getPath());
    assertEquals(addr2.getAddress(), sdp.getAddress());
    assertEquals(22222, sdp.getPort());
  }
}
