// Copyright 2023 ETH Zurich
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

package org.scion;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

public class ScionDatagramChannel {

    private final DatagramChannel channel;
    private final ScionPacketHelper helper = new ScionPacketHelper();
    private ScionPacketHelper.PathState pathState = ScionPacketHelper.PathState.NO_PATH;

    public static ScionDatagramChannel open() throws IOException {
        return new ScionDatagramChannel();
    }

    protected ScionDatagramChannel() throws IOException {
        channel = DatagramChannel.open();
    }

    public synchronized SocketAddress receive(ByteBuffer userBuffer) throws IOException {
        byte[] bytes = new byte[65536];
        ByteBuffer buffer = ByteBuffer.wrap(bytes); // TODO allocate direct?
        SocketAddress srcAddr = null;
        srcAddr = channel.receive(buffer);
        if (srcAddr == null) {
            // this indicates nothing is available
            return null;
        }
        buffer.flip();

        int headerLength = helper.readScionHeader(bytes);
        userBuffer.put(bytes, headerLength, helper.getPayloadLength());
        pathState = ScionPacketHelper.PathState.RCV_PATH;
        return helper.getReceivedSrcAddress();
    }

    public synchronized void send(ByteBuffer buffer, InetSocketAddress destinationAddress) throws IOException {
        byte[] buf = new byte[1000]; /// TODO ????  1000?
        InetSocketAddress localAddress = (InetSocketAddress) channel.getLocalAddress(); // TODO check type?
        int payloadLength = buffer.limit() - buffer.position();
        int headerLength = helper.writeHeader(buf, pathState, localAddress, destinationAddress, payloadLength);

        ByteBuffer buffer2 = ByteBuffer.allocate(payloadLength + headerLength); // TODO allocate direct??? Capacity?
        System.arraycopy(buf, 0, buffer2.array(), buffer2.arrayOffset(), headerLength);
        // TODO arrayOffset vs position() ?
        System.arraycopy(buffer.array(), buffer.arrayOffset(), buffer2.array(), buffer2.arrayOffset() + headerLength, payloadLength);
        SocketAddress dstAddress = helper.getFirstHopAddress();
        channel.send(buffer2, dstAddress);
        buffer.position(buffer.limit());
    }

    public ScionDatagramChannel bind(InetSocketAddress address) throws IOException {
        channel.bind(address);
        return this;
    }

    public void configureBlocking(boolean block) throws IOException {
        channel.configureBlocking(block);
    }

    @Deprecated
    public void setDstIsdAs(String isdAs) {
        helper.setDstIsdAs(isdAs);
    }
}
