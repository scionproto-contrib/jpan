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

package org.scion.jpan.selectors;

import java.util.*;
import org.scion.jpan.*;

/**
 * The PathSelectorFixed does (almost) nothing. It will provide a path when open() is called. It
 * will also verify the path against the path policy. It will not check for expiration or poll for
 * new path. If a path is reported faulty, it will remove it.
 *
 * @see PathSelector
 */
public class PathSelectorFixed implements PathSelector {

  private ScionSocketAddress dstAddress;
  private PathPolicy pathPolicy;
  private Path usedPath;

  public static PathSelectorFixed create() {
    return create(PathPolicy.DEFAULT);
  }

  public static PathSelectorFixed create(PathPolicy policy) {
    return new PathSelectorFixed(policy);
  }

  private PathSelectorFixed(PathPolicy policy) {
    this.dstAddress = null;
    this.pathPolicy = policy;
  }

  @Override
  public synchronized void refresh() {
    // Nothing to do
  }

  @Override
  public synchronized void reportError(Scmp.ErrorMessage error) {
    if (usedPath == null) {
      return;
    }

    long faultyIsdAs;
    long ifId1;
    Long ifId2 = null;
    if (error instanceof Scmp.Error5Message) {
      Scmp.Error5Message error5 = (Scmp.Error5Message) error;
      faultyIsdAs = error5.getIsdAs();
      ifId1 = error5.getInterfaceId();
    } else if (error instanceof Scmp.Error6Message) {
      Scmp.Error6Message error6 = (Scmp.Error6Message) error;
      faultyIsdAs = error6.getIsdAs();
      ifId1 = error6.getIngressId();
      ifId2 = error6.getEgressId();
    } else {
      return;
    }

    PathMetadata usedMeta = usedPath.getMetadata();
    if (ScionUtil.isPathUsingInterface(usedMeta, faultyIsdAs, ifId1)
        || (ifId2 != null && ScionUtil.isPathUsingInterface(usedMeta, faultyIsdAs, ifId2))) {
      usedPath = null;
    }
  }

  @Override
  public synchronized PathPolicy getPathPolicy() {
    return pathPolicy;
  }

  @Override
  public synchronized void setPathPolicy(PathPolicy pathPolicy) {
    this.pathPolicy = pathPolicy;
    if (isOpen()) {
      checkPathPolicy();
    }
  }

  private void checkPathPolicy() {
    // Remove used path if it doesn't fit the policy
    if (usedPath != null && pathPolicy.filter(Collections.singletonList(usedPath)).isEmpty()) {
      usedPath = null;
    }
  }

  @Override
  public synchronized Path getPath() {
    return usedPath;
  }

  @Override
  public ScionSocketAddress getRemoteSocketAddress() {
    return dstAddress;
  }

  /**
   * Initialize the PathSelector with the path associated with the ScionSocketAddress.
   *
   * @throws IllegalStateException if the PathSelector is already running
   * @see PathSelector#open(ScionSocketAddress)
   */
  @Override
  public synchronized void open(ScionSocketAddress remote) {
    if (isOpen()) {
      throw new IllegalStateException("Path selector is already running");
    }
    this.dstAddress = remote;

    // use this path
    usedPath = remote.getPath();
    checkPathPolicy();
  }

  @Override
  public synchronized void close() {
    this.dstAddress = null;
  }

  @Override
  public void setExpirationSafetyMargin(int cfgExpirationSafetyMargin) {
    // N/A
  }

  @Override
  public boolean isOpen() {
    return this.dstAddress != null;
  }
}
