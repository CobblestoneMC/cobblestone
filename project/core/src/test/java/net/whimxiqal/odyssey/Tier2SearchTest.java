/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.whimxiqal.odyssey.api.TraversalState;
import org.junit.jupiter.api.Test;

class Tier2SearchTest {

  private static final TestDomain DOMAIN = new TestDomain("test");

  private VirtualPath<TestStep, TestDomain> virtualPath(Cell from, Cell target) {
    return new VirtualPath<>(from, DOMAIN, new CellRegion<>(target, DOMAIN), TraversalState.DEFAULT);
  }

  @Test
  void immediateModeSolvesWithoutParking() {
    CorridorMode mode = new CorridorMode(false);
    Tier2Search<TestAgent, TestStep, TestDomain> search = new Tier2Search<>(
        new TestAgent(), virtualPath(new Cell(0, 0, 0), new Cell(3, 0, 0)),
        List.of(mode), Heuristics.zero(), 1000, () -> false, Runnable::run);

    CompletableFuture<Tier2Result<TestStep, TestDomain>> future = search.solve();

    assertTrue(future.isDone(), "an all-immediate solve should complete synchronously");
    Tier2Result<TestStep, TestDomain> result = future.getNow(null);
    assertTrue(result.solved());
    assertEquals(3.0, result.cost(), 1e-9);
    assertEquals(3, result.steps().size());
  }

  @Test
  void parksUntilBlocksArriveThenResumesToSolution() {
    CorridorMode mode = new CorridorMode(true);
    Tier2Search<TestAgent, TestStep, TestDomain> search = new Tier2Search<>(
        new TestAgent(), virtualPath(new Cell(0, 0, 0), new Cell(2, 0, 0)),
        List.of(mode), Heuristics.zero(), 1000, () -> false, Runnable::run);

    CompletableFuture<Tier2Result<TestStep, TestDomain>> future = search.solve();

    // Parked on the first expansion's pending block — not done yet.
    assertFalse(future.isDone());
    assertEquals(1, mode.pendingCount());

    // Deliver the (0,0,0) blocks; the search resumes, expands (1,0,0), and parks again.
    mode.releaseNext();
    assertFalse(future.isDone());
    assertEquals(1, mode.pendingCount());

    // Deliver the (1,0,0) blocks; the search reaches (2,0,0) and completes.
    mode.releaseNext();
    assertTrue(future.isDone());
    Tier2Result<TestStep, TestDomain> result = future.getNow(null);
    assertTrue(result.solved());
    assertEquals(2.0, result.cost(), 1e-9);
    assertEquals(2, result.steps().size());
  }

  @Test
  void reportsUnreachableWhenNoMovesAndNotAtTarget() {
    Tier2Search<TestAgent, TestStep, TestDomain> search = new Tier2Search<>(
        new TestAgent(), virtualPath(new Cell(0, 0, 0), new Cell(5, 0, 0)),
        List.of(), Heuristics.zero(), 1000, () -> false, Runnable::run);

    Tier2Result<TestStep, TestDomain> result = search.solve().getNow(null);

    assertFalse(result.solved());
  }

  @Test
  void startAlreadyInTargetSolvesWithZeroSteps() {
    Tier2Search<TestAgent, TestStep, TestDomain> search = new Tier2Search<>(
        new TestAgent(), virtualPath(new Cell(7, 0, 0), new Cell(7, 0, 0)),
        List.of(new CorridorMode(false)), Heuristics.zero(), 1000, () -> false, Runnable::run);

    Tier2Result<TestStep, TestDomain> result = search.solve().getNow(null);

    assertTrue(result.solved());
    assertEquals(0.0, result.cost(), 1e-9);
    assertTrue(result.steps().isEmpty());
  }
}
