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

package org.scion.testutil;

import org.scion.PackageVisibilityHelper;

public class MockDNS {

  private static final String HOST_112 = "server.as112.test";
  private static final String HOST_112_TXT = "\"scion=1-ff00:0:112,127.0.0.1\"";

  public static void install(String ip) {
    String entry112 = HOST_112 + ";" + HOST_112_TXT;
    System.setProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, entry112);
  }

  public static void clear() {
    System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
  }
}
