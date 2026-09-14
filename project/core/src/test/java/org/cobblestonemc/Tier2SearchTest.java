/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.cobblestonemc.api.TraversalState;
import org.junit.jupiter.api.Test;

class Tier2SearchTest {

  private static final TestDomain DOMAIN = new TestDomain("test");

  private VirtualPath<TestStep, TestDomain> virtualPath(Cell from, Cell target) {
    return new VirtualPath<>(
        from, DOMAIN, new CellRegion<>(target, DOMAIN), TraversalState.DEFAULT);
  }

  /**
   * The deadline is tested inside the search loop, and the loop only runs when something wakes it.
   * A mode whose blocks never arrive parks the search on a callback that never comes — so without a
   * timer armed at the deadline the search waits forever instead of timing out. This reproduces
   * that: a mode that returns a future nobody completes.
   */
  @Test
  void aSearchParkedOnAFutureThatNeverCompletesStillTimesOut() throws Exception {
    Mode<TestAgent, TestStep, TestDomain> neverCompletes =
        (agent, from, domain, state, goal) -> FutureOr.ofFuture(new CompletableFuture<>());

    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestCobblestoneLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(3, 0, 0)),
            List.of(neverCompletes),
            List.of(),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Executors.newSingleThreadExecutor(),
            System.currentTimeMillis() + 100);

    Tier2Result<TestStep, TestDomain> result = search.solve().get(10, TimeUnit.SECONDS);

    assertInstanceOf(Tier2Result.Failed.class, result);
    assertEquals(
        Tier2Result.FailureOutcome.TIMED_OUT,
        ((Tier2Result.Failed<TestStep, TestDomain>) result).outcome());
  }

  /**
   * A mining route whose breakability verdicts arrive <i>after</i> the search reached its goal must
   * still finish.
   *
   * <p>This is the shape of every real mining search: an integration answers on the server thread,
   * so the first time the search asks about an edge the verdict is always in flight. It expands
   * through optimistically, reaches the goal, and parks on path confirmation until the answers
   * land. If a landed verdict cannot be read back as an answer, the route can never be confirmed
   * and the search parks until its deadline instead of returning the path it already has.
   */
  @Test
  void aMiningRouteWhoseVerdictsLandAfterTheGoalIsReachedStillSolves() {
    CompletableFuture<Boolean> allowed = new CompletableFuture<>();
    Mode<TestAgent, TestStep, TestDomain> minedCorridor =
        (agent, from, domain, state, goal) ->
            FutureOr.of(
                List.of(
                    new Movement<>(
                        from.plus(1, 0, 0),
                        1.0,
                        1.0,
                        TestStep.MOVE,
                        state,
                        () -> FutureOr.from(allowed))));

    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestCobblestoneLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(2, 0, 0)),
            List.of(minedCorridor),
            List.of(),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

    CompletableFuture<Tier2Result<TestStep, TestDomain>> future = search.solve();
    assertFalse(future.isDone(), "the goal is reached, but its route is unconfirmed");

    allowed.complete(false); // nothing bars breaking these blocks

    assertTrue(future.isDone(), "the verdicts landed; the route is confirmed");
    assertInstanceOf(Tier2Result.Solved.class, future.getNow(null));
  }

  /**
   * An integration that fails outright must not park the search forever. The call that would have
   * barred the edge is the one that broke, so the edge is allowed and the route stands.
   */
  @Test
  void anEdgeCheckThatFailsIsTreatedAsAllowedRatherThanAwaitedForever() {
    CompletableFuture<Boolean> broken = new CompletableFuture<>();
    Mode<TestAgent, TestStep, TestDomain> minedCorridor =
        (agent, from, domain, state, goal) ->
            FutureOr.of(
                List.of(
                    new Movement<>(
                        from.plus(1, 0, 0),
                        1.0,
                        1.0,
                        TestStep.MOVE,
                        state,
                        () -> FutureOr.from(broken))));

    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestCobblestoneLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(2, 0, 0)),
            List.of(minedCorridor),
            List.of(),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

    CompletableFuture<Tier2Result<TestStep, TestDomain>> future = search.solve();
    assertFalse(future.isDone());

    broken.completeExceptionally(new IllegalStateException("the integration threw"));

    assertTrue(future.isDone(), "a failed check must not park the search");
    assertInstanceOf(Tier2Result.Solved.class, future.getNow(null));
  }

  /**
   * A repair that prunes a node must leave nothing pointing at it.
   *
   * <p>Two repairs, in order. First a mode-restricted edge — a mining step an integration forbids —
   * comes back barred, which strands the dead end it led to and prunes it; its parent survives and
   * is outside the repaired subtree. Then the cell above that parent is barred, and the second
   * repair walks the parent's children.
   *
   * <p>The order matters: a repair that clears {@code bestParent} before pruning leaves the pruned
   * node in its old parent's child set, since the removal looks the parent up through exactly that
   * field — and the second repair then finds a child with no node behind it. It takes a restricted
   * edge to set up, so only searches with mining enabled reach this shape at all.
   */
  @Test
  void aRepairThatPrunesANodeLeavesNoDanglingChildBehind() {
    CompletableFuture<Boolean> deadEndBarred = new CompletableFuture<>();
    CompletableFuture<Boolean> cellBarred = new CompletableFuture<>();

    // (0,0,0) → (1,0,0) → (2,0,0) → (3,0,0), the last step restricted; (3,0,0) goes nowhere.
    // The goal is out past the dead end, so the search never arrives and stays alive on its
    // outstanding checks while the two verdicts land one at a time.
    Restriction<TestAgent, TestDomain> barOne =
        (agent, cell, domain) ->
            cell.equals(new Cell(1, 0, 0)) ? FutureOr.ofFuture(cellBarred) : FutureOr.of(false);
    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestCobblestoneLogger(),
            new TestAgent(),
            virtualPath(new Cell(0, 0, 0), new Cell(9, 0, 0)),
            List.of(new DeadEndMode(3, () -> FutureOr.ofFuture(deadEndBarred))),
            List.of(barOne),
            Heuristics.zero(),
            1000,
            5,
            1.0,
            () -> false,
            Runnable::run,
            0);

    CompletableFuture<Tier2Result<TestStep, TestDomain>> future = search.solve();
    assertFalse(future.isDone(), "still holding two unresolved checks");

    // First repair: the restricted edge is barred, stranding and pruning (3,0,0).
    deadEndBarred.complete(true);
    assertFalse(future.isDone(), "(1,0,0)'s verdict is still outstanding");

    // Second repair: (1,0,0) is impassable, so the repair walks (2,0,0)'s children — which must no
    // longer include the pruned dead end.
    cellBarred.complete(true);

    assertTrue(future.isDone());
    assertInstanceOf(Tier2Result.Failed.class, future.getNow(null));
  }

  @Test
  void immediateModeSolvesWithoutParking() {
    CorridorMode mode = new CorridorMode(false);
    Tier2Search<TestAgent, TestStep, TestDomain> search =
        new Tier2Search<>(
            new TestCobblestoneLogger(),
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
            new TestCobblestoneLogger(),
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
            new TestCobblestoneLogger(),
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
            new TestCobblestoneLogger(),
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
            new TestCobblestoneLogger(),
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
            new TestCobblestoneLogger(),
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
            new TestCobblestoneLogger(),
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
        TestAgent agent, Cell from, TestDomain domain, TraversalState state, Cell destination) {
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
            new TestCobblestoneLogger(),
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
        TestAgent agent, Cell from, TestDomain domain, TraversalState state, Cell destination) {
      Collection<Movement<TestStep>> moves;
      if (from.equals(new Cell(0, 0, 0))) {
        moves =
            List.of(
                new Movement<>(new Cell(1, 0, 0), 1.0, 1.0, TestStep.MOVE, state),
                new Movement<>(new Cell(1, 1, 0), 1.0, 1.0, TestStep.MOVE, state));
      } else if (from.equals(new Cell(1, 0, 0))) {
        moves =
            List.of(
                new Movement<>(
                    new Cell(2, 0, 0),
                    1.0,
                    1.0,
                    TestStep.MOVE,
                    state,
                    () -> FutureOr.from(bdRestricted)));
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
            new TestCobblestoneLogger(),
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
