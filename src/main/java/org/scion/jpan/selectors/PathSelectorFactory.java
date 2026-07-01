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

package org.scion.jpan.selectors;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import org.scion.jpan.*;

public interface PathSelectorFactory {

  PathSelector createPathSelector(ScionService service, InetSocketAddress destination)
      throws IOException;

  abstract class AbstractPathSelectorFactory implements PathSelectorFactory {
    private final PathPolicy defaultPolicy;

    protected AbstractPathSelectorFactory(PathPolicy defaultPolicy) {
      this.defaultPolicy = defaultPolicy;
    }

    public PathPolicy getDefaultPolicy() {
      return defaultPolicy;
    }
  }

  class Default extends AbstractPathSelectorFactory {

    private static final PathSelectorFactory INSTANCE = new Default(PathPolicy.DEFAULT);

    public static PathSelectorFactory instance() {
      return INSTANCE;
    }

    protected Default(PathPolicy defaultPolicy) {
      super(defaultPolicy);
    }

    public static PathSelectorFactory create(PathPolicy defaultPolicy) {
      return new Default(defaultPolicy);
    }

    public PathSelector createPathSelector(ScionService service, InetSocketAddress remote)
        throws IOException {
      PathSelectorWithRefresh selector =
          PathSelectorWithRefresh.create(service, getDefaultPolicy());
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

  class NoOp extends AbstractPathSelectorFactory {

    private static final PathSelectorFactory INSTANCE = new NoOp(PathPolicy.DEFAULT);

    public static PathSelectorFactory instance() {
      return INSTANCE;
    }

    protected NoOp(PathPolicy policy) {
      super(policy);
    }

    public static PathSelectorFactory create(PathPolicy policy) {
      return new NoOp(policy);
    }

    public PathSelector createPathSelector(ScionService service, InetSocketAddress remote)
        throws IOException {
      PathSelectorNoOp selector = PathSelectorNoOp.create(getDefaultPolicy());
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
