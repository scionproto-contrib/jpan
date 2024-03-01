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

/** Scion utility functions. */
public class ScionUtil {

  public static String getPropertyOrEnv(String propertyName, String envName) {
    String value = System.getProperty(propertyName);
    return value != null ? value : System.getenv(envName);
  }

  public static String getPropertyOrEnv(String propertyName, String envName, String defaultValue) {
    String value = getPropertyOrEnv(propertyName, envName);
    return value != null ? value : defaultValue;
  }

  public static boolean getPropertyOrEnv(
      String propertyName, String envName, boolean defaultValue) {
    String value = getPropertyOrEnv(propertyName, envName);
    return value != null ? Boolean.parseBoolean(value) : defaultValue;
  }

  public static int getPropertyOrEnv(String propertyName, String envName, int defaultValue) {
    String value = getPropertyOrEnv(propertyName, envName);
    return value != null ? Integer.parseInt(value) : defaultValue;
  }
}
