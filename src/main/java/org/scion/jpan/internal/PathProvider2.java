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
import org.scion.jpan.RequestPath;

import java.net.InetSocketAddress;

public interface PathProvider2<K> {
  /**
   * A faulty path should not be provided any time soon. It may be provided at a later time.
   * @param key The key used to register the callback.
   * @param p Faulty path.
   */
  void reportFaultyPath(K key, Path p);

  /**
   * Register a callback that is invoked when paths are updated.
   * A typical single-path DatagramChannel will require only one such callback.
   * If the PAthProvider is connected, see {@link #connect(Path)}, it will
   * immediately invoke the callback with a path.
   * @param key The key to identify the callback. THis is used to unregister the callback.
   * @param cb The callback method.
   */
  void subscribe(K key, PathUpdateCallback cb);

  /**
   * Unregister a previously registered callback.
   * @param key The key used to register the callback.
   */
  void unsubscribe(K key);

  void setPathPolicy(PathPolicy pathPolicy);

  /**
   * Initialize the PathProvider with a remote destination and start providing paths.
   * The path provider will (in this call) request a first set of path and assign on
   * path to all registered callbacks..
   */
  void connect(long isdAs, InetSocketAddress destination);

  /**
   * Initialize the PathProvider with an existing path and start providing paths.
   * The path provider will (in this call) only request a new set of path if the provided path
   * is expired. New paths will be requested if the path is expired or about to expire,
   * if additional subscribers register, or if the path is reported faulty.
   */
  void connect(Path path);

  /**
   * Stop the path provider.
   */
  void disconnect();

  interface PathUpdateCallback {
    void pathsUpdated(Path newPath);
  }
}
