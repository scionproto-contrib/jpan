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

import java.io.IOException;
import org.scion.jpan.Scmp;

public interface ScmpErrorHandler {

  /**
   * Handle an SCMP error message. Actions usually include:<br>
   * - Throw an IOException - Report affected paths to a PathProvider so that affected paths get
   * de-prioritized.
   *
   * @param msg The error message
   * @param connected
   * @throws IOException in case a Exception should trigger an error.
   */
  void handle(Scmp.ErrorMessage msg, boolean connected) throws IOException;
}
