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

package org.scion.jpan.testutil.test;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scion.jpan.internal.ByteUtil;
import org.scion.jpan.internal.NatMapping;
import org.scion.jpan.internal.STUN;
import org.scion.jpan.testutil.ManagedThread;
import org.scion.jpan.testutil.MockDatagramChannel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

class MockDatagramChannelTest {

    private static final long LONG = 12345678;

    @Disabled
    @Test
    void test() throws IOException {
        try (MockDatagramChannel channel = MockDatagramChannel.open()) {
            channel.setSendCallback((byteBuffer,socketAddress) -> byteBuffer.limit());
            channel.setReceiveCallback(byteBuffer -> {
                // We add a request
                byteBuffer.putLong(12345678);
                return null;
            });

            boolean isBlocking = channel.isBlocking();
            try (Selector selector = channel.provider().openSelector()) {
                channel.configureBlocking(false);
                // start receiver
                channel.register(selector, SelectionKey.OP_READ, channel);
                doStunRequest(selector, 1, channel);
            } finally {
                channel.configureBlocking(isBlocking);
            }
        }
    }

    @Test
    void testORIGINAL() throws IOException {
        ManagedThread sender = ManagedThread.newBuilder().expectThrows(InterruptedException.class).build();
        try (DatagramChannel channel = DatagramChannel.open()) {
            channel.bind(null);
            InetSocketAddress local = (InetSocketAddress) channel.getLocalAddress();

            sender.submit(news -> {
                try (DatagramChannel chn = DatagramChannel.open()) {
                    news.reportStarted();
                    while (true) {
                        ByteBuffer buffer = ByteBuffer.allocate(8);
                        buffer.putLong(LONG);
                        buffer.flip();
                        chn.send(buffer, local);
                        Thread.sleep(10);
                    }
                }
            });



            boolean isBlocking = channel.isBlocking();
            try (Selector selector = channel.provider().openSelector()) {
                channel.configureBlocking(false);
                // start receiver
                channel.register(selector, SelectionKey.OP_READ, channel);
                doStunRequest(selector, 1, channel);
            } finally {
                channel.configureBlocking(isBlocking);
            }
        } finally {
            sender.stopNow();
        }
    }


    private boolean doStunRequest(Selector selector, int n, DatagramChannel channel) throws IOException {
        InetSocketAddress dst = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12131);


        ByteBuffer buffer = ByteBuffer.allocate(1000);

        // Start sending
        for (int i = 0; i < n; i++) {
            buffer.clear();
            buffer.putLong(12345678);
            buffer.flip();
            channel.send(buffer, dst);
        }

        // Wait
        while (selector.select(100) > 0) {
            Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                if (key.isReadable()) {
                    DatagramChannel channelIn = (DatagramChannel) key.channel();
                    buffer.clear();
                    channelIn.receive(buffer);
                    buffer.flip();

                    long l = buffer.getLong();
                    if (l == 12345678) {
                        return true;
                    }

                }
            }
        }
        return false;
    }
}
