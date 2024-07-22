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

import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectionKey;

public class ScionSelectionKey extends AbstractSelectionKey {

    private final ScionDatagramChannel channel;
    private final ScionSelector selector;
    private int ops;

    ScionSelectionKey(ScionDatagramChannel channel, ScionSelector selector, int ops) {
        this.channel = channel;
        this.selector = selector;
        this.ops = ops;
    }

    @Override
    public SelectableChannel channel() {
        return channel;
    }

    @Override
    public Selector selector() {
        return selector;
    }

    @Override
    public int interestOps() {
        // TODO synchronize, see super-class javadoc
        return ops;
    }

    @Override
    public SelectionKey interestOps(int ops) {
        this.ops = ops;
        return this;
    }

    @Override
    public int readyOps() {
        return 0;
    }
}
