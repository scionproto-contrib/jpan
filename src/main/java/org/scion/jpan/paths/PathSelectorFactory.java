// Copyright 2026 ETH Zurich
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

package org.scion.jpan.paths;

import java.net.InetSocketAddress;
import java.util.List;

import org.scion.jpan.*;
import org.scion.jpan.internal.PathProvider;
import org.scion.jpan.internal.PathProviderNoOp;
import org.scion.jpan.internal.PathProviderWithRefresh;

public interface PathSelectorFactory {

  PathProvider getPathProvider(ScionService service, InetSocketAddress destination, PathProvider.PathUpdateCallback pathUpdateCallback);

  class Default implements PathSelectorFactory {

    private static final PathSelectorFactory INSTANCE = new Default();

    public static PathSelectorFactory instance() {
      return INSTANCE;
    }

    protected Default() {}

    public PathProvider getPathProvider(ScionService service, InetSocketAddress remote, PathProvider.PathUpdateCallback pathUpdateCallback) {
      PathProviderWithRefresh selector =
              PathProviderWithRefresh.create(service, PathPolicy.DEFAULT);
      selector.subscribe(pathUpdateCallback);
      if (remote instanceof ScionSocketAddress) {
        selector.connect(((ScionSocketAddress) remote).getPath()); // TODO connect(IP/ISD-AS) only?
      } else {
        List<Path> paths = null;
        try {
          paths = service.lookupPaths(remote);
        } catch (ScionException e) {
          throw new ScionRuntimeException(e);
        }

        if (paths.isEmpty()) {
          throw new ScionRuntimeException("No paths found for remote address " + remote);
        }
        selector.connect(paths.get(0)); // TODO this is not nice!
      }
      return selector;
    }
  }

  class NoOp implements PathSelectorFactory {

    private static final PathSelectorFactory INSTANCE = new NoOp(PathPolicy.DEFAULT);

    private final PathPolicy policy;

    public static PathSelectorFactory instance() {
      return INSTANCE;
    }

    protected NoOp(PathPolicy policy) {
      this.policy = policy;
    }

    public static PathSelectorFactory create(PathPolicy policy) {
      return new NoOp(policy);
    }

    public PathProvider getPathProvider(ScionService service, InetSocketAddress remote, PathProvider.PathUpdateCallback pathUpdateCallback) {
      PathProviderNoOp selector = PathProviderNoOp.create(policy);
      selector.subscribe(pathUpdateCallback);
      if (remote instanceof ScionSocketAddress) {
        selector.connect(((ScionSocketAddress) remote).getPath()); // TODO connect(IP/ISD-AS) only?
      } else {
        List<Path> paths = null;
        try {
          paths = service.lookupPaths(remote);
        } catch (ScionException e) {
          throw new ScionRuntimeException(e);
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
