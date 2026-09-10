/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import org.cobblestonemc.api.TraversalState;

/**
 * A test {@link Mode} laying out a short corridor that stops at a dead end, where the last step is
 * <b>mode-restricted</b> — the shape a mining move has when an integration may forbid breaking the
 * block.
 *
 * <p>The corridor runs {@code (0,0,0) → (1,0,0) → … → (deadEnd,0,0)} at cost 1 a step, and offers
 * nothing beyond the dead end. Only the edge arriving at the dead end carries a restriction; every
 * other step is unconditional.
 */
final class DeadEndMode implements Mode<TestAgent, TestStep, TestDomain> {

  private final int deadEndX;
  private final Supplier<FutureOr<Boolean>> deadEndBarred;

  DeadEndMode(int deadEndX, Supplier<FutureOr<Boolean>> deadEndBarred) {
    this.deadEndX = deadEndX;
    this.deadEndBarred = deadEndBarred;
  }

  @Override
  public FutureOr<Collection<Movement<TestStep>>> step(
      TestAgent agent, Cell from, TestDomain domain, TraversalState state, Cell destination) {
    if (from.x() >= deadEndX) {
      return FutureOr.of(List.of()); // the dead end goes nowhere
    }
    Cell next = from.plus(1, 0, 0);
    boolean arrivesAtDeadEnd = next.x() == deadEndX;
    return FutureOr.of(
        List.of(
            new Movement<>(
                next, 1.0, 1.0, TestStep.MOVE, state, arrivesAtDeadEnd ? deadEndBarred : null)));
  }
}
