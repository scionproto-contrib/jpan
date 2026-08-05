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

package org.scion.jpan.testutil;

import java.net.InetSocketAddress;
import java.util.*;
import org.scion.jpan.*;
import org.scion.jpan.selectors.PathSelector;
import org.scion.jpan.selectors.PathSelectorFactory;

/**
 * The PathSelectorRotator is a simple provider that operates on a fixed list of paths.
 *
 * @see PathSelector
 */
public class PathSelectorRotator implements PathSelector {

  private PathPolicy pathPolicy = paths -> paths;
  private final ScionSocketAddress dstAddress;
  private List<Path> usedPaths = new ArrayList<>();
  private boolean isConnected = false;

  public static PathSelectorRotator create(List<Path> paths) {
    return new PathSelectorRotator(paths);
  }

  private PathSelectorRotator(List<Path> paths) {
    this.usedPaths.addAll(paths);
    this.dstAddress = paths.isEmpty() ? null : paths.get(0).getRemoteSocketAddress();
  }

  @Override
  public synchronized void refresh() {
    // Nothing to do
  }

  @Override
  public synchronized void reportError(Scmp.ErrorMessage error) {
    if (usedPaths.isEmpty()) {
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

    for (int i = 0; i < usedPaths.size(); i++) {
      // Yes, get always the first one, because we rotate the list in each loop.
      PathMetadata usedMeta = usedPaths.get(0).getMetadata();
      if (ScionUtil.isPathUsingInterface(usedMeta, faultyIsdAs, ifId1)
          || (ifId2 != null && ScionUtil.isPathUsingInterface(usedMeta, faultyIsdAs, ifId2))) {
        getNextPath();
      } else {
        // rotate until we find a path that works.
        break;
      }
    }
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
    usedPaths = pathPolicy.filter(usedPaths);
    assertPathExists();
  }

  private void assertPathExists() {
    if (usedPaths.isEmpty()) {
      String isdAs = ScionUtil.toStringIA(dstAddress.getIsdAs());
      throw new ScionRuntimeException("No path found to destination: " + isdAs + "," + dstAddress);
    }
  }

  @Override
  public synchronized Path getPath() {
    return usedPaths.get(0);
  }

  @Override
  public InetSocketAddress getRemoteSocketAddress() {
    return dstAddress;
  }

  @Override
  public long getRemoteIsdAs() {
    return dstAddress.getIsdAs();
  }

  @Override
  public synchronized void connect(ScionSocketAddress remote) {
    if (isConnected()) {
      throw new IllegalStateException("Path provider is already connected");
    }
    if (this.dstAddress != remote) {
      throw new IllegalArgumentException(this.dstAddress + " != " + remote);
    }

    checkPathPolicy();
    isConnected = true;
  }

  @Override
  public synchronized void disconnect() {
    isConnected = false;
  }

  @Override
  public void setExpirationSafetyMargin(int cfgExpirationSafetyMargin) {
    // N/A
  }

  public synchronized boolean isConnected() {
    return isConnected;
  }

  private void getNextPath() {
    usedPaths.add(usedPaths.remove(0));
  }

  public static class Factory extends PathSelectorFactory.AbstractPathSelectorFactory {
    private final PathSelector selector;

    protected Factory(PathSelector selector) {
      super(PathPolicy.DEFAULT);
      this.selector = selector;
    }

    public static PathSelectorFactory create(PathSelector selector) {
      return new Factory(selector);
    }

    public PathSelector createPathSelector(ScionService service) {
      return selector;
    }
  }
}
