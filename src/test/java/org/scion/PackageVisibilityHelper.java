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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Helper class to access package private methods in org.scion.PathService and ScionPacketHelper.
 */
public class PackageVisibilityHelper {

    public static final String DEBUG_PROPERTY_DNS_MOCK = ScionConstants.DEBUG_PROPERTY_DNS_MOCK;

    public static List<Daemon.Path> getPathList(ScionPathService service, long srcIsdAs, long dstIsdAs) {
        return service.getPathList(srcIsdAs, dstIsdAs);
    }

    public static InetSocketAddress getSrcAddress(byte[] packet) {
        try (ScionPacketHelper helper = new ScionPacketHelper(ScionPacketHelper.PathState.NO_PATH)) {
            helper.readScionHeader(packet);
            return helper.getReceivedSrcAddress();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static InetSocketAddress getDstAddress(byte[] packet) {
        try (ScionPacketHelper helper = new ScionPacketHelper(ScionPacketHelper.PathState.NO_PATH)) {
            helper.readScionHeader(packet);
            return helper.getReceivedDstAddress();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
