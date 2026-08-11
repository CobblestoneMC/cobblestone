/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.api.TraversalState;
import org.junit.jupiter.api.Test;

class Tier2SearchTest {

  private static final TestDomain DOMAIN = new TestDomain("test");

  private VirtualPath<TestStep, TestDomain> virtualPath(Cell from, Cell target) {
    return new VirtualPath<>(
        from, DOMAIN, new CellRegion<>(target, DOMAIN), TraversalState.DEFAULT);
  }

  @Test
  void immediateModeSolvesWithoutParking() {
    CorridorMode mode = new CorridorMode(false);
    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestOdysseyLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(3, 0, 0)),
            List.of(mode),
            List.of(),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

    CompletableFuture<Tier2Result<TestStep, TestDomain>> future = search.solve();

    assertTrue(future.isDone(), "an all-immediate solve should complete synchronously");
    Tier2Result<TestStep, TestDomain> result = future.getNow(null);
    assertInstanceOf(Tier2Result.Solved.class, result);
    assertEquals(3.0, ((Tier2Result.Solved<TestStep, TestDomain>) result).cost(), 1e-9);
    assertEquals(3, ((Tier2Result.Solved<TestStep, TestDomain>) result).steps().size());
  }

  @Test
  void parksUntilBlocksArriveThenResumesToSolution() {
    CorridorMode mode = new CorridorMode(true);
    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestOdysseyLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(2, 0, 0)),
            List.of(mode),
            List.of(),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

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
    assertInstanceOf(Tier2Result.Solved.class, result);
    assertEquals(2.0, ((Tier2Result.Solved<TestStep, TestDomain>) result).cost(), 1e-9);
    assertEquals(2, ((Tier2Result.Solved<TestStep, TestDomain>) result).steps().size());
  }

  @Test
  void reportsUnreachableWhenNoMovesAndNotAtTarget() {
    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestOdysseyLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(5, 0, 0)),
            List.of(),
            List.of(),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

    Tier2Result<TestStep, TestDomain> result = search.solve().getNow(null);

    assertInstanceOf(Tier2Result.Failed.class, result);
  }

  @Test
  void startAlreadyInTargetSolvesWithZeroSteps() {
    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestOdysseyLogger(),
            new TestAgent(),
            virtualPath(new Cell(7, 0, 0), new Cell(7, 0, 0)),
            List.of(new CorridorMode(false)),
            List.of(),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

    Tier2Result<TestStep, TestDomain> result = search.solve().getNow(null);

    assertInstanceOf(Tier2Result.Solved.class, result);
    assertEquals(0.0, ((Tier2Result.Solved<TestStep, TestDomain>) result).cost(), 1e-9);
    assertTrue(((Tier2Result.Solved<TestStep, TestDomain>) result).steps().isEmpty());
  }

  @Test
  void immediateRestrictionSeversPathAndReportsUnreachable() {
    // The corridor runs 0→1→2→3, but (2,0,0) is barred, so the target can never be reached.
    Restriction<TestAgent, TestDomain> barTwo =
        (agent, cell, domain) -> FutureOr.of(cell.equals(new Cell(2, 0, 0)));
    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestOdysseyLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(3, 0, 0)),
            List.of(new CorridorMode(false)),
            List.of(barTwo),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

    Tier2Result<TestStep, TestDomain> result = search.solve().getNow(null);

    assertInstanceOf(Tier2Result.Failed.class, result);
  }

  @Test
  void asyncImpassableReSolvesAndReportsUnreachable() {
    // The corridor 0→1→2→3 is expanded optimistically past (2,0,0) while its verdict is pending;
    // when it returns impassable, the solve walls it off, re-solves, and finds no route.
    CompletableFuture<Boolean> gate = new CompletableFuture<>();
    Restriction<TestAgent, TestDomain> barTwoWhenReady =
        (agent, cell, domain) ->
            cell.equals(new Cell(2, 0, 0)) ? FutureOr.ofFuture(gate) : FutureOr.of(false);
    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestOdysseyLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(3, 0, 0)),
            List.of(new CorridorMode(false)),
            List.of(barTwoWhenReady),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

    CompletableFuture<Tier2Result<TestStep, TestDomain>> future = search.solve();
    assertFalse(future.isDone(), "reached the goal optimistically; awaiting (2,0,0)'s verdict");

    gate.complete(true); // (2,0,0) is impassable → wall it off, re-solve → unreachable
    assertTrue(future.isDone());
    assertInstanceOf(Tier2Result.Failed.class, future.getNow(null));
  }

  @Test
  void repairReParentsToRetainedAlternativeWhenTheBetterRouteIsBarred() {
    // Diamond: A→B (cheap) and A→C (same), both reach D, then D→G. B's cell is barred
    // asynchronously,
    // so the repair must re-parent D to the retained (costlier) route through C rather than lose
    // it.
    CompletableFuture<Boolean> gate = new CompletableFuture<>();
    Restriction<TestAgent, TestDomain> barB =
        (agent, cell, domain) ->
            cell.equals(new Cell(1, 0, 0)) ? FutureOr.ofFuture(gate) : FutureOr.of(false);
    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestOdysseyLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(3, 0, 0)),
            List.of(new DiamondMode()),
            List.of(barB),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

    CompletableFuture<Tier2Result<TestStep, TestDomain>> future = search.solve();
    assertFalse(future.isDone(), "reached the goal optimistically through B; awaiting B's verdict");

    gate.complete(true); // B barred → D re-parents to the C route, G still reached
    assertTrue(future.isDone());
    Tier2Result<TestStep, TestDomain> result = future.getNow(null);
    assertInstanceOf(Tier2Result.Solved.class, result);
    assertEquals(
        5.0,
        ((Tier2Result.Solved<TestStep, TestDomain>) result).cost(),
        1e-9); // A→C (1) → D (3) → G (1); the B route would have been 3
    assertEquals(3, ((Tier2Result.Solved<TestStep, TestDomain>) result).steps().size());
  }

  /**
   * A hand-built graph: {@code A(0,0,0)} branches to {@code B(1,0,0)} and {@code C(1,1,0)} (cost 1
   * each); both reach {@code D(2,0,0)} — cheaply via B (1), dearly via C (3) — and {@code
   * D→G(3,0,0)} (1). Lets a test bar B and check D re-parents to the retained C route.
   */
  private static final class DiamondMode implements Mode<TestAgent, TestStep, TestDomain> {
    @Override
    public FutureOr<Collection<Movement<TestStep>>> step(
        TestAgent agent, Cell from, TestDomain domain, TraversalState state) {
      Collection<Movement<TestStep>> moves;
      if (from.equals(new Cell(0, 0, 0))) {
        moves =
            List.of(
                new Movement<>(new Cell(1, 0, 0), 1.0, 1.0, TestStep.MOVE, state),
                new Movement<>(new Cell(1, 1, 0), 1.0, 1.0, TestStep.MOVE, state));
      } else if (from.equals(new Cell(1, 0, 0))) {
        moves = List.of(new Movement<>(new Cell(2, 0, 0), 1.0, 1.0, TestStep.MOVE, state));
      } else if (from.equals(new Cell(1, 1, 0))) {
        moves = List.of(new Movement<>(new Cell(2, 0, 0), 3.0, 3.0, TestStep.MOVE, state));
      } else if (from.equals(new Cell(2, 0, 0))) {
        moves = List.of(new Movement<>(new Cell(3, 0, 0), 1.0, 1.0, TestStep.MOVE, state));
      } else {
        moves = List.of();
      }
      return FutureOr.of(moves);
    }
  }

  @Test
  void edgeRestrictionDropsOnlyThatEdgeAndReParents() {
    // Same diamond, but the B→D edge carries a mode-scoped restriction future (breakability). No
    // cell
    // restrictions at all — so this exercises the Movement-carried edge path end to end.
    CompletableFuture<Boolean> gate = new CompletableFuture<>();
    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestOdysseyLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(3, 0, 0)),
            List.of(new RestrictableDiamondMode(gate)),
            List.of(),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

    CompletableFuture<Tier2Result<TestStep, TestDomain>> future = search.solve();
    assertFalse(
        future.isDone(), "reached the goal via B→D optimistically; awaiting that edge's check");

    gate.complete(true); // B→D barred → drop just that edge, re-parent D through C
    assertTrue(future.isDone());
    Tier2Result<TestStep, TestDomain> result = future.getNow(null);
    assertInstanceOf(Tier2Result.Solved.class, result);
    assertEquals(5.0, ((Tier2Result.Solved<TestStep, TestDomain>) result).cost(), 1e-9);
    assertEquals(3, ((Tier2Result.Solved<TestStep, TestDomain>) result).steps().size());
  }

  /**
   * The {@link DiamondMode} graph, but with a restriction future attached to the {@code B→D} edge.
   */
  private static final class RestrictableDiamondMode
      implements Mode<TestAgent, TestStep, TestDomain> {
    private final CompletableFuture<Boolean> bdRestricted;

    RestrictableDiamondMode(CompletableFuture<Boolean> bdRestricted) {
      this.bdRestricted = bdRestricted;
    }

    @Override
    public FutureOr<Collection<Movement<TestStep>>> step(
        TestAgent agent, Cell from, TestDomain domain, TraversalState state) {
      Collection<Movement<TestStep>> moves;
      if (from.equals(new Cell(0, 0, 0))) {
        moves =
            List.of(
                new Movement<>(new Cell(1, 0, 0), 1.0, 1.0, TestStep.MOVE, state),
                new Movement<>(new Cell(1, 1, 0), 1.0, 1.0, TestStep.MOVE, state));
      } else if (from.equals(new Cell(1, 0, 0))) {
        moves =
            List.of(
                new Movement<>(new Cell(2, 0, 0), 1.0, 1.0, TestStep.MOVE, state, bdRestricted));
      } else if (from.equals(new Cell(1, 1, 0))) {
        moves = List.of(new Movement<>(new Cell(2, 0, 0), 3.0, 3.0, TestStep.MOVE, state));
      } else if (from.equals(new Cell(2, 0, 0))) {
        moves = List.of(new Movement<>(new Cell(3, 0, 0), 1.0, 1.0, TestStep.MOVE, state));
      } else {
        moves = List.of();
      }
      return FutureOr.of(moves);
    }
  }

  @Test
  void pendingRestrictionParksThenResumesToSolution() {
    // An async verdict (not impassable, once resolved) parks the second phase until it completes.
    CompletableFuture<Boolean> verdict = new CompletableFuture<>();
    Restriction<TestAgent, TestDomain> gated =
        (agent, cell, domain) -> FutureOr.ofFuture(verdict.thenApply(ignored -> false));
    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestOdysseyLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(1, 0, 0)),
            List.of(new CorridorMode(false)),
            List.of(gated),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

    CompletableFuture<Tier2Result<TestStep, TestDomain>> future = search.solve();
    assertFalse(future.isDone(), "parked on the pending restriction verdict");

    verdict.complete(true); // resolves the mapped verdict to "not impassable"
    assertTrue(future.isDone());
    assertInstanceOf(Tier2Result.Solved.class, future.getNow(null));
  }
}
