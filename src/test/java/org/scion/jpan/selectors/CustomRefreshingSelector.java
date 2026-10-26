// Copyright 2026 ETH Zurich
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

package org.scion.jpan.selectors;

import java.util.concurrent.atomic.AtomicInteger;
import org.scion.jpan.PathPolicy;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionSocketAddress;

public class CustomRefreshingSelector extends PathSelectorWithRefresh {

  private final int delayMs;
  private final int intervalMs;

  public static CustomRefreshingSelector createCustom(
      ScionService service, PathPolicy policy, int delayMs, int intervalMs) {
    return new CustomRefreshingSelector(service, policy, delayMs, intervalMs);
  }

  protected CustomRefreshingSelector(
      ScionService service, PathPolicy policy, int delayMs, int intervalMs) {
    super(service, policy);
    this.delayMs = delayMs;
    this.intervalMs = intervalMs;
  }

  @Override
  public void open(ScionSocketAddress remoteAddress) {
    super.open(remoteAddress);
    // apply initial hop count ordering
    AtomicInteger i = new AtomicInteger();
    super.applyRanking(paths -> paths.forEach(e -> e.setRank(i.incrementAndGet())));
    // schedule latency measurement + ordering
    super.addScheduledTask(this::measureAndRankByLatency, delayMs, intervalMs);
  }

  private void measureAndRankByLatency() {
    // Apply reverse ordering
    super.applyRanking(paths -> paths.forEach(e -> e.setRank(-e.getRank())));
  }
}
