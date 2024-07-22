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
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;

public class ScionSelectorProvider extends SelectorProvider {

    private static final ScionSelectorProvider INSTANCE = new ScionSelectorProvider();

    public static SelectorProvider provider() {
        return INSTANCE;
    }

    @Override
    public DatagramChannel openDatagramChannel() throws IOException {
        //return ScionDatagramChannel;
        throw new UnsupportedOperationException();
    }

    @Override
    public DatagramChannel openDatagramChannel(ProtocolFamily family) throws IOException {
        if (StandardProtocolFamily.INET.equals(family) || StandardProtocolFamily.INET6.equals(family)) {
            //return ScionDatagramChannel.open().channel();
            throw new UnsupportedOperationException();
        }
        throw new UnsupportedOperationException("Protocol family not supported: " + family.name());
    }

    @Override
    public Pipe openPipe() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public AbstractSelector openSelector() throws IOException {
        return ScionSelector.create(this);
    }

    @Override
    public ServerSocketChannel openServerSocketChannel() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public SocketChannel openSocketChannel() throws IOException {
        throw new UnsupportedOperationException();
    }
}
