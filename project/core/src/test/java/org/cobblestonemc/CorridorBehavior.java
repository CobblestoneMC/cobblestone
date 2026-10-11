/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.api.TraversalState;

/**
 * A test {@link MovementBehavior} that always offers a single move of {@code +1} in x at cost 1 — a
 * one-dimensional corridor.
 *
 * <p>When {@code gated}, each {@code movements} call returns a <b>pending</b> {@link FutureOr} that
 * the test completes on demand via {@link #releaseNext()}, exercising the search's park/resume
 * machinery. Otherwise it returns immediate results (the cache-hit fast path).
 */
final class CorridorBehavior implements MovementBehavior<TestAgent, TestStep, TestDomain> {

  private final boolean gated;
  private final List<Gate> gates = new ArrayList<>();
  private int released;

  CorridorBehavior(boolean gated) {
    this.gated = gated;
  }

  @Override
  public FutureOr<Collection<Movement<TestStep>>> movements(
      TestAgent agent, Cell from, TestDomain domain, TraversalState state, Cell goal) {
    Collection<Movement<TestStep>> movements =
        List.of(new Movement<>(from.plus(1, 0, 0), 1.0, 1.0, TestStep.MOVE, state));
    if (!gated) {
      return FutureOr.of(movements);
    }
    CompletableFuture<Collection<Movement<TestStep>>> future = new CompletableFuture<>();
    gates.add(new Gate(future, movements));
    return FutureOr.ofFuture(future);
  }

  int pendingCount() {
    return gates.size() - released;
  }

  void releaseNext() {
    Gate gate = gates.get(released++);
    gate.future().complete(gate.movements());
  }

  private record Gate(
      CompletableFuture<Collection<Movement<TestStep>>> future,
      Collection<Movement<TestStep>> movements) {}
}
