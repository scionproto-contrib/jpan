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


import java.net.*;

public class ScionSocketAddress extends InetSocketAddress {
  private final long isdAs;
  private ScionPath path;
  private ScionPacketHelper helper;

  private ScionSocketAddress(ScionPacketHelper scionPacketHelper, long isdAs,
                             String hostName, int port, ScionPath path) {
    // TODO this probably causes a DNS lookup, can we avoid that? Check!
    super(hostName, port);
    this.isdAs = isdAs;
    this.path = path;
    this.helper = scionPacketHelper;
  }

  public static ScionSocketAddress create(String isdAs, String hostName,
                                          int port) {
    long isdAsCode = ScionUtil.ParseIA(isdAs);
    return new ScionSocketAddress(null, isdAsCode, hostName, port, null);
  }

  public static ScionSocketAddress create(String hostName,
                                          int port, ScionPath path) {
    return new ScionSocketAddress(null, path.getDestinationCode(), hostName, port, path);
  }

  @Deprecated
  static ScionSocketAddress create(ScionPacketHelper scionPacketHelper,
                                   long isdAs, String hostName, int port) {
    return new ScionSocketAddress(scionPacketHelper, isdAs, hostName, port, null);
  }

  private static ScionSocketAddress createUnresolved() {
    // We hide the public static method from InetSocketAddress
    throw new UnsupportedOperationException();
  }

  public static ScionSocketAddress create(InetSocketAddress address) {
    ScionAddress addr = ScionService.defaultService().getScionAddress(address.getHostString());
    return new ScionSocketAddress(null, addr.getIsdAs(), addr.getHostName(), address.getPort(), addr.getPath());

  }

  public long getIsdAs() {
    return isdAs;
  }

  public int getIsd() {
    return ScionUtil.extractIsd(isdAs);
  }

  void setHelper(ScionPacketHelper scionPacketHelper) {
    if (this.helper != null) {
      throw new IllegalStateException();
    }
    this.helper = scionPacketHelper;
  }

  ScionPacketHelper getHelper() {
    if (helper == null) {
      helper = new ScionPacketHelper(this, ScionPacketHelper.PathState.NO_PATH); // TODO this is weird...
    }
    return helper;
  }

  public ScionPath getPath() {
    return path;
  }

  public void setPath(ScionPath path) {
    this.path = path;
  }

  public boolean hasPath() {
    return path != null;
  }

}
