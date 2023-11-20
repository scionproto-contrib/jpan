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

  public static void install(String isdAs, String hostName, String address) {
    String entry = System.getProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, "");
    if (!entry.isEmpty()) {
      entry += ";";
    }
    entry += hostName + "=";
    entry += "\"scion=" + isdAs + "," + address + "\"";
    System.setProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK, entry);
  }

  public static void clear() {
    System.clearProperty(PackageVisibilityHelper.DEBUG_PROPERTY_DNS_MOCK);
  }
}
