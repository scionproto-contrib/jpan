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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.scion.jpan.proto.daemon.Daemon;

class PathDuplicationFilter {

  MultiMap<Integer, Entry> paths = new MultiMap<>();

  private static class Entry {
    Daemon.Path path;
    int[] interfaces;

    public Entry(Daemon.Path path, int[] interfaces) {
      this.path = path;
      this.interfaces = interfaces;
    }

    public void update(Daemon.Path path, int[] interfaces) {
      this.path = path;
      this.interfaces = interfaces;
    }
  }

  /**
   * Add path to list, but avoid duplicates. A path is considered "duplicate" if it uses the same
   * sequence of interface IDs. In case of a duplicate, we keep the path with the latest expiration
   * date.
   *
   * @param path New path
   */
  void checkDuplicatePaths(Daemon.Path.Builder path) {
    // Create a hash code that uses only interface IDs
    int[] interfaces = extractInterfaces(path);
    int hash = Arrays.hashCode(interfaces);
    if (paths.contains(hash)) {
      for (Entry storedPath : paths.get(hash)) {
        if (Arrays.equals(interfaces, storedPath.interfaces)) {
          // Which one doe we keep? Compare minimum expiration date.
          if (path.getExpiration().getSeconds() > storedPath.path.getExpiration().getSeconds()) {
            storedPath.update(path.build(), interfaces);
          }
          return;
        }
      }
    }
    // Add new path!
    paths.put(hash, new Entry(path.build(), interfaces));
  }

  public List<Daemon.Path> getPaths() {
    List<Entry> entries = paths.values();
    List<Daemon.Path> result = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      result.add(entry.path);
    }
    return result;
  }

  /**
   * Extract used interfaces. Typically, for detecting duplicates, we could just compare the rwa
   * byte[]. This would detect most duplicates. However, in some cases we get two paths that use
   * identical interfaces but have different SegmentID, Expiration Date and different "unused"
   * interfaces. For example, in the scionproto "default" topology, going from 1-ff00:0:111 to
   * 1-ff00:0:112, we end up with two path that look externally like this: [494 > 103]. However,
   * internally they look like this:
   *
   * <p>- segID=9858, timestamp=1723449803, [494, 0, 104, 103] <br>
   * - segID=9751, timestamp=1723449803, [494, 0, 105, 103] <br>
   *
   * <p>The 104 vs 105 interface is not actually used and is an artifact of the path being
   * shortened. The following methods considers these cases and only extracts interface IDs of
   * interfaces that are actually used.
   *
   * @param path path
   * @return interfaces
   */
  private static int[] extractInterfaces(Daemon.Path.Builder path) {
    ByteBuffer raw = path.getRaw().asReadOnlyByteBuffer();
    int[] segLen = PathRawParserLight.getSegments(raw);
    int segCount = PathRawParserLight.calcSegmentCount(segLen);
    int[] result = new int[PathRawParserLight.extractHopCount(segLen) * 2];
    int offset = 0;
    int ifPos = 0;
    for (int j = 0; j < segLen.length; j++) {
      boolean flagC = PathRawParserLight.extractInfoFlagC(raw, j);
      for (int i = offset; i < offset + segLen[j] - 1; i++) {
        if (flagC) {
          result[ifPos++] = PathRawParserLight.extractHopFieldEgress(raw, segCount, i);
          result[ifPos++] = PathRawParserLight.extractHopFieldIngress(raw, segCount, i + 1);
        } else {
          result[ifPos++] = PathRawParserLight.extractHopFieldIngress(raw, segCount, i);
          result[ifPos++] = PathRawParserLight.extractHopFieldEgress(raw, segCount, i + 1);
        }
      }
      offset += segLen[j];
    }
    return result;
  }
}
