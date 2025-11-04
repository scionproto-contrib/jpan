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

import org.scion.jpan.Path;
import org.scion.jpan.PathPolicy;

public interface PathProvider {
  /**
   * Report a faulty path. A new path will provided immediately (synchronously) if available. A
   * faulty path will not be provided again any time soon. It may be provided again at a later time.
   *
   * @param p Faulty path.
   */
  void reportFaultyPath(Path p);

  /**
   * Register a callback that is invoked when paths are updated.
   *
   * @param cb The callback method.
   */
  void subscribe(PathUpdateCallback cb);

  void setPathPolicy(PathPolicy pathPolicy);

  /**
   * Initialize the PathProvider with an existing path and start providing paths. The path provider
   * will (in this call) only request a new set of path if the provided path is expired. New paths
   * will be requested if the path is expired or about to expire, if additional subscribers
   * register, or if the path is reported faulty.
   *
   * <p>Contract: the PathProvider must synchronously update subscribed consumers with a new path.
   */
  void connect(Path path);

  /** Stop the path provider. */
  void disconnect();

  void setExpirationSafetyMargin(int cfgExpirationSafetyMargin);

  interface PathUpdateCallback {
    void updatePath(Path newPath);
  }
}
