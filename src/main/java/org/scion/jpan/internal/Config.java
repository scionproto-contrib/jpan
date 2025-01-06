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

import static org.scion.jpan.Constants.*;

import org.scion.jpan.Constants;
import org.scion.jpan.ScionUtil;

public class Config {

  public static String getNat() {
    return ScionUtil.getPropertyOrEnv(PROPERTY_NAT, ENV_NAT, DEFAULT_NAT).toUpperCase();
  }

  public static boolean useNatMappingKeepAlive() {
    return ScionUtil.getPropertyOrEnv(
        PROPERTY_NAT_MAPPING_KEEPALIVE, ENV_NAT_MAPPING_KEEPALIVE, DEFAULT_NAT_MAPPING_KEEPALIVE);
  }

  public static int getNatMappingTimeout() {
    return ScionUtil.getPropertyOrEnv(
        PROPERTY_NAT_MAPPING_TIMEOUT, ENV_NAT_MAPPING_TIMEOUT, DEFAULT_NAT_MAPPING_TIMEOUT);
  }

  public static String getNatStunServer() {
    return ScionUtil.getPropertyOrEnv(PROPERTY_NAT_STUN_SERVER, ENV_NAT_STUN_SERVER);
  }

  public static int getStunTimeoutMs() {
    return ScionUtil.getPropertyOrEnv(
        Constants.PROPERTY_NAT_STUN_TIMEOUT_MS,
        Constants.ENV_NAT_STUN_TIMEOUT_MS,
        Constants.DEFAULT_NAT_STUN_TIMEOUT_MS);
  }
}
