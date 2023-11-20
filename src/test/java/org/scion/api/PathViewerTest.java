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

package org.scion.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.testutil.MockDaemon;

public class PathViewerTest {

  private static final String SCION_HOST = "as110.test";
  private static final String SCION_TXT = "\"scion=1-ff00:0:110,127.0.0.1\"";
  private static final int DEFAULT_PORT = MockDaemon.DEFAULT_PORT;

  @BeforeAll
  public static void beforeAll() {
    System.setProperty(
        PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, SCION_HOST + "=" + SCION_TXT);
  }

  @AfterAll
  public static void afterAll() {
    System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
  }

  @BeforeEach
  public void beforeEach() {
    // reset counter
    MockDaemon.getAndResetCallCount();
  }

  @Test
  void getPath() throws IOException {
    MockDaemon daemon = MockDaemon.create().start();

    long dstIA = ScionUtil.parseIA("1-ff00:0:112");
    ScionPath path = ScionService.defaultService().getPath(dstIA);
    PathViewer viewer = PathViewer.create(path);

    assertEquals("127.0.0.10:31004", viewer.getInterface().getAddress());
    assertEquals(2, viewer.getInterfacesList().size());
    //assertEquals(1, viewer.getInternalHopsList().size());
    //assertEquals(0, viewer.getMtu());
    //assertEquals(0, viewer.getLinkTypeList().size());
    assertEquals(36, viewer.getRaw().length);

    // 2 calls: 1 path & 1 local AS
    assertEquals(2, MockDaemon.getAndResetCallCount());
    daemon.close();
  }
}
