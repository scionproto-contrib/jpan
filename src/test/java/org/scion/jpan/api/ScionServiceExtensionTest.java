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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionSocketAddress;
import org.scion.jpan.internal.bootstrap.LocalAS;
import org.scion.jpan.internal.paths.ControlServiceGrpc;
import org.scion.jpan.internal.paths.DaemonServiceGrpc;
import org.scion.jpan.internal.paths.PathServiceRpc;
import org.scion.jpan.internal.snap.SnapDataplaneDetails;

public class ScionServiceExtensionTest {

  @Test
  void test() {
    // Small test to confirm that we can create subclasses of ScionService. This is, for example,
    // Useful to create Mock-implementations for testing.
    MyLocalAs localAs = new MyLocalAs(Collections.singletonList(42L), true);
    MyScionService mss = new MyScionService(localAs, null, null, null, null);

    assertEquals(42, (long) mss.getLocalIsdAsList().get(0));
    assertTrue(mss.checkLocalIsdAs);

    assertEquals(0, mss.getPaths((ScionSocketAddress) null).size());
    assertTrue(mss.checkPathFromSSA);
  }

  private static class MyLocalAs extends LocalAS {

    protected MyLocalAs(List<Long> localIsdAs, boolean isCoreAs) {
      super(
          localIsdAs,
          isCoreAs,
          1024,
          DispatcherPortRange.createAll(),
          Collections.emptyList(),
          Collections.emptyList(),
          Collections.emptyList(),
          Collections.emptyList(),
          null,
          null);
    }
  }

  private static class MyScionService extends ScionService {

    boolean checkLocalIsdAs = false;
    boolean checkPathFromSSA = false;

    public MyScionService(
        LocalAS localAS,
        ControlServiceGrpc controlService,
        PathServiceRpc pathService,
        DaemonServiceGrpc daemonService,
        SnapDataplaneDetails snapDataplaneDetails) {
      super(localAS, controlService, pathService, daemonService, snapDataplaneDetails);
    }

    @Override
    public List<Long> getLocalIsdAsList() {
      checkLocalIsdAs = true;
      return super.getLocalIsdAsList();
    }

    @Override
    public List<Path> getPaths(ScionSocketAddress ssa) {
      checkPathFromSSA = true;
      return Collections.emptyList();
    }
  }
}
