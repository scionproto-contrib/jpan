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

import org.scion.jpan.*;

/**
 * The PathSelectorNull should be used when no PathSelector is required.
 *
 * @see PathSelector
 */
public class PathSelectorNull implements PathSelector {

  private static final PathSelectorNull INSTANCE = new PathSelectorNull();

  public static PathSelectorNull instance() {
    return INSTANCE;
  }

  private PathSelectorNull() {
    // Nothing.
  }

  @Override
  public synchronized void refresh() {
    // Nothing.
  }

  @Override
  public synchronized void reportError(Scmp.ErrorMessage error) {
    // Nothing.
  }

  @Override
  public synchronized PathPolicy getPathPolicy() {
    return PathPolicy.DEFAULT;
  }

  @Override
  public synchronized void setPathPolicy(PathPolicy pathPolicy) {
    // Nothing.
  }

  @Override
  public synchronized Path getPath() {
    throw new UnsupportedOperationException("No PathSelector is available.");
  }

  @Override
  public ScionSocketAddress getRemoteSocketAddress() {
    throw new UnsupportedOperationException("No PathSelector is available.");
  }

  @Override
  public synchronized void open(ScionSocketAddress remote) {
    throw new UnsupportedOperationException("No PathSelector is available.");
  }

  @Override
  public synchronized void close() {
    // Nothing.
  }

  @Override
  public void setExpirationSafetyMargin(int cfgExpirationSafetyMargin) {
    // Nothing.
  }

  @Override
  public boolean isOpen() {
    return false;
  }

  public static class Factory implements PathSelectorFactory {

    private static final PathSelectorNull.Factory INSTANCE = new PathSelectorNull.Factory();

    public static PathSelectorNull.Factory instance() {
      return INSTANCE;
    }

    @Override
    public PathSelector createPathSelector(ScionService service) {
      throw new UnsupportedOperationException("No PathSelectorFactory is available.");
    }
  }
}
