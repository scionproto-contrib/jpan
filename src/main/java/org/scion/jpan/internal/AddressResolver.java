// Copyright 2024 ETH Zurich
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

package org.scion.jpan.internal;

import java.net.InetAddress;

import org.scion.internal.SimpleCache;
import org.scion.jpan.ScionAddress;
import org.scion.jpan.ScionException;
import org.scion.jpan.ScionService;

public class AddressResolver {

  private final ScionService service;
  private final SimpleCache<InetAddress, ScionAddress> cache = new SimpleCache<>(10);

  public AddressResolver(ScionService service) {
    this.service = service;
  }

  public ScionAddress resolve(InetAddress address) throws ScionException {
    ScionAddress resolved = cache.get(address);
    if (resolved != null) {
      return resolved;
    }

    // Routes to a valid IP
    // 1) HostName -> dig TXT -> extract "scion="
    // 2) IP -> dig -x IP -> extract authority hostname (?) -> 1)
    // 3) SCION-IP -> dig -x returns some-host -> dig TXT some-host fails
    //    -> dig some-host -> extract authority -> 1)

    ScionAddress sa = service.getScionAddress(address.getHostName());
    if (sa != null) {
      cache.put(address, sa);
    }
    return sa;
  }
}
