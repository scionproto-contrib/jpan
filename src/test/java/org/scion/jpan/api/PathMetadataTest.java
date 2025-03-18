// Copyright 2025 ETH Zurich
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

package org.scion.jpan.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.proto.control_plane.Seg;
import org.scion.jpan.testutil.MockControlServer;
import org.scion.jpan.testutil.MockNetwork;

class PathMetadataTest {

  @BeforeEach
  void beforeEach() {
    MockNetwork.startTiny();
  }

  @AfterEach
  void afterEach() {
    MockNetwork.stopTiny();
  }

  @Test
  void test() {
    MockControlServer cs = MockControlServer.start(12345);
    Seg.SegmentsResponse.Builder respUp = Seg.SegmentsResponse.newBuilder();
    Seg.SegmentsResponse.Segments.Builder segUp = Seg.SegmentsResponse.Segments.newBuilder();
    Seg.PathSegment.Builder pSegUp = Seg.PathSegment.newBuilder();
    // pSegUp.setSegmentInfo();
    segUp.addSegments(pSegUp.build());
    respUp.putSegments(Seg.SegmentType.SEGMENT_TYPE_UP_VALUE, segUp.build());
    // TODO cs.addResponse();
  }
}
