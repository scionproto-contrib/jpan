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

/**
 * This is the same as the {@link ScionSocketAddress}, except that it also has a {@link Path}.
 *
 * <p>Note: {@link #equals(Object)}, {@link #hashCode()}, and {@link #toString()} do not consider
 * the path. This is partly because {@link #equals(Object)}, {@link #hashCode()} are {@code final}
 * in the base class {@link java.net.InetSocketAddress}.
 */
public class ScionPathAddress extends ScionSocketAddress {

  private final Path path;

  static ScionPathAddress from(Path path, long dstIsdAs, InetAddress dstIP, int dstPort) {
    return new ScionPathAddress(path, dstIsdAs, dstIP, dstPort);
  }

  protected ScionPathAddress(Path path, long dstIsdAs, InetAddress dstIP, int dstPort) {
    super(path, dstIsdAs, dstIP, dstPort);
    this.path = path;
  }

  public Path getPath() {
    return path;
  }

  @Override
  public String toString() {
    return super.toString();
  }
}
