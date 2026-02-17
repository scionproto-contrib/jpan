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

package org.scion.jpan.internal.util;

import static org.scion.jpan.Constants.*;

import org.scion.jpan.Constants;
import org.scion.jpan.ScionUtil;

public class Config {

  private Config() {}

  public static int getControlPlaneTimeoutMs() {
    double milliSeconds =
        ScionUtil.getPropertyOrEnv(
            PROPERTY_CONTROL_PLANE_TIMEOUT_MS,
            ENV_CONTROL_PLANE_TIMEOUT_MS,
            DEFAULT_CONTROL_PLANE_TIMEOUT_MS);
    return (int) (milliSeconds);
  }

  public static String getPathService() {
    return ScionUtil.getPropertyOrEnv(PROPERTY_BOOTSTRAP_PATH_SERVICE, ENV_BOOTSTRAP_PATH_SERVICE);
  }

  public static String getNat() {
    return ScionUtil.getPropertyOrEnv(PROPERTY_NAT, ENV_NAT, DEFAULT_NAT).toUpperCase();
  }

  public static boolean useNatMappingKeepAlive() {
    return ScionUtil.getPropertyOrEnv(
        PROPERTY_NAT_MAPPING_KEEPALIVE, ENV_NAT_MAPPING_KEEPALIVE, DEFAULT_NAT_MAPPING_KEEPALIVE);
  }

  public static int getNatMappingTimeoutMs() {
    double seconds =
        ScionUtil.getPropertyOrEnv(
            PROPERTY_NAT_MAPPING_TIMEOUT, ENV_NAT_MAPPING_TIMEOUT, DEFAULT_NAT_MAPPING_TIMEOUT);
    return (int) (seconds * 1000.);
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

  public static int getPathExpiryMarginSeconds() {
    return ScionUtil.getPropertyOrEnv(
        PROPERTY_PATH_EXPIRY_MARGIN, ENV_PATH_EXPIRY_MARGIN, DEFAULT_PATH_EXPIRY_MARGIN);
  }

  public static int getPathPollingIntervalSeconds() {
    return ScionUtil.getPropertyOrEnv(
        PROPERTY_PATH_POLLING_INTERVAL_SEC,
        ENV_PATH_POLLING_INTERVAL_SEC,
        DEFAULT_PATH_POLLING_INTERVAL);
  }
}
