// Copyright 2025 ETH Zurich
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

import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.ProtocolException;
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;
import org.scion.jpan.Scmp;

public class DefaultErrorHandler implements ScmpErrorHandler {

  private final PathProvider pathProvider;

  public static DefaultErrorHandler create(PathProvider pathProvider) {
    return new DefaultErrorHandler(pathProvider);
  }

  private DefaultErrorHandler(PathProvider pathProvider) {
    this.pathProvider = pathProvider;
  }

  public void handle(Scmp.ErrorMessage error, boolean isConnected) throws IOException {
    switch (error.getTypeCode().type()) {
      case ERROR_1:
        if (error.getTypeCode() == Scmp.TypeCode.TYPE_1_CODE_4) {
          throw new PortUnreachableException(error.toString());
        }
        throw new NoRouteToHostException(error.toString());
      case ERROR_2:
      case ERROR_4:
        throw new ProtocolException(error.toString());
      case ERROR_5:
      case ERROR_6:
        if (isConnected) {
          reportFaultyPaths(error);
        } else {
          // We throw an exception here.
          // Alternatively, we could just swallow the error, after all this is an unreliable
          // protocol...
          throw new NoRouteToHostException(error.toString());
        }
        break;
      default:
        // ignore
    }
  }

  private void reportFaultyPaths(Scmp.ErrorMessage error) {
    long faultyIsdAs;
    long ifId1;
    Long ifId2;
    if (error instanceof Scmp.Error5Message) {
      Scmp.Error5Message error5 = (Scmp.Error5Message) error;
      faultyIsdAs = error5.getIsdAs();
      ifId1 = error5.getInterfaceId();
      ifId2 = null;
    } else if (error instanceof Scmp.Error6Message) {
      Scmp.Error6Message error6 = (Scmp.Error6Message) error;
      faultyIsdAs = error6.getIsdAs();
      ifId1 = error6.getIngressId();
      ifId2 = error6.getEgressId();
    } else {
      return;
    }

    pathProvider.reprioritizePaths(
        path -> {
          double conf = confidence(path, faultyIsdAs, ifId1);
          if (ifId2 != null && conf > 0) {
            conf *= confidence(path, faultyIsdAs, ifId2);
          }
          return conf;
        });
  }

  private double confidence(Path path, long faultyIsdAs, long faultyIfId) {
    PathMetadata meta = path.getMetadata();
    int nInterfaces = meta.getInterfacesList().size();
    for (int i = 0; i < nInterfaces; i++) {
      PathMetadata.PathInterface pIf = meta.getInterfacesList().get(i);
      if (pIf.getIsdAs() == faultyIsdAs && pIf.getId() == faultyIfId) {
        return 0;
      }
    }
    return 1;
  }
}
