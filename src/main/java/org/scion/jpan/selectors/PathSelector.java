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

import java.net.InetSocketAddress;
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

  void setPathPolicy(PathPolicy pathPolicy);

  void connect(ScionSocketAddress remote);

  /**
   * Initialize the PathSelector with an existing path and start providing paths. The path provider
   * will (in this call) only request a new set of path if the provided path is expired. New paths
   * will be requested if the path is expired or about to expire or if the path is reported faulty.
   *
   * <p>Contract: the PathSelector must synchronously update subscribed consumers with a new path.
   *
   * @throws IllegalStateException if the PathSelector is already connected
   */
  void connect(Path path);

  /** Stop the path provider. */
  void disconnect();

  void setExpirationSafetyMargin(int cfgExpirationSafetyMargin);

  Path getPath();

  InetSocketAddress getRemoteSocketAddress();

  long getRemoteIsdAs();
}
