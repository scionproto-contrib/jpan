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

package org.scion.jpan.internal.paths;

import static com.ibm.icu.text.PluralRules.Operand.e;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.scion.jpan.ScionRuntimeException;
import org.scion.jpan.internal.bootstrap.LocalAS;
import org.scion.jpan.internal.util.Config;
import org.scion.jpan.proto.endhost.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PathServiceRpc {

  private static final Logger LOG = LoggerFactory.getLogger(PathServiceRpc.class.getName());

  private final List<PathService> services = new ArrayList<>();
  private final int deadLineMs;

  public static PathServiceRpc create(LocalAS localAS) {
    return new PathServiceRpc(localAS);
  }

  private PathServiceRpc(LocalAS localAS) {
    this.deadLineMs = Config.getControlPlaneTimeoutMs();
    for (LocalAS.ServiceNode node : localAS.getControlServices()) {
      services.add(new PathService(node.getIpString()));
    }
  }

  public void close() {
    services.forEach(PathService::close);
  }

  public synchronized Path.ListSegmentsResponse segments(long srcIA, long dstIA) {
    Path.ListSegmentsRequest protoRequest =
        Path.ListSegmentsRequest.newBuilder().setSrcIsdAs(srcIA).setDstIsdAs(dstIA).build();
    RequestBody requestBody = RequestBody.create(protoRequest.toByteArray());

    String error = "No control services found in topology";
    for (int i = 0; i < services.size(); i++) {
      PathService ps = services.get(0); // Always get the first one!
      ps.init();
      Request request =
          new Request.Builder()
              .url("http://" + ps.address + "/scion.endhost.v1.PathService/ListPaths")
              .addHeader("Content-type", "application/proto")
              //            .addHeader("User-Agent", "OkHttp Bot")
              .post(requestBody)
              .build();

      try (Response response = ps.httpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          ResponseBody body = response.body();
          String str = body != null ? body.string() : null;
          if (response.code() == 400) {
            String msg = "Error while requesting segments: " + srcIA + " -> " + dstIA;
            msg += " -> BAD_REQUEST: " + response.message();
            LOG.info(msg);
            throw new ScionRuntimeException(msg);
          }
          if (response.code() == 500) {
            String msg = "Error while requesting segments: " + srcIA + " -> " + dstIA;
            msg += " -> INTERNAL_SERVER_ERROR -> failed (AS unreachable?): " + response.message();
            LOG.info(msg);
            throw new ScionRuntimeException(msg);
          }

          LOG.warn(
              "While connecting path service {}: code={} msg={}", ps.address, response.code(), str);
          throw new IOException("Unexpected code " + response.code() + ": " + str);
        }
        return Path.ListSegmentsResponse.newBuilder().mergeFrom(response.body().bytes()).build();
      } catch (IOException e) {
        error = e.getMessage();
        LOG.warn("Error connecting path service {}: {}", ps.address, error);
        ps.close();
        // Move CS to end of list
        services.add(services.remove(0));
      }
    }

    throw new ScionRuntimeException(
        "Error while connecting to SCION network, no path service available: " + error);
  }

  private static class PathService {
    private final String address;
    private OkHttpClient httpClient;

    public PathService(String address) {
      this.address = address;
    }

    void init() {
      if (httpClient != null) {
        return;
      }
      LOG.info("Bootstrapping with path service: {}", address);
      httpClient = new OkHttpClient();
    }

    void close() {
      if (httpClient != null) {
        httpClient.dispatcher().cancelAll();
        httpClient.connectionPool().evictAll();
        httpClient = null;
      }
    }
  }
}
