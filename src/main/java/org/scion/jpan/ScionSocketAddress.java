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

package org.scion.jpan;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public class ScionSocketAddress extends InetSocketAddress {

  private final Path path;
  private final long isdAs;

  public static ScionSocketAddress from(Path path, long dstIsdAs, InetAddress dstIP, int dstPort) {
    return new ScionSocketAddress(path, dstIsdAs, dstIP, dstPort);
  }

  private ScionSocketAddress(Path path, long dstIsdAs, InetAddress dstIP, int dstPort) {
    super(dstIP, dstPort);
    this.path = path;
    this.isdAs = dstIsdAs;
  }

  public Path getPath() {
    return path;
  }

  public long getIsdAs() {
    return isdAs;
  }

  @Override
  public String toString() {
    return ScionUtil.toStringIA(isdAs) + "," + super.toString();
  }
}
