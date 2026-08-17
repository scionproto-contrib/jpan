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

package org.scion.jpan.demo;

import org.scion.jpan.testutil.MockNetwork;

/** Helper class for setting up a demo topology. */
public class DemoTopology {

  static void configureMockV6() {
    MockNetwork.startTiny(false);
  }

  static void configureMockV4() {
    MockNetwork.startTiny(true);
  }

  public static void shutDown() {
    MockNetwork.stopTiny();
  }
}
