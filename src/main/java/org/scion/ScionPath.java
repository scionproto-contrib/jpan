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

import com.google.protobuf.ByteString;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.scion.proto.daemon.Daemon;

/**
 * A SCION path represents a single path from a source to a destination.
 * Paths can be retrieved from the ScionService.
 * <p>
 * This class is threadsafe.
 */
public class ScionPath {
    private final Daemon.Path pathProtoc;
    // ScionPath ois basically immutable, it may be accessed in multiple thread concurrently.
    private volatile byte[] pathRaw;
    private final long srcIsdAs;
    private final long dstIsdAs;
    private final InetSocketAddress firstHopAddress;


    ScionPath(Daemon.Path path, long srcIsdAs, long dstIsdAs) {
        this.pathProtoc = path;
        this.pathRaw = null;
        this.srcIsdAs = srcIsdAs;
        this.dstIsdAs = dstIsdAs;
        this.firstHopAddress = getFirstHopAddress(path);
    }

    ScionPath(byte[] path, long srcIsdAs, long dstIsdAs, InetSocketAddress firstHopAddress) {
        this.pathProtoc = null;
        this.pathRaw = path;
        this.srcIsdAs = srcIsdAs;
        this.dstIsdAs = dstIsdAs;
        this.firstHopAddress = firstHopAddress;
    }

    public static ScionPath create(byte[] rawPath, long srcIsdAs, long dstIsdAs, InetSocketAddress firstHopAddress) {
        return new ScionPath(rawPath, srcIsdAs, dstIsdAs, firstHopAddress);
    }

    Daemon.Path getPathInternal() {
        return pathProtoc;
    }

    public long getDestinationIsdAs() {
        return dstIsdAs;
    }

    // TODO naming: Source vs Src?
    public long getSourceIsdAs() {
        return srcIsdAs;
    }

    public InetSocketAddress getFirstHopAddress() {
        return firstHopAddress;
    }

    private InetSocketAddress getFirstHopAddress(Daemon.Path internalPath) {
        String underlayAddressString = internalPath.getInterface().getAddress().getAddress();
        try {
            int splitIndex = underlayAddressString.indexOf(':');
            InetAddress underlayAddress = InetAddress.getByName(underlayAddressString.substring(0, splitIndex));
            int underlayPort = Integer.parseUnsignedInt(underlayAddressString.substring(splitIndex + 1));
            return new InetSocketAddress(underlayAddress, underlayPort);
        } catch (UnknownHostException e) {
            // TODO throw IOException?
            throw new RuntimeException(e);
        }
    }

    public byte[] getRawPath() {
        if (pathRaw == null) {
            ByteString bs  = pathProtoc.getRaw();
            pathRaw = new byte[bs.size()];
            for (int i = 0; i < bs.size(); i++) {
                pathRaw[i] = bs.byteAt(i);
            }

        }
        return pathRaw;
    }
}
