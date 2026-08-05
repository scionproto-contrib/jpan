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

import org.scion.jpan.*;

public interface PathSelectorFactory {

  PathSelector createPathSelector(ScionService service);

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

    public PathSelector createPathSelector(ScionService service) {
      return PathSelectorWithRefresh.create(service, getDefaultPolicy());
    }
  }

  class Fixed extends AbstractPathSelectorFactory {

    private static final PathSelectorFactory INSTANCE = new Fixed(PathPolicy.DEFAULT);

    public static PathSelectorFactory instance() {
      return INSTANCE;
    }

    protected Fixed(PathPolicy policy) {
      super(policy);
    }

    public static PathSelectorFactory create(PathPolicy policy) {
      return new Fixed(policy);
    }

    @Override
    public PathSelector createPathSelector(ScionService service) {
      return PathSelectorFixed.create(getDefaultPolicy());
    }
  }
}
