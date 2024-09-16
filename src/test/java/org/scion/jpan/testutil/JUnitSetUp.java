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

package org.scion.jpan.testutil;

import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.scion.jpan.Constants;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Scion;

public class JUnitSetUp
    implements BeforeAllCallback, BeforeEachCallback, ExtensionContext.Store.CloseableResource {
  private static boolean started = false;
  private static boolean failed = false;

  @Override
  public void beforeAll(ExtensionContext context) {
    if (!started) {
      started = true;

      // Ignore environment variables
      PackageVisibilityHelper.setIgnoreEnvironment(true);

      // Check for daemon
      try (ServerSocket ignored = new java.net.ServerSocket(30255)) {
        // empty
      } catch (IOException e) {
        failed = true;
        throw new IllegalStateException("JUnit tests cannot run while port 30255 is in use.");
      }

      // Check for control server
      try (ServerSocket ignored = new java.net.ServerSocket(31000)) {
        // empty
      } catch (IOException e) {
        failed = true;
        throw new IllegalStateException("JUnit tests cannot run while port 31000 is in use.");
      }

      context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).put("SCION JUnit global", this);
    }
  }

  @Override
  public void close() {
    // System.out.println("Singleton::Finish-Once");
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    System.setProperty(Constants.PROPERTY_HOSTS_FILES, "....invalid-dummy-filename");

    Scion.closeDefault();
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_TOPO_FILE);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_NAPTR_NAME);
    System.clearProperty(Constants.PROPERTY_BOOTSTRAP_HOST);
    System.clearProperty(Constants.PROPERTY_DAEMON);
    System.clearProperty(Constants.PROPERTY_DNS_SEARCH_DOMAINS);
    System.clearProperty(Constants.PROPERTY_HOSTS_FILES);
    System.setProperty(Constants.PROPERTY_USE_OS_SEARCH_DOMAINS, "false");
    if (failed) {
      System.exit(1);
    }
  }
}
