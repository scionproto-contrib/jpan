// Copyright 2025 ETH Zurich, Anapaya Systems
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

package org.scion.jpan.ppl;

import java.util.*;
import java.util.function.Consumer;
import org.scion.jpan.ScionUtil;

/**
 * Graph implements a graph of ASes and IfIDs for testing purposes. IfIDs must be globally unique.
 *
 * <p>Nodes are represented by ASes.
 *
 * <p>Edges are represented by pairs of IfIDs.
 */
public class Graph {
  // maps IfIDs to the other IfID of the edge
  private final Map<Integer, Integer> links = new HashMap<>();
  // specifies whether an IfID is on a peering link
  private final Map<Integer, Boolean> isPeer = new HashMap<>();
  // maps IfIDs to the AS they belong to
  private final Map<Integer, Long> parents = new HashMap<>();
  // maps ASes to a structure containing a slice of their IfIDs
  private final Map<Long, AS> ases = new HashMap<>();

  private final Map<Long, Signer> signers = new HashMap<>();

  // New allocates a new empty graph.
  private Graph() {}

  // fromDescription initializes a new graph from description desc.
  static Graph fromDescription(DefaultGen.Description desc) {
    Graph graph = new Graph();
    for (String node : desc.nodes) {
      graph.add(node);
    }
    for (DefaultGen.EdgeDesc edge : desc.edges) {
      graph.addLink(edge.xIA, edge.xIfID, edge.yIA, edge.yIfID, edge.peer);
    }
    return graph;
  }

  // Add adds a new node to the graph. If ia is not a valid string representation
  // of an ISD-AS, Add panics.
  private void add(String ia) {
    long isdas = ScionUtil.parseIA(ia);
    ases.put(isdas, new AS());
    signers.put(
        isdas,
        new Signer(
            SignerOption.withIA(isdas)
            //                withTRCID(cppki.TRCID(
            //            ISD:    isdas.ISD(),
            //                    Serial: 1,
            //                    Base:   1,)
            ));
  }

  // GetSigner returns the signer for the ISD-AS.
  private Signer getSigner(String ia) {
    return signers.get(ScionUtil.parseIA(ia));
  }

  // AddLink adds a new edge between the ASes described by xIA and yIA, with
  // xIfID in xIA and yIfID in yIA. If xIA or yIA are not valid string
  // representations of an ISD-AS, AddLink panics.
  private void addLink(String xIA, int xIfID, String yIA, int yIfID, boolean peer) {
    long x = ScionUtil.parseIA(xIA);
    long y = ScionUtil.parseIA(yIA);
    if (!ases.containsKey(x)) {
      throw new IllegalStateException(String.format("AS %s not in graph", xIA));
    }
    if (!ases.containsKey(y)) {
      throw new IllegalStateException(String.format("AS %s not in graph", yIA));
    }
    if (links.containsKey(xIfID)) {
      throw new IllegalStateException(String.format("IfID %d is not unique", xIfID));
    }
    if (links.containsKey(yIfID)) {
      throw new IllegalStateException(String.format("IfID %d is not unique", yIfID));
    }
    links.put(xIfID, yIfID);
    links.put(yIfID, xIfID);
    isPeer.put(xIfID, peer);
    isPeer.put(yIfID, peer);
    parents.put(xIfID, x);
    parents.put(yIfID, y);
    ases.get(x).ifIDs.put(xIfID, new Object());
    ases.get(y).ifIDs.put(yIfID, new Object());
  }

  // RemoveLink deletes the edge containing ifID from the graph.
  private void removeLink(int ifID) {
    long ia = parents.get(ifID);
    int neighborIfID = links.get(ifID);
    long neighborIA = parents.get(neighborIfID);

    links.remove(ifID);
    links.remove(neighborIfID);
    isPeer.remove(ifID);
    isPeer.remove(neighborIfID);
    parents.remove(ifID);
    parents.remove(neighborIfID);
    ases.get(ia).delete(ifID);
    ases.get(neighborIA).delete(neighborIfID);
  }

  // GetParent returns the parent AS of ifID.
  long getParent(int ifID) {
    return parents.get(ifID);
  }

  // GetPaths returns all the minimum-length paths. If xIA = yIA, a 1-length
  // slice containing an empty path is returned. If no path exists between xIA
  // and yIA, a 0-length slice is returned.
  //
  // Note that this always returns shortest length paths, even if they might not
  // be valid SCION paths.
  List<List<Integer>> getPaths(long src, long dst) {
    int solutionLength = 1000; // Infinity
    Queue<Solution> queue = new ArrayDeque<>();
    queue.add(new Solution(src));

    List<List<Integer>> solution = new ArrayList<>();
    while (true) {
      if (queue.isEmpty()) {
        // Nothing left to explore.
        break;
      }
      // Explore the next element in the queue.
      Solution curSolution = queue.poll();

      if (curSolution.len() > solutionLength) {
        break;
      }

      // If we found the solution, save the length to stop exploring
      // longer paths.
      if (curSolution.currentIA == dst) {
        solutionLength = curSolution.len();
        solution.add(curSolution.trail);
        continue;
      }

      // Explore neighboring ASes, if not visited yet.
      for (int ifID : ases.get(curSolution.currentIA).ifIDs.keySet()) {
        int nextIfID = links.get(ifID);
        long nextIA = parents.get(nextIfID);
        if (curSolution.visited(nextIA)) {
          continue;
        }
        // Copy to avoid mutating the trails of other explorations.
        Solution nextTrail = curSolution.copy();
        nextTrail.add(ifID, nextIfID, nextIA);
        nextTrail.currentIA = nextIA;
        queue.add(nextTrail);
      }
    }
    return solution;
  }

  // SignerOption allows customizing the generated Signer.
  private interface SignerOption extends Consumer<Signer> {

    // WithPrivateKey customizes the private key for the Signer.
    //        static SignerOption withPrivateKey(key crypto.Signer) {
    //            return func(o *Signer) {
    //                o.PrivateKey = key
    //            }
    //        }

    // WithIA customizes the ISD-AS for the Signer.
    static SignerOption withIA(long ia) {
      return o -> o.ia = ia;
    }

    // WithTRCID customizes the TRCID for the Signer.
    //        static SignerOption withTRCID(trcID cppki.TRCID) {
    //            return func(o *Signer) {
    //                o.TRCID = trcID
    //            }
    //        }

    // WithTimestamp customizes the signature timestamp for the Signer.
    //        static SignerOption withTimestamp(ts time.Time) {
    //            return func(o *Signer) {
    //                o.Timestamp = ts
    //            }
    //        }
  }

  private static class Signer {
    //        PrivateKey crypto.Signer
    //        // Timestamp is the timestamp that this signer is bound to. If it is set,
    //        // all signatures are created with this timestamp. If it is not set, the
    //        // current time is used for the signature timestamp.
    //        Timestamp time.Time
    long ia;

    //        TRCID     cppki.TRCID
    //
    Signer(long ia) {
      this.ia = ia;
    }

    Signer(SignerOption... opts) {
      for (SignerOption opt : opts) {
        opt.accept(this);
      }
      //        if s.PrivateKey == nil {
      //            var err error
      //            s.PrivateKey, err = ecdsa.GenerateKey(elliptic.P256(), crand.Reader)
      //            if err != nil {
      //                panic(err)
      //            }
      //        }
      //        return s;
    }
    //
    //    func (s Signer) Sign(ctx context.Context, msg []byte,
    //    associatedData ...[]byte) (*cryptopb.SignedMessage, error) {
    //
    //        var l int
    //        for _, d := range associatedData {
    //            l += len(d)
    //        }
    //        ts := s.Timestamp
    //        if ts.IsZero() {
    //            ts = time.Now()
    //        }
    //        skid, err := cppki.SubjectKeyID(s.PrivateKey.Public())
    //        if err != nil {
    //            return nil, err
    //        }
    //
    //        id := &cppb.VerificationKeyID{
    //            IsdAs:        uint64(s.IA),
    //                    TrcBase:      uint64(s.TRCID.Base),   // nolint - name from published
    // protobuf
    //                    TrcSerial:    uint64(s.TRCID.Serial), // nolint - name from published
    // protobuf
    //                    SubjectKeyId: skid,
    //        }
    //        rawID, err := proto.Marshal(id)
    //        if err != nil {
    //            return nil, err
    //        }
    //
    //        hdr := signed.Header{
    //            SignatureAlgorithm:   signed.ECDSAWithSHA256,
    //                    AssociatedDataLength: l,
    //                    Timestamp:            ts,
    //                    VerificationKeyID:    rawID,
    //        }
    //
    //        return signed.Sign(hdr, msg, s.PrivateKey, associatedData...)
    //    }
  }

  // AS contains a list of all the IfIDs in an AS.
  private static class AS {
    private final Map<Integer, Object> ifIDs = new HashMap<>();

    // Delete removes ifID from as.
    void delete(int ifID) {
      if (ifIDs.remove(ifID) == null) {
        throw new IllegalArgumentException("ifID not found");
      }
    }
  }

  // solution tracks the state of a candidate solution for the graph
  // exploration in graph.GetPaths.
  private static class Solution {
    // current AS in the exploration
    long currentIA;
    // whether the AS has already been visited by this path, to avoid loops
    final Map<Long, Object> visited = new HashMap<>();
    // the trail of IfIDs
    final List<Integer> trail = new ArrayList<>(); // uint16

    Solution(long start) {
      visited.put(start, new Object());
      currentIA = start;
    }

    Solution copy() {
      Solution newS = new Solution(currentIA);
      newS.visited.putAll(visited);
      newS.trail.addAll(trail);
      return newS;
    }

    boolean visited(long ia) {
      return visited.containsKey(ia);
    }

    // Add appends localIfID and nextIfID to the trail, and advances to nextIA.
    void add(int localIfID, int nextIfID, long nextIA) {
      visited.put(nextIA, new Object());
      trail.add(localIfID);
      trail.add(nextIfID);
    }

    int len() {
      return trail.size() / 2;
    }
  }
}
