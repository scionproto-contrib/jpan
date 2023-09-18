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

import org.scion.proto.daemon.Daemon;

/**
 * A SCION path represents a single path from a source to a destination.
 * Paths can be retrieved from the ScionPathService.
 */
public class ScionPath {
    private final Daemon.Path path;


    ScionPath(Daemon.Path path) {
        this.path = path;
    }

    Daemon.Path getPathInternal() {
        return path;
    }

}
