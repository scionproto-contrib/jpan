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

package org.scion.internal;

public class HopField {

    private boolean r0;
    private boolean r1;
    private boolean r2;
    private boolean r3;
    private boolean r4;
    private boolean r5;
    private boolean r6;
    //  1 bit : ConsIngress Router Alert. If the ConsIngress Router Alert is set, the ingress router
    //  (in construction direction) will process the L4 payload in the packet.
    private boolean I;
    //  1 bit : ConsEgress Router Alert. If the ConsEgress Router Alert is set, the egress router
    //  (in construction direction) will process the L4 payload in the packet.
    private boolean E;
    //  8 bits : Expiry time of a hop field. The expiration time expressed is relative. An absolute expiration time
    //  in seconds is computed in combination with the timestamp field (from the corresponding info field) as follows:
    //  abs_time = timestamp + (1+expiryTime)*24*60*60/256
    private int expiryTime;
    // 16 bits : consIngress : The 16-bits ingress interface IDs in construction direction.
    private int condIngress;
    // 16 bits : consEgress : The 16-bits egress interface IDs in construction direction.
    private int condEgress;
    // 48 bits : MAC : 6-byte Message Authentication Code to authenticate the hop field.
    // For details on how this MAC is calculated refer to Hop Field MAC Computation:
    // https://scion.docs.anapaya.net/en/latest/protocols/scion-header.html#hop-field-mac-computation
    private int mac;

    public static HopField read(byte[] data, int offset) {
        HopField field = new HopField();
        // TODO read!!!
        return field;
    }


    public int length() {
        return 12;
    }

}
