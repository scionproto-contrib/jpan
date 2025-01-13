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

package org.scion.jpan.internal.ppl;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.scion.jpan.Path;
import org.scion.jpan.Scion;

public class LocalIsdAs {
  // LocalISDAS is a path policy that checks whether the first hop in the path (local AS) belongs
  // to the specified set.
  long[] allowedIAs;

  List<Path> eval(List<Path> paths) {
    List<Path> result = new ArrayList<>();
    for (Path path : paths) {
      long pathSource = Scion.defaultService().getLocalIsdAs();
      // RequestPath path2 = (RequestPath) path; // TODO
      if (pathSource == path.getRemoteIsdAs()) {
        continue;
      }
      // TODO use Set<>?
      for (long allowedIA : allowedIAs) {
        if (pathSource == allowedIA) {
          result.add(path);
          break;
        }
      }
    }
    return result;
  }

  JsonWriter MarshalJSON(JsonWriter json) throws IOException {
    for (long l : allowedIAs) {
      json.value(l);
    }
    return json;
  }

  JsonReader UnmarshalJSON(JsonReader json) {
    // TODO json.peek().Unmarshal(b, &li.AllowedIAs);
    return json;
  }
}
