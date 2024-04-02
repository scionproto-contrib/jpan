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

package org.scion.testutil;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.scion.Constants;
import org.scion.Scion;

public class JUnitSetUp
    implements BeforeAllCallback, BeforeEachCallback, ExtensionContext.Store.CloseableResource {
  private static boolean started = false;

  @Override
  public void beforeAll(ExtensionContext context) {
    if (!started) {
      started = true;
      context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).put("any unique name", this);
    }
  }

  @Override
  public void close() {
    // System.out.println("Singleton::Finish-Once");
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    Scion.closeDefault();
  }
}
