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

package org.scion.jpan.testutil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionService;

class PingPongHelperTest {

  @AfterAll
  public static void afterAll() {
    // Defensive clean up
    ScionService.closeDefault();
  }

  @Test
  void testChannel() {
    PingPongChannelHelper.Server serverFn = PingPongChannelHelper::defaultServer;
    PingPongChannelHelper.Client clientFn = PingPongChannelHelper::defaultClient;
    PingPongChannelHelper pph = PingPongChannelHelper.newBuilder(1, 1, 10).build();
    pph.runPingPong(serverFn, clientFn);
  }

  @Test
  void testSocket() {
    PingPongSocketHelper.Server serverFn = PingPongSocketHelper::defaultServer;
    PingPongSocketHelper.Client clientFn = PingPongSocketHelper::defaultClient;
    PingPongSocketHelper pph = PingPongSocketHelper.newBuilder(1, 1, 10).build();
    pph.runPingPong(serverFn, clientFn);
  }
}
