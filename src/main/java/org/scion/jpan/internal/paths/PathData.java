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

package org.scion.jpan.internal.paths;

public class PathData {

  //    // The raw data-plane path.
  //    byte[] raw;
  //    // Interface for exiting the local AS using this path.
  //    Interface interf;
  //    // The list of interfaces the path is composed of.
  //    List<PathInterface> interfaces;
  //    // The maximum transmission unit (MTU) on the path.
  //    int mtu;
  //    // The point in time when this path expires. In seconds since UNIX epoch.
  //    Timestamp expiration;
  //    // Latency lists the latencies between any two consecutive interfaces.
  //    // Entry i describes the latency between interface i and i+1.
  //    // Consequently, there are N-1 entries for N interfaces.
  //    // A negative value indicates that the AS did not announce a latency for
  //    // this hop.
  //    Duration latency;
  //    // Bandwidth lists the bandwidth between any two consecutive interfaces, in
  //    // Kbit/s.
  //    // Entry i describes the bandwidth between interfaces i and i+1.
  //    // A 0-value indicates that the AS did not announce a bandwidth for this
  //    // hop.
  //    List<Long> bandwidth;
  //    // Geo lists the geographical position of the border routers along the
  //    // path.
  //    // Entry i describes the position of the router for interface i.
  //    // A 0-value indicates that the AS did not announce a position for this
  //    // router.
  //    List<GeoCoordinates> geo;
  //    // LinkType contains the announced link type of inter-domain links.
  //    // Entry i describes the link between interfaces 2*i and 2*i+1.
  //    List<LinkType> link_type;
  //    // InternalHops lists the number of AS internal hops for the ASes on path.
  //    // Entry i describes the hop between interfaces 2*i+1 and 2*i+2 in the same
  //    // AS.
  //    // Consequently, there are no entries for the first and last ASes, as these
  //    // are not traversed completely by the path.
  //    List<Integer> internal_hops;
  //    // Notes contains the notes added by ASes on the path, in the order of
  //    // occurrence.
  //    // Entry i is the note of AS i on the path.
  //    List<String> notes;
  //    // EpicAuths contains the EPIC authenticators used to calculate the PHVF and LHVF.
  //    List<EpicAuths> epic_auths;

}
