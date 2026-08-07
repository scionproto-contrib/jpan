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

import org.scion.jpan.Path;
import org.scion.jpan.PathPolicy;
import org.scion.jpan.ScionSocketAddress;
import org.scion.jpan.Scmp;

/**
 * A PathSelector provides the next best path. Lifecycle:<br>
 * 1) create PathSelector <br>
 * 2) connect()<br>
 * 2a) (reportError() if one is received)<br>
 * 3) disconnect()<br>
 */
public interface PathSelector {

  /**
   * Report paths as faulty. The algorithm is pretty simple: This method tags all paths as faulty
   * that use the ISD/AS and at least one of the interfaces that are reported in the error.
   *
   * <p>A more advanced algorithm could also de-rank any path through an affected AS, even if other
   * interfaces are used (especially if internal connectivity is affected) or when the AS is
   * addressed through a different ISD.
   *
   * @param error The SCMP error.
   */
  void reportError(Scmp.ErrorMessage error);

  PathPolicy getPathPolicy();

  /**
   * Set a new PathPolicy. This method will not be applied to the current paths in the PathSelector.
   * To apply the new PathPolicy, {@link #refresh()} must be called.
   *
   * @param pathPolicy The PathPolicy instance.
   */
  void setPathPolicy(PathPolicy pathPolicy);

  /**
   * Initialize the PathSelector with a destination address. The path provider may (in this call)
   * request a new set of path if it has not valid paths.
   *
   * @throws IllegalStateException if the PathSelector is already connected
   */
  void connect(ScionSocketAddress remote);

  /**
   * If the PathSelector supports refresh, it will discard all existing paths and fetch new paths
   * from the path service. If refresh is not supported this method does nothing.
   */
  void refresh();

  /** Stop the path provider. */
  void disconnect();

  void setExpirationSafetyMargin(int cfgExpirationSafetyMargin);

  Path getPath();

  ScionSocketAddress getRemoteSocketAddress();
}
