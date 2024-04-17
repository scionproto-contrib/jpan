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

package org.scion.api;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.*;
import org.scion.internal.MultiMap;

class DatagramSocketApiCompletenessTest {

  @Test
  void testApiCompleteness() {
    // Test that org.scion.DatagramSocket overrides ALL methods of java.net.DatagramSocket
    MultiMap<String, Method> methods = new MultiMap<>();
    for (Method m : org.scion.socket.DatagramSocket.class.getDeclaredMethods()) {
      methods.put(m.getName(), m);
    }

    for (Method required : java.net.DatagramSocket.class.getDeclaredMethods()) {
      if (!Modifier.isPublic(required.getModifiers())) {
        continue;
      }
      List<Method> matches = methods.get(required.getName());
      boolean foundImplementation = false;
      assertFalse(matches.isEmpty(), "Method not found: " + required);
      for (Method implemented : matches) {
        if (required.getParameterCount() == implemented.getParameterCount()) {
          boolean mismatch = false;
          for (int i = 0; i < required.getParameters().length; i++) {
            if (required.getParameterTypes()[i] != implemented.getParameterTypes()[i]) {
              mismatch = true;
              break;
            }
          }
          // TODO verify "synchronized"
          if (!mismatch) {
            foundImplementation = true;
            break;
          }
        }
      }
      assertTrue(foundImplementation, "Method not found: " + required);
    }
  }
}
