// Copyright 2025 ETH Zurich
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

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import org.scion.jpan.*;

/**
 * The PathProviderNoOp does (almost) nothing. It will provide a path when connect(path) is called.
 * It will also verify the path against the path policy. It will not check for expiration or poll
 * for new path. If a path is reported faulty, it will remove it.
 *
 * @see org.scion.jpan.internal.PathProvider
 */
public class PathProviderNoOp implements PathProvider {

  private long dstIsdAs;
  private InetSocketAddress dstAddress;
  private PathPolicy pathPolicy;
  private Path usedPath;

  private PathUpdateCallback subscriber;

  public static PathProviderNoOp create(PathPolicy policy) {
    return new PathProviderNoOp(policy);
  }

  private PathProviderNoOp(PathPolicy policy) {
    this.dstIsdAs = 0;
    this.dstAddress = null;
    this.pathPolicy = policy;
  }

  @Override
  public synchronized void reportFaultyPath(Path p) {
    if (!Objects.equals(usedPath, p)) {
      return;
    }
    usedPath = null;

    // No path available
    subscriber.updatePath(null);
  }

  @Override
  public void subscribe(PathUpdateCallback cb) {
    if (subscriber != null) {
      throw new IllegalStateException("This PathProvider already has a subscription.");
    }
    this.subscriber = cb;
  }

  @Override
  public synchronized PathPolicy getPathPolicy() {
    return pathPolicy;
  }

  @Override
  public synchronized void setPathPolicy(PathPolicy pathPolicy) {
    this.pathPolicy = pathPolicy;
    if (isConnected()) {
      checkPathPolicy();
    }
  }

  private void checkPathPolicy() {
    // Remove used path if it doesn't fit the policy
    if (usedPath != null && pathPolicy.filter(Collections.singletonList(usedPath)).isEmpty()) {
      usedPath = null;
      subscriber.updatePath(null);
    }
    assertPathExists();
  }

  private void assertPathExists() {
    if ((usedPath == null || isExpired(usedPath))) {
      String isdAs = ScionUtil.toStringIA(dstIsdAs);
      throw new ScionRuntimeException("No path found to destination: " + isdAs + "," + dstAddress);
    }
  }

  private boolean isExpired(Path path) {
    return path.getMetadata().getExpiration() < Instant.now().getEpochSecond();
  }

  @Override
  public void connect(Path path) {
    if (isConnected()) {
      throw new IllegalStateException("Path provider is already connected");
    }
    this.dstIsdAs = path.getRemoteIsdAs();
    this.dstAddress = path.getRemoteSocketAddress();

    if (isExpired(path)) {
      usedPath = null;
      subscriber.updatePath(null);
      assertPathExists(); // This will throw an exception
      return;
    }

    // use this path
    usedPath = path;
    subscriber.updatePath(path);
    checkPathPolicy();
  }

  @Override
  public synchronized void disconnect() {
    this.dstAddress = null;
    this.dstIsdAs = 0;
  }

  @Override
  public void setExpirationSafetyMargin(int cfgExpirationSafetyMargin) {
    // N/A
  }

  public boolean isConnected() {
    return this.dstAddress != null;
  }
}
