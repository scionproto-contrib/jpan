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

package org.scion.jpan.internal.ppl;

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
  private final Map<Integer, Integer> links = new HashMap<>(); // uint16, uint16
  // specifies whether an IfID is on a peering link
  private final Map<Integer, Boolean> isPeer = new HashMap<>(); // map[uint16]bool
  // maps IfIDs to the AS they belong to
  private final Map<Integer, Long> parents = new HashMap<>(); // map[uint16]addr.IA
  // maps ASes to a structure containing a slice of their IfIDs
  private final Map<Long, AS> ases = new HashMap<>(); // map[addr.IA]*AS

  private final Map<Long, Signer> signers = new HashMap<>(); // map[addr.IA]*Signer

  // ctrl *gomock.Controller
  // lock sync.Mutex;

  // New allocates a new empty graph.
  public Graph() { // ctrl *gomock.Controller) *Graph {
    //        return &Graph{
    //            ctrl:    ctrl,
    //                    links:   make(map[uint16]uint16),
    //                    isPeer:  make(map[uint16]bool),
    //                    parents: make(map[uint16]addr.IA),
    //                    ases:    make(map[addr.IA]*AS),
    //                    signers: make(map[addr.IA]*Signer),
    //        }
  }

  // NewFromDescription initializes a new graph from description desc.
  public static Graph NewFromDescription(
      DefaultGen.Description desc) { // ctrl *gomock.Controller, desc *Description) *Graph {
    Graph graph = new Graph();
    for (String node : desc.Nodes) {
      graph.Add(node);
    }
    for (DefaultGen.EdgeDesc edge : desc.Edges) {
      graph.AddLink(edge.Xia, edge.XifID, edge.Yia, edge.YifID, edge.Peer);
    }
    return graph;
  }

  // Add adds a new node to the graph. If ia is not a valid string representation
  // of an ISD-AS, Add panics.
  private void Add(String ia) {
    // g.lock.Lock()
    // defer g.lock.Unlock()
    long isdas = ScionUtil.parseIA(ia);
    ases.put(isdas, new AS());
    signers.put(
        isdas,
        new Signer(
            SignerOption.WithIA(isdas)
            //                WithTRCID(cppki.TRCID(
            //            ISD:    isdas.ISD(),
            //                    Serial: 1,
            //                    Base:   1,)
            ));
  }

  // GetSigner returns the signer for the ISD-AS.
  private Signer GetSigner(String ia) {
    //        g.lock.Lock()
    //        defer g.lock.Unlock()
    return signers.get(ScionUtil.parseIA(ia));
  }

  // AddLink adds a new edge between the ASes described by xIA and yIA, with
  // xIfID in xIA and yIfID in yIA. If xIA or yIA are not valid string
  // representations of an ISD-AS, AddLink panics.
  private void AddLink(String xIA, int xIfID, String yIA, int yIfID, boolean peer) {

    // g.lock.Lock()
    // defer g.lock.Unlock()
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
    ases.get(x).IfIDs.put(xIfID, new Object()); // struct{}{}
    ases.get(y).IfIDs.put(yIfID, new Object()); // = struct{}{}
  }

  // RemoveLink deletes the edge containing ifID from the graph.
  private void RemoveLink(int ifID) {
    // g.lock.Lock()
    // defer g.lock.Unlock()
    long ia = parents.get(ifID);
    int neighborIfID = links.get(ifID);
    long neighborIA = parents.get(neighborIfID);

    links.remove(ifID);
    links.remove(neighborIfID);
    isPeer.remove(ifID);
    isPeer.remove(neighborIfID);
    parents.remove(ifID);
    parents.remove(neighborIfID);
    ases.get(ia).Delete(ifID);
    ases.get(neighborIA).Delete(neighborIfID);
  }

  // GetParent returns the parent AS of ifID.
  long GetParent(int ifID) {
    // g.lock.Lock()
    // defer g.lock.Unlock()
    return parents.get(ifID);
  }

  // GetPaths returns all the minimum-length paths. If xIA = yIA, a 1-length
  // slice containing an empty path is returned. If no path exists between xIA
  // and yIA, a 0-length slice is returned.
  //
  // Note that this always returns shortest length paths, even if they might not
  // be valid SCION paths.
  //    List<List<Integer>> GetPaths(String xIA, String yIA) {
  List<List<Integer>> GetPaths(long src, long dst) {
    // g.lock.Lock()
    // defer g.lock.Unlock() // TODO
    //        long src = ScionUtil.parseIA(xIA);
    //        long dst = ScionUtil.parseIA(yIA);
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

      if (curSolution.Len() > solutionLength) {
        break;
      }

      // If we found the solution, save the length to stop exploring
      // longer paths.
      if (curSolution.CurrentIA == dst) {
        solutionLength = curSolution.Len();
        solution.add(curSolution.trail);
        continue;
      }

      // Explore neighboring ASes, if not visited yet.
      for (Integer ifID : ases.get(curSolution.CurrentIA).IfIDs.keySet()) {
        int nextIfID = links.get(ifID);
        long nextIA = parents.get(nextIfID);
        if (curSolution.Visited(nextIA)) {
          continue;
        }
        // Copy to avoid mutating the trails of other explorations.
        Solution nextTrail = curSolution.Copy();
        nextTrail.Add(ifID, nextIfID, nextIA);
        nextTrail.CurrentIA = nextIA;
        queue.add(nextTrail);
      }
    }
    return solution;
  }

  // SignerOption allows customizing the generated Signer.
  private static interface SignerOption extends Consumer<Signer> {
    // func(o *Signer)

    // WithPrivateKey customizes the private key for the Signer.
    //        static SignerOption WithPrivateKey(key crypto.Signer) {
    //            return func(o *Signer) {
    //                o.PrivateKey = key
    //            }
    //        }

    // WithIA customizes the ISD-AS for the Signer.
    static SignerOption WithIA(long ia) {
      return (o) -> o.IA = ia;
    }

    // WithTRCID customizes the TRCID for the Signer.
    //        static SignerOption WithTRCID(trcID cppki.TRCID) {
    //            return func(o *Signer) {
    //                o.TRCID = trcID
    //            }
    //        }

    // WithTimestamp customizes the signature timestamp for the Signer.
    //        static SignerOption WithTimestamp(ts time.Time) {
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
    long IA;

    //        TRCID     cppki.TRCID
    //
    Signer(long ia) {
      IA = ia;
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
    private final Map<Integer, Object> IfIDs = new HashMap<>(); // map[uint16]struct{}

    // Delete removes ifID from as.
    void Delete(int ifID) {
      if (IfIDs.remove(ifID) == null) {
        throw new IllegalArgumentException("ifID not found");
      }
    }
  }

  // solution tracks the state of a candidate solution for the graph
  // exploration in graph.GetPaths.
  private static class Solution {
    // current AS in the exploration
    long CurrentIA;
    // whether the AS has already been visited by this path, to avoid loops
    // visited map[addr.IA]struct{} // TODO
    final Map<Long, Object> visited = new HashMap<>();
    // the trail of IfIDs
    final List<Integer> trail = new ArrayList<>(); // uint16

    Solution(long start) {
      visited.put(start, new Object());
      CurrentIA = start;
    }

    Solution Copy() {
      if (this == null) {
        return null;
      }
      Solution newS = new Solution(CurrentIA);
      // newS.CurrentIA = CurrentIA;
      // newS.visited = make(map[addr.IA]struct{})
      newS.visited.putAll(visited);
      //            for (long ia : visited.keySet()) {
      //                newS.visited.put(ia, new Object());
      //            }
      newS.trail.addAll(trail);
      return newS;
    }

    boolean Visited(long ia) {
      return visited.containsKey(ia);
    }

    // Add appends localIfID and nextIfID to the trail, and advances to nextIA.
    void Add(int localIfID, int nextIfID, long nextIA) {
      visited.put(nextIA, new Object());
      trail.add(localIfID);
      trail.add(nextIfID);
    }

    int Len() {
      return trail.size() / 2;
    }
  }
}
