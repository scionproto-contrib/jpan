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

package org.scion.jpan.internal;

import com.google.protobuf.ByteString;
import org.scion.jpan.proto.daemon.Daemon;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class PathDeduplicator {

    MultiMap<Integer, Entry> paths = new MultiMap<>();

    private static class Entry {
        Daemon.Path path;
        byte[] nullified;

        public Entry(Daemon.Path path, byte[] nullified) {
            this.path = path;
            this.nullified = nullified;
        }
    }

    void checkDuplicatePaths(Daemon.Path.Builder path) {
        // Add, path to list, but avoid duplicates
        ByteString raw = path.getRaw();
        // Create a hash code that ignores segment IDs and expiry date
        byte[] nullifiedRaw = nullifySegmentIDsAndExpiry(path);
        int hash = Arrays.hashCode(nullifiedRaw);
        if (paths.contains(hash)) {
            for (Entry otherPath : paths.get(hash)) {
                byte[] otherNullifiedRaw = otherPath.nullified;
                boolean equals = Arrays.equals(nullifiedRaw, otherNullifiedRaw);
                if (equals) {
                    // duplicate!
                    // Which one doe we keep? Compare minimum expiration date.

                    // TODO We should also nullify MAC and exp-date (not only time stamp)
                    //    Better yet, we should just compare metadata!
                    //    This would also create less objects. No need to nullify anything!
                    //   We should only compare hopCount and ingress/egress IDs!!
                    if (true) throw new UnsupportedOperationException();

                    return;
                }
            }
        }
        // Add new path!
        paths.put(hash, new Entry(path.build(), nullifiedRaw));
    }

    public List<Daemon.Path> getPaths() {
        List<Entry> entries = paths.values();
        List<Daemon.Path> result = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            result.add(entry.path);
        }
        return result;
    }

    private static byte[] nullifySegmentIDsAndExpiry(Daemon.Path.Builder path) {
        // Nullify segment IDs and expiry date

        ByteString byteString = path.getRaw();
        ByteBuffer bb = byteString.asReadOnlyByteBuffer();
        int header = bb.getInt();

        int hopCount0 = ByteUtil.readInt(header, 14, 6);
        int hopCount1 = ByteUtil.readInt(header, 20, 6);
        int hopCount2 = ByteUtil.readInt(header, 26, 6);

        if (hopCount0 > 0) {
            // TODO we are also skipping the flags here, is that alright?
            bb.getLong();
        }
        if (hopCount1 > 0) {
            bb.getLong();
        }
        if (hopCount2 > 0) {
            bb.getLong();
        }

        byte[] result = new byte[bb.remaining()];
        bb.get(result);
        return result;
    }

}
