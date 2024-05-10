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

package org.scion.jpan;

import java.io.IOException;
import java.net.*;

@Deprecated // Please use ScionDatagramChannel instead.
public class DatagramChannel extends ScionDatagramChannel {

  @Deprecated // Please use ScionDatagramChannel instead.
  protected DatagramChannel(ScionService service, java.nio.channels.DatagramChannel channel)
      throws IOException {
    super(service, channel);
  }

  @Deprecated // Please use ScionDatagramChannel.open() instead.
  public static DatagramChannel open() throws IOException {
    return open(null);
  }

  @Deprecated // Please use ScionDatagramChannel.open() instead.
  public static DatagramChannel open(ScionService service) throws IOException {
    return open(service, java.nio.channels.DatagramChannel.open());
  }

  @Deprecated // Please use ScionDatagramChannel.open() instead.
  public static DatagramChannel open(
      ScionService service, java.nio.channels.DatagramChannel channel) throws IOException {
    return new DatagramChannel(service, channel);
  }
}
