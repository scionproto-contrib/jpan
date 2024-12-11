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

package org.scion.jpan;

import java.io.IOException;
import java.nio.channels.IllegalSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.util.Set;

/**
 * TODO <br>
 * TODO <br>
 * TODO <br>
 * TODO <br>
 * TODO <br>
 * TODO <br>
 * TODO <br>
 * TODO <br>
 * TODO <br>
 * TODO <br>
 * TODO <br>
 * TODO <br>
 * TODO <br>
 * TODO <br>
 *
 * <p>Design
 *
 * <p>We have to implement our own Selector infrastructure because the JDK Selector expects
 * internally an instance of sun.nio.ch.SelChImpl, otherwise it throws a IllegalSelectorException.
 *
 * <p>QUestions:
 *
 * <p>How do we select() ?  We probably should call select() on the internal channel and return
 * corresponding SelectionKeys.
 *
 * <p>Move ScionSelectXXX classes into separate package?
 *
 * TODO
 * <p>Should we inherit DatagramChannel instead of using Delegation? This would be the only way
 * to be totally transparent to users that expect an instance of JDK datagram channel.<br>
 * -> This should be the first step <br>
 * -> THis may also allow us to use the JDK Selector infrastructure....? If we use a subclass
 *    of DatagramChannel, then we are also using a subclass of SelChImpl, or not?
 *
 * TODO
 * Steps:<br>
 * - FIx segments. etc
 * - Implement scatteringByteChannel
 * - Subclass DatagramChannel (consider avoiding Delegation)
 * - Revisit Selectors, possibly reuse JDK selectors.
 *
 *
 *
 *
 * TODO run PingPongSelectorServer to see current state
 */
public class ScionSelector extends AbstractSelector {

  public static ScionSelector open() throws IOException {
    return (ScionSelector) ScionSelectorProvider.provider().openSelector();
  }

  static ScionSelector create(ScionSelectorProvider scionSelectorProvider) {

    return new ScionSelector(scionSelectorProvider);
  }

  /**
   * Initializes a new instance of this class.
   *
   * @param provider The provider that created this selector
   */
  protected ScionSelector(ScionSelectorProvider provider) {
    super(provider);
  }

  @Override
  protected void implCloseSelector() throws IOException {
    // TODO?
  }

  @Override
  protected SelectionKey register(AbstractSelectableChannel ch, int ops, Object att) {
    return new ScionSelectionKey((ScionDatagramChannel) ch, this, ops);
  }

  @Override
  public Set<SelectionKey> keys() {
    // return Set.of();
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<SelectionKey> selectedKeys() {
    // return Set.of();
    throw new UnsupportedOperationException();
  }

  @Override
  public int selectNow() throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public int select(long timeout) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public int select() throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Selector wakeup() {
    // return null;
    throw new UnsupportedOperationException();
  }
}
