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

package protobuf;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.scion.proto.daemon.Daemon;
import org.scion.proto.drkey.Drkey;

public class SmokeTest {

  @Test
  public void smoketest() {
    Drkey.Protocol protocol = Drkey.Protocol.forNumber(1);
    assertNotNull(protocol);
    assertEquals(1, protocol.getNumber());

    Daemon.Service daemonService = Daemon.Service.newBuilder().build();
    assertNotNull(daemonService.getUri());
  }
}
