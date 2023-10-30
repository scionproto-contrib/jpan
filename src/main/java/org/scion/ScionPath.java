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

import org.scion.proto.daemon.Daemon;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * A SCION path represents a single path from a source to a destination.
 * Paths can be retrieved from the ScionService.
 */
public class ScionPath {
    private final Daemon.Path pathProtoc;
    private final byte[] pathRaw;
    private final long srcIsdAs;
    private final long dstIsdAs;


    ScionPath(Daemon.Path path, long srcIsdAs, long dstIsdAs) {
        this.pathProtoc = path;
        this.pathRaw = null;
        this.srcIsdAs = srcIsdAs;
        this.dstIsdAs = dstIsdAs;
    }

    ScionPath(byte[] path, long srcIsdAs, long dstIsdAs) {
        this.pathProtoc = null;
        this.pathRaw = path;
        this.srcIsdAs = srcIsdAs;
        this.dstIsdAs = dstIsdAs;
    }

    public static ScionPath create(byte[] rawPath, long srcIsdAs, long dstIsdAs) {
        return new ScionPath(rawPath, srcIsdAs, dstIsdAs);
    }

    Daemon.Path getPathInternal() {
        return pathProtoc;
    }

    public long getDestinationCode() {
        return dstIsdAs;
    }

    InetSocketAddress getFirstHopAddress() {
        Daemon.Path internalPath = pathProtoc;
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
}
