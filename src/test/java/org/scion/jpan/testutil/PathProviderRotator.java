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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import org.scion.jpan.*;
import org.scion.jpan.internal.AddressLookupService;
import org.scion.jpan.internal.PathProvider;
import org.scion.jpan.internal.ScionAddress;
import org.scion.jpan.paths.PathSelectorFactory;

/**
 * The PathProviderRotator is a simple provider that operates on a fixed list of paths.
 *
 * @see PathProvider
 */
public class PathProviderRotator implements PathProvider {

  private PathPolicy pathPolicy = paths -> paths;
  private long dstIsdAs;
  private ScionSocketAddress dstAddress;
  private List<Path> usedPaths = new ArrayList<>();

  private PathUpdateCallback subscriber;

  public static PathProviderRotator create(List<Path> paths) {
    return new PathProviderRotator(paths);
  }

  private PathProviderRotator(List<Path> paths) {
    this.usedPaths.addAll(paths);
  }

  @Override
  public synchronized void reportFaultyPath(Path p) {
    if (!Objects.equals(usedPaths.get(0), p)) {
      return;
    }
    getNextPath();
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

    PathMetadata usedMeta = usedPaths.get(0).getMetadata();
    if (ScionUtil.isPathUsingInterface(usedMeta, faultyIsdAs, ifId1)
        || (ifId2 != null && ScionUtil.isPathUsingInterface(usedMeta, faultyIsdAs, ifId2))) {
      getNextPath();
    }
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
    usedPaths = pathPolicy.filter(usedPaths);
    subscriber.updatePath(usedPaths.get(0));
    assertPathExists();
  }

  private void assertPathExists() {
    if (usedPaths.isEmpty()) {
      String isdAs = ScionUtil.toStringIA(dstIsdAs);
      throw new ScionRuntimeException("No path found to destination: " + isdAs + "," + dstAddress);
    }
  }

  @Override
  public synchronized Path getPath() {
    return usedPaths.get(0);
  }

  @Override
  public synchronized void connect(InetSocketAddress remote) throws IOException {
    if (true) {
      throw new UnsupportedOperationException();
    }
    if (isConnected()) {
      throw new IllegalStateException("Path provider is already connected");
    }

    ScionAddress sa;
    try {
      sa = AddressLookupService.lookupAddress(remote.getHostString());
    } catch (ScionException e) {
      throw new IOException(e);
    }

    //    this.dstIsdAs = sa.getIsdAs();
    //    this.dstAddress = remote;
    //
    //    // use this path
    //    if (remote.getPath() != usedPaths.get(0)) {
    //      throw new IllegalArgumentException();
    //    }
    //    subscriber.updatePath(remote.getPath());
    //    checkPathPolicy();
  }

  @Override
  public void connect(ScionSocketAddress remote) {
    if (true) {
      throw new UnsupportedOperationException();
    }
    if (isConnected()) {
      throw new IllegalStateException("Path provider is already connected");
    }
    this.dstIsdAs = remote.getIsdAs();
    this.dstAddress = remote;

    // use this path
    if (remote.getPath() != usedPaths.get(0)) {
      throw new IllegalArgumentException();
    }
    subscriber.updatePath(remote.getPath());
    checkPathPolicy();
  }

  @Override
  public void connect(Path path) {
    if (isConnected()) {
      throw new IllegalStateException("Path provider is already connected");
    }
    this.dstIsdAs = path.getRemoteIsdAs();
    this.dstAddress = path.getRemoteSocketAddress();

    // use this path
    if (path != usedPaths.get(0)) {
      throw new IllegalArgumentException();
    }
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

  private void getNextPath() {
    usedPaths.add(usedPaths.remove(0));
    subscriber.updatePath(usedPaths.get(0));
  }

  public static class FixedFactory implements PathSelectorFactory {
    private final PathProvider selector;

    protected FixedFactory(PathProvider selector) {
      this.selector = selector;
    }

    public static PathSelectorFactory create(PathProvider selector) {
      return new FixedFactory(selector);
    }

    public PathProvider createPathSelector(
        ScionService service,
        InetSocketAddress remote,
        PathProvider.PathUpdateCallback pathUpdateCallback)
        throws IOException {
      selector.subscribe(pathUpdateCallback);
      if (remote instanceof ScionSocketAddress) {
        selector.connect(((ScionSocketAddress) remote).getPath()); // TODO connect(IP/ISD-AS) only?
      } else {
        List<Path> paths = null;
        try {
          paths = service.lookupPaths(remote);
        } catch (ScionException e) {
          throw new IOException(e);
        }

        if (paths.isEmpty()) {
          throw new ScionRuntimeException("No paths found for remote address " + remote);
        }
        selector.connect(paths.get(0)); // TODO this is not nice!
      }
      return selector;
    }
  }
}
