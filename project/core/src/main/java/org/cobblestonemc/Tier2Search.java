/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.cobblestonemc.api.TraversalState;
import org.jetbrains.annotations.Nullable;

/**
 * A single-domain A* solve for one {@link VirtualPath}, run cooperatively so it never blocks a
 * worker thread.
 *
 * <p><b>Modes</b> may return a pending {@link FutureOr} (a chunk-load cache miss); the search parks
 * that expansion and resumes when the blocks arrive.
 *
 * <p><b>Restrictions</b> (integration passability checks) are handled <i>optimistically</i>: a cell
 * whose verdict is not yet known is expanded through as if passable, and its check is fired in the
 * background (integrations resolve it on the main/region thread). Verdicts land in a mailbox the
 * search drains as it runs — so the beam never stalls on a check, and a wall right next to the
 * start is caught the moment its verdict returns, not at the end.
 *
 * <p>To make that correct on a graph, every node keeps <b>all</b> the candidate parents that ever
 * relaxed it (not just its best), so removing a cell never forgets an alternative route. When a
 * cell already in the tree comes back impassable it joins a persistent impassable set (never probed
 * again) and the search <b>incrementally repairs</b>: it removes the cell, then re-parents its
 * dependent subtree to the best surviving route via a mini-Dijkstra over the retained edges —
 * pruning only the nodes with no route left. No global re-solve, no re-exploration.
 *
 * <p>All state mutation happens inside {@link #pump()}, which the {@code scheduled}/{@code
 * signalled} flags keep single-flight; verdict and mode-completion callbacks only enqueue/wake, so
 * no locks are needed.
 *
 * @param <A> the agent type
 * @param <T> the payload type
 * @param <D> the domain type
 */
final class Tier2Search<A extends Agent, T, D extends Domain> {

  private final CobblestoneLogger logger;
  private final A agent;
  private final D domain;

  /**
   * The domain this solve actually reads blocks through: {@link Domain#scopedForSolve()} of {@link
   * #domain}, so whatever caching it does belongs to this solve and dies with it.
   *
   * <p>Kept apart from {@link #domain} so that nothing scoped escapes: results are built from
   * {@code domain}, and a finished path that carried the scoped view would pin its caches for as
   * long as anything held the path.
   */
  private final D readDomain;

  private final DomainRegion<D> target;
  private final List<? extends Mode<A, T, D>> modes;
  private final List<? extends Restriction<A, D>> restrictions;
  private final boolean hasRestrictions;
  private final SolveHeuristic heuristic;
  private final double heuristicWeight;
  private final int maxCellsVisited;
  private final BooleanSupplier cancelled;
  private final Executor executor;
  private final long deadlineMillis;
  private final CellState start;

  // --- search state; touched only inside pump() (single-flight) ---
  private final Map<CellState, Node<T>> nodes = new HashMap<>();
  private final Map<Cell, Set<CellState>> byCell = new HashMap<>();

  /**
   * Lowest {@code f} first, and among equal {@code f} the node with the <b>largest</b> {@code g} —
   * the one furthest along its route.
   *
   * <p>The tie-break is not a detail. Movement costs are near-uniform over a lattice, so huge
   * numbers of cells share an {@code f} exactly; with no secondary key their order is whatever the
   * heap happens to give, and the search fans out over every equal-cost route at once instead of
   * following one. Preferring the deeper node turns that fan into a probe, and a probe that reaches
   * the goal retires the whole tie group behind it.
   */
  private static final Comparator<Entry> BY_ESTIMATE =
      Comparator.comparingDouble(Entry::estimatedTotalCost)
          .thenComparing(Comparator.comparingDouble(Entry::currentCost).reversed());

  private final PriorityQueue<Entry> open = new PriorityQueue<>(BY_ESTIMATE);
  private PendingModes<T> pendingModes;
  private CellState pendingGoal; // an optimistically-reached goal awaiting path confirmation

  // --- passability; verdicts are permanent ---
  // true = impassable, false = passable, absent = unknown (or unchecked).
  private final Map<Cell, Boolean> passability = new HashMap<>();
  private final Set<Cell> inFlight = new HashSet<>();
  // Memoized edge-restriction verdicts, so an edge's supplier is invoked at most once.
  private final Map<EdgeRef, FutureOr<Boolean>> edgeVerdicts = new HashMap<>();
  private final ConcurrentLinkedQueue<Verdict> mailbox = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<EdgeRef> edgeMailbox = new ConcurrentLinkedQueue<>();
  private final AtomicInteger pendingChecks = new AtomicInteger();
  // scheduled: a pump task is queued/running. signalled: new work arrived (mailbox add, mode or
  // verdict completion) — the pump consumes it so no wakeup is ever lost.
  private final AtomicBoolean scheduled = new AtomicBoolean();
  private final AtomicBoolean signalled = new AtomicBoolean();

  private final CompletableFuture<Tier2Result<T, D>> result = new CompletableFuture<>();

  /** Logging and reporting only; the algorithm never reads it. See {@link Tier2Metrics}. */
  private final Tier2Metrics metrics;

  /**
   * The last cell projected onto {@link #target}, and what it projected to.
   *
   * <p>Every relaxed edge needs its neighbor projected twice — once to taper the A* weight and once
   * for the estimate itself — and a {@link DomainRegion} is free to be a composite of boxes whose
   * nearest-boundary search is not cheap. The two calls are back to back, so a single slot catches
   * all of it.
   */
  private Cell projectedFrom;

  private Cell projectedTo;

  /**
   * Within this many blocks of the target, the heuristic weight eases back down towards 1.
   *
   * <p>A weighted search prices a step <em>away</em> from the target at {@code weight} times what a
   * step towards it earns, so when the goal sits in a pocket — a room whose door faces away, a
   * ledge reached from behind — the search will exhaust an enormous number of cells at the pocket's
   * mouth before it will accept one that retreats. It arrives within a few blocks and stops there.
   *
   * <p>Decaying only over the last stretch fixes that without giving up any of the speed: the long
   * haul still runs at full weight, and the endgame runs nearly admissible, which is exactly where
   * thoroughness is worth paying for. Sized to comfortably contain a building.
   */
  private static final double ENDGAME_RADIUS = 64.0;

  Tier2Search(
      CobblestoneLogger logger,
      A agent,
      VirtualPath<T, D> virtualPath,
      List<? extends Mode<A, T, D>> modes,
      List<? extends Restriction<A, D>> restrictions,
      HeuristicStrategy heuristic,
      int maxCellsVisited,
      int runningAverageWidth,
      double heuristicWeight,
      BooleanSupplier cancelled,
      Executor executor,
      long deadlineMillis) {
    this.logger =
        new ScopedCobblestoneLogger(
            logger,
            "DomainLocal[" + virtualPath.fromCell() + " to " + virtualPath.targetRegion() + "]");
    this.agent = agent;
    this.domain = virtualPath.domain();
    @SuppressWarnings("unchecked")
    D scoped = (D) virtualPath.domain().scopedForSolve();
    this.readDomain = scoped;
    this.target = virtualPath.targetRegion();
    this.modes = modes;
    this.restrictions = restrictions;
    this.hasRestrictions = !restrictions.isEmpty();
    this.heuristic = heuristic.newSolve(runningAverageWidth, this.target);
    this.heuristicWeight = heuristicWeight;
    this.maxCellsVisited = maxCellsVisited;
    this.cancelled = cancelled;
    this.executor = executor;
    this.deadlineMillis = deadlineMillis;

    this.start = new CellState(virtualPath.fromCell(), virtualPath.state());
    this.metrics = new Tier2Metrics(distanceToTarget(start.cell()));
    Node<T> startNode = getOrCreate(start);
    startNode.cost = 0.0;
    // `heuristic` here is the strategy parameter; the per-solve instance is the field.
    startNode.trailAverage = this.heuristic.seed();
    offer(start, 0.0, startNode.trailAverage);
  }

  CompletableFuture<Tier2Result<T, D>> solve() {
    logger.debug("Solved; current mem usage:{} MiB", currentMemoryUsageMiB());
    armDeadline();
    wake();
    return result;
  }

  long currentMemoryUsageMiB() {
    var runtime = Runtime.getRuntime();
    return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
  }

  /**
   * How far past the deadline the timer is set. The delay is measured on {@code nanoTime} while
   * {@link #loop()} compares {@code currentTimeMillis}, whose granularity runs to ~16ms on Windows:
   * waking a shade early would find the deadline not yet passed, and nothing re-arms the timer.
   */
  private static final long DEADLINE_SLACK_MILLIS = 20L;

  /**
   * Schedules a single wake-up at the deadline, so the budget is enforced even while the search is
   * parked.
   *
   * <p>The deadline is tested inside {@link #loop()}, which only runs when something calls {@link
   * #wake()} — a mode's blocks arriving, or a restriction verdict landing. Every park therefore
   * depends on a callback that may never come: a chunk future that never completes, or an
   * integration that schedules its verdict onto a server thread and loses it. Without this timer
   * such a search waits forever rather than timing out, and the greedier the heuristic the likelier
   * it is to get there — a search that reaches its goal quickly spends most of its life parked on
   * path confirmation, which is exactly the state that depends on those verdicts.
   */
  private void armDeadline() {
    if (deadlineMillis <= 0) {
      return;
    }
    long delay = Math.max(1, deadlineMillis - System.currentTimeMillis()) + DEADLINE_SLACK_MILLIS;
    // Hold the solve weakly and the result strongly. A timer task lives until it fires, and a
    // lambda capturing `this` would pin the whole search — every node, every candidate parent, the
    // open set — for the full budget after the search finished; on a busy server that is gigabytes
    // of finished searches waiting on their own timers. The logger and the result future are small
    // and reference nothing of the search, so they can be held outright.
    WeakReference<Tier2Search<A, T, D>> self = new WeakReference<>(this);
    CompletableFuture<Tier2Result<T, D>> pending = result;
    CobblestoneLogger timerLogger = logger;
    BooleanSupplier abandoned = cancelled;
    CompletableFuture.runAsync(
        () -> {
          if (pending.isDone()) {
            return;
          }
          Tier2Search<A, T, D> search = self.get();
          if (search != null) {
            // The normal path: wake it, and let the loop report the timeout with its stats, so
            // there is exactly one place a solve gives up and one line that says so.
            search.wake();
            return;
          }
          // Collected before its deadline: nothing was left holding it, so it cannot be woken and
          // its stats went with it. The result still has to be completed or the search above waits
          // forever — but say which of the two it was, rather than logging nothing at all.
          if (!abandoned.getAsBoolean()) {
            timerLogger.debug("Timed out; the solve was discarded before its deadline");
          }
          pending.complete(new Tier2Result.Failed<>(Tier2Result.FailureOutcome.TIMED_OUT));
        },
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, executor));
  }

  /**
   * The target's nearest boundary cell to {@code cell}, memoized over the run of repeat queries
   * about one cell. See {@link #projectedFrom}.
   */
  private Cell nearestBoundary(Cell cell) {
    if (!cell.equals(projectedFrom)) {
      projectedFrom = cell;
      projectedTo = target.nearestBoundaryCell(cell);
    }
    return projectedTo;
  }

  /** How far {@code cell} is from the target, in blocks. */
  private double distanceToTarget(Cell cell) {
    return cell.distance(nearestBoundary(cell));
  }

  /**
   * The heuristic weight to apply {@code remaining} blocks out: the configured weight in the open,
   * easing to 1 as the search closes on the target. See {@link #ENDGAME_RADIUS}.
   */
  private double weightAt(double remaining) {
    if (heuristicWeight <= 1.0 || remaining >= ENDGAME_RADIUS) {
      return heuristicWeight;
    }
    return 1.0 + (heuristicWeight - 1.0) * (remaining / ENDGAME_RADIUS);
  }

  /** Queues a node on the open set at cost {@code g}, pricing its remaining journey. */
  private void offer(CellState key, double g, double trailAverage) {
    double remaining = distanceToTarget(key.cell());
    double estimate = heuristic.estimate(key.cell(), remaining, key.state(), trailAverage);
    open.add(new Entry(key, g, g + weightAt(remaining) * estimate));
  }

  private String stats() {
    return metrics.format(nodes.size(), currentMemoryUsageMiB());
  }

  /**
   * Gives up on the budget, reporting where it went. The one place a solve times out: the deadline
   * timer wakes the search rather than completing it itself, so the report is never skipped.
   */
  private void timedOut() {
    logger.debug("Timed out; {}", stats());
    result.complete(new Tier2Result.Failed<>(Tier2Result.FailureOutcome.TIMED_OUT));
  }

  /**
   * Wakes a solve that has been abandoned, so it unwinds now rather than sitting on its node table
   * until the deadline. {@link #loop()} sees the cancellation and returns without completing: the
   * caller that cancelled has already reported the outcome.
   */
  void abandon() {
    wake();
  }

  /** Signals that there is work and schedules a single {@link #pump()} run if one is not active. */
  private void wake() {
    signalled.set(true);
    if (scheduled.compareAndSet(false, true)) {
      executor.execute(this::pump);
    }
  }

  private void pump() {
    // The park ends once, here: this run was scheduled after a real release. The loop below re-runs
    // for a signal that raced into a pump already running, which waited on nothing at all.
    boolean woke = false;
    try {
      // Consume signals: each pass runs the loop until it hits a wait; re-run while new work
      // arrived.
      while (signalled.compareAndSet(true, false)) {
        if (!woke) {
          woke = true;
          logger.trace("Woke up after {}ms", metrics.woke());
        }
        metrics.resume();

        loop();

        metrics.pause();
        if (result.isDone()) {
          return;
        }
      }
    } catch (Throwable throwable) {
      result.completeExceptionally(throwable);
    } finally {
      metrics.park();
      scheduled.set(false);
      if (!result.isDone() && signalled.get()) {
        wake(); // a signal raced our release; re-schedule
      }
    }
  }

  private void loop() {
    while (true) {
      if (cancelled.getAsBoolean()) {
        return; // abandoned; the outer search has already completed with CANCELLED
      }
      if (deadlineMillis > 0 && System.currentTimeMillis() >= deadlineMillis) {
        timedOut();
        return;
      }
      drainVerdicts();
      if (result.isDone()) {
        return;
      }
      if (pendingModes != null) {
        if (!pendingModes.ready()) {
          return; // still waiting on block I/O; the mode future will wake us
        }
        PendingModes<T> ready = pendingModes;
        pendingModes = null;
        relaxAll(ready.node(), unwrap(ready.results()));
        continue;
      }
      if (pendingGoal != null) {
        Node<T> goal = nodes.get(pendingGoal);
        if (goal == null) {
          pendingGoal = null; // a repair dropped it; fall through and keep searching
        } else if (pathConfirmed(pendingGoal)) {
          finishSolved(pendingGoal);
          return;
        } else {
          return; // path still has unconfirmed cells; a verdict will wake us
        }
      }
      if (open.isEmpty()) {
        if (pendingChecks.get() > 0) {
          return; // nothing to expand, but a pending verdict may yet repair; wait
        }
        logger.debug("Failed: open set is empty; {}", stats());
        result.complete(new Tier2Result.Failed<>(Tier2Result.FailureOutcome.UNREACHABLE));
        return;
      }
      Entry entry = open.poll();
      Node<T> node = nodes.get(entry.key());
      if (node == null || node.closed || entry.currentCost() != node.cost) {
        continue; // stale duplicate (superseded, or dropped/raised by a repair)
      }
      // Verify this node's incoming best edge now that we are committing to expand toward it.
      // Optimistic: a pending verdict does not block — we expand anyway and repair if it later
      // resolves barred; only an immediately-known bar drops the edge here.
      if (node.bestParent != null && node.bestEdge.restricted() != null) {
        FutureOr<Boolean> verdict =
            checkEdge(node.bestParent, node.key, node.bestEdge.restricted());
        if (verdict.isImmediate() && Boolean.TRUE.equals(verdict.value())) {
          removeEdge(node.bestParent, node.key); // barred — drop, repair, and re-poll
          continue;
        }
      }
      node.closed = true;
      Cell closed = node.key.cell();
      Cell goal = nearestBoundary(closed);
      metrics.reached(closed.distance(goal));
      if (target.contains(node.key.cell())) {
        if (pathConfirmed(node.key)) {
          finishSolved(node.key);
          return;
        }
        pendingGoal = node.key; // reached optimistically; wait for its path's checks to confirm
        continue;
      }
      // The cap counts cell-states reached, not expansions: it is a memory guard, and what a solve
      // holds is one Node per reached cell-state (each with its candidate parents and children),
      // not one per expansion. A 26-neighbourhood mode reaches several cells per expansion, so a
      // cap on expansions bounds the table only loosely — loosely enough to run out of heap first.
      if (nodes.size() > maxCellsVisited) {
        logger.debug("Visited cells ({}) > max ({}); {}", nodes.size(), maxCellsVisited, stats());
        result.complete(new Tier2Result.Failed<>(Tier2Result.FailureOutcome.LIMIT_EXCEEDED));
        return;
      }
      metrics.expanded();
      expand(node, goal);
    }
  }

  /** Expands one closed node, where {@code goal} is the target cell its modes aim at. */
  private void expand(Node<T> node, Cell goal) {
    List<FutureOr<Collection<Movement<T>>>> results = new ArrayList<>(modes.size());
    boolean anyPending = false;
    for (Mode<A, T, D> mode : modes) {
      FutureOr<Collection<Movement<T>>> movements =
          mode.step(agent, node.key.cell(), readDomain, node.key.state(), goal);
      results.add(movements);
      anyPending |= !movements.isImmediate();
    }
    if (anyPending) {
      pendingModes = new PendingModes<>(node.key, results);
      List<CompletableFuture<?>> pending = new ArrayList<>();
      for (FutureOr<Collection<Movement<T>>> movements : results) {
        if (!movements.isImmediate()) {
          pending.add(movements.future());
        }
      }
      CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]))
          .whenComplete(
              (ignored, error) -> {
                if (error != null) {
                  result.completeExceptionally(error);
                }
                wake();
              });
      return;
    }
    relaxAll(node.key, unwrap(results));
  }

  private void relaxAll(CellState parentKey, List<Movement<T>> movements) {
    Node<T> parent = nodes.get(parentKey);
    if (parent == null) {
      return; // parent was removed by a repair while its modes were pending; drop the expansion
    }
    for (Movement<T> movement : movements) {
      Cell cell = movement.cell();
      if (Boolean.TRUE.equals(passability.get(cell))) {
        continue; // known impassable
      }
      if (hasRestrictions && !passability.containsKey(cell) && !inFlight.contains(cell)) {
        if (!fireCheck(cell)) {
          continue; // resolved immediately as impassable
        }
      }
      CellState key = new CellState(cell, movement.state());
      Node<T> neighbor = getOrCreate(key);
      neighbor.putParent(parentKey, movement); // retained candidate parent
      // A mode-scoped edge restriction (mining breakability, pearl ballistics) is checked lazily —
      // when this node is popped, not here — so its supplier fires only for edges we commit to.
      // Use the parent's current g: a repair may have raised it while these modes were pending.
      double tentative = parent.cost + movement.cost();
      if (tentative < neighbor.cost) {
        // setBestParent first: it folds this edge into the neighbor's trail average, which is what
        // prices the neighbor's remaining journey.
        setBestParent(neighbor, parentKey, movement, tentative);
        offer(key, tentative, neighbor.trailAverage);
      }
    }
  }

  private void setBestParent(Node<T> node, CellState parentKey, Movement<T> edge, double g) {
    if (node.bestParent != null) {
      Node<T> old = nodes.get(node.bestParent);
      if (old != null) {
        old.removeChild(node.key);
      }
    }
    node.cost = g;
    node.bestParent = parentKey;
    node.bestEdge = edge;
    Node<T> parent = nodes.get(parentKey);
    if (parent != null) {
      parent.addChild(node.key);
      node.trailAverage =
          heuristic.advance(
              parent.trailAverage, edge.cost(), parentKey.cell().distance(node.key.cell()));
    }
  }

  private Node<T> getOrCreate(CellState key) {
    Node<T> node = nodes.get(key);
    if (node == null) {
      node = new Node<>(key);
      nodes.put(key, node);
      byCell.computeIfAbsent(key.cell(), c -> new HashSet<>()).add(key);
    }
    return node;
  }

  private void removeNode(CellState key) {
    Node<T> node = nodes.remove(key);
    if (node == null) {
      return;
    }
    Set<CellState> at = byCell.get(key.cell());
    if (at != null) {
      at.remove(key);
      if (at.isEmpty()) {
        byCell.remove(key.cell());
      }
    }
    if (node.bestParent != null) {
      Node<T> parent = nodes.get(node.bestParent);
      if (parent != null) {
        parent.removeChild(key);
      }
    }
  }

  /**
   * Ensures a passability check for {@code cell} is under way. Returns {@code false} if the verdict
   * resolved immediately as impassable (skip the cell), {@code true} otherwise (passable, or
   * pending — expand optimistically).
   */
  private boolean fireCheck(Cell cell) {
    FutureOr<Boolean> verdict = impassable(cell);
    if (verdict.isImmediate()) {
      boolean impassable = Boolean.TRUE.equals(verdict.value());
      passability.put(cell, impassable);
      return !impassable;
    }
    if (inFlight.add(cell)) {
      pendingChecks.incrementAndGet();
      verdict
          .future()
          .whenComplete(
              (value, error) -> {
                mailbox.add(new Verdict(cell, error == null && Boolean.TRUE.equals(value)));
                pendingChecks.decrementAndGet();
                wake();
              });
    }
    return true; // optimistic
  }

  /**
   * Invokes an edge's restriction supplier once (memoized), firing its check. On a <i>pending</i>
   * verdict that later resolves barred, the edge is queued for removal; an <i>immediate</i> barred
   * verdict is left for the caller to act on (via {@link #removeEdge}). Returns the verdict so the
   * caller can inspect the immediate case.
   */
  private FutureOr<Boolean> checkEdge(
      CellState parentKey, CellState childKey, Supplier<FutureOr<Boolean>> restricted) {
    EdgeRef ref = new EdgeRef(parentKey, childKey);
    FutureOr<Boolean> cached = edgeVerdicts.get(ref);
    if (cached != null) {
      return settled(ref, cached);
    }
    FutureOr<Boolean> verdict = restricted.get();
    edgeVerdicts.put(ref, verdict);
    if (!verdict.isImmediate()) {
      pendingChecks.incrementAndGet();
      verdict
          .future()
          .whenComplete(
              (value, error) -> {
                if (error == null && Boolean.TRUE.equals(value)) {
                  edgeMailbox.add(ref);
                }
                pendingChecks.decrementAndGet();
                wake();
              });
    }
    return settled(ref, verdict);
  }

  /**
   * Re-reads a memoized verdict whose future has since landed as an immediate one, and remembers it
   * that way.
   *
   * <p>{@link FutureOr#isImmediate()} describes the <i>shape</i> of a verdict, not whether it has
   * an answer: a {@link FutureOr.Pending} stays pending forever, however long ago its future
   * completed. Without this, an edge whose check was still in flight the first time it was asked
   * about would read as unresolved for the rest of the solve — and {@link #pathConfirmed} would
   * park on it every time it walked the route, no matter how many times the verdict arrived. The
   * barred case is caught by {@link #edgeMailbox}; this is what makes an <i>allowed</i> one
   * readable, which is the case every finished mining route depends on.
   *
   * <p>A verdict that failed is settled as allowed. The integration could not answer, and the
   * search has no standing to bar an edge on an exception — the same call that would have said so
   * is the one that broke.
   */
  private FutureOr<Boolean> settled(EdgeRef ref, FutureOr<Boolean> verdict) {
    if (verdict.isImmediate()) {
      return verdict;
    }
    CompletableFuture<Boolean> future = verdict.future();
    if (!future.isDone()) {
      return verdict;
    }
    FutureOr<Boolean> answer =
        FutureOr.of(!future.isCompletedExceptionally() && Boolean.TRUE.equals(future.getNow(null)));
    edgeVerdicts.put(ref, answer);
    return answer;
  }

  /** Combined verdict over all restrictions: impassable if any bars the cell. */
  private FutureOr<Boolean> impassable(Cell cell) {
    List<FutureOr<Boolean>> verdicts = new ArrayList<>(restrictions.size());
    for (Restriction<A, D> restriction : restrictions) {
      verdicts.add(restriction.impassable(agent, cell, readDomain));
    }
    return FutureOr.all(verdicts).map(list -> list.contains(Boolean.TRUE));
  }

  private void drainVerdicts() {
    Verdict verdict;
    while ((verdict = mailbox.poll()) != null) {
      passability.put(verdict.cell(), verdict.impassable());
      inFlight.remove(verdict.cell());
      if (verdict.impassable() && byCell.containsKey(verdict.cell())) {
        repairCell(verdict.cell());
      }
    }
    EdgeRef edge;
    while ((edge = edgeMailbox.poll()) != null) {
      removeEdge(edge.parent(), edge.child());
    }
  }

  /** Removes an impassable cell's nodes and repairs the subtrees that depended on them. */
  private void repairCell(Cell impassableCell) {
    Set<CellState> roots = byCell.get(impassableCell);
    if (roots == null || roots.isEmpty()) {
      return;
    }
    List<CellState> seeds = new ArrayList<>();
    for (CellState root : new ArrayList<>(roots)) {
      Node<T> node = nodes.get(root);
      if (node != null) {
        node.collectChildren(seeds); // its dependents lose their best route
      }
      removeNode(root);
    }
    repairFrom(seeds);
  }

  /** Drops one mode-scoped edge; if it was the child's best route, repairs the child's subtree. */
  private void removeEdge(CellState parentKey, CellState childKey) {
    Node<T> child = nodes.get(childKey);
    if (child == null || child.removeParent(parentKey) == null) {
      return; // already gone
    }
    if (parentKey.equals(child.bestParent)) {
      repairFrom(List.of(childKey));
    }
  }

  /**
   * Repairs in place every node whose best route was invalidated (a cell removed above them, or
   * their best edge dropped): re-parents each — via a mini-Dijkstra over the retained candidate
   * edges — to the cheapest surviving route, or removes it if it has none left. No re-solve, no
   * re-exploration.
   */
  private void repairFrom(Collection<CellState> invalidated) {
    // The dependent subtree: the invalidated nodes plus their best-parent descendants.
    Set<CellState> affected = new LinkedHashSet<>();
    Deque<CellState> frontier = new ArrayDeque<>(invalidated);
    while (!frontier.isEmpty()) {
      CellState key = frontier.pop();
      Node<T> node = nodes.get(key);
      if (node == null) {
        continue; // already gone; there is nothing left of it to re-parent
      }
      if (!affected.add(key)) {
        continue;
      }
      node.collectChildren(frontier);
    }
    if (affected.isEmpty()) {
      return;
    }
    logger.trace("Repairing {} node(s) after a wall", affected.size());

    // Invalidate the subtree, seed each node from its surviving external parents, and index the
    // internal (affected→affected) edges for the mini-Dijkstra.
    Map<CellState, List<CellState>> internalEdges = new HashMap<>();
    Map<CellState, Repair<T>> tentative = new HashMap<>();
    for (CellState key : affected) {
      // Every key here had a node when the subtree was walked, and nothing removes nodes in
      // between, so this cannot be null.
      Node<T> node = nodes.get(key);
      // Detach from the old best parent before forgetting who it was. A parent outside the
      // affected subtree keeps its `children` entry otherwise, and if this node is then pruned for
      // having no surviving route, removeNode cannot unlink it — it looks up the parent through
      // the very field cleared below. That leaves a child pointing at a node that no longer
      // exists, and the next repair to walk this parent's children trips over it.
      if (node.bestParent != null) {
        Node<T> oldParent = nodes.get(node.bestParent);
        if (oldParent != null) {
          oldParent.removeChild(key);
        }
      }
      node.clearChildren();
      node.cost = Double.POSITIVE_INFINITY;
      node.bestParent = null;
      node.bestEdge = null;
      node.forEachParent(
          (parentKey, edge) -> {
            if (affected.contains(parentKey)) {
              internalEdges.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(key);
              return;
            }
            Node<T> parent = nodes.get(parentKey);
            if (parent == null || parent.cost == Double.POSITIVE_INFINITY) {
              return; // removed, dangling, or not itself reachable
            }
            relaxRepair(tentative, key, parentKey, edge, parent.cost);
          });
    }

    PriorityQueue<RepairEntry> queue =
        new PriorityQueue<>((a, b) -> Double.compare(a.cost(), b.cost()));
    for (Map.Entry<CellState, Repair<T>> seed : tentative.entrySet()) {
      queue.add(new RepairEntry(seed.getKey(), seed.getValue().cost()));
    }
    Set<CellState> settled = new HashSet<>();
    while (!queue.isEmpty()) {
      RepairEntry entry = queue.poll();
      CellState key = entry.key();
      Repair<T> best = tentative.get(key);
      if (!settled.add(key) || best == null || entry.cost() != best.cost()) {
        continue; // settled already, or a stale queue entry
      }
      Node<T> node = nodes.get(key);
      setBestParent(node, best.parent(), best.edge(), best.cost());
      if (!node.closed) {
        offer(key, node.cost, node.trailAverage);
      }
      List<CellState> children = internalEdges.get(key);
      if (children == null) {
        continue;
      }
      for (CellState childKey : children) {
        if (settled.contains(childKey)) {
          continue;
        }
        Movement<T> edge = nodes.get(childKey).parentEdge(key);
        if (edge != null && relaxRepair(tentative, childKey, key, edge, node.cost)) {
          queue.add(new RepairEntry(childKey, node.cost + edge.cost()));
        }
      }
    }

    for (CellState key : affected) {
      if (!settled.contains(key)) {
        removeNode(key); // no surviving route — orphaned
      }
    }
  }

  /**
   * Records a candidate repair route for {@code key} via {@code parentKey}; true if it is a new
   * best.
   */
  private boolean relaxRepair(
      Map<CellState, Repair<T>> tentative,
      CellState key,
      CellState parentKey,
      Movement<T> edge,
      double parentG) {
    double g = parentG + edge.cost();
    Repair<T> current = tentative.get(key);
    if (current != null && current.cost() <= g) {
      return false;
    }
    tentative.put(key, new Repair<>(g, parentKey, edge));
    return true;
  }

  /**
   * Whether every cell and edge of the best route to {@code goal} is known to be allowed, firing
   * any check that has not run yet.
   *
   * <p>It walks the <b>whole</b> route before answering, rather than stopping at the first
   * unresolved check. Each check an integration owns costs a hop to the server thread and back, so
   * stopping early would confirm the path one edge per round trip — a mining route through a town
   * carries a few hundred restricted edges, and waiting those hops out one at a time spends the
   * whole budget parked. Firing them all at once turns that into a single wait. The exception is an
   * edge that comes back barred on the spot: that severs the route and repairs it, so there is
   * nothing left to walk.
   */
  private boolean pathConfirmed(CellState goal) {
    CellState cursor = goal;
    boolean awaitingVerdict = false;
    while (true) {
      Node<T> node = nodes.get(cursor);
      if (node == null) {
        return false; // vanished under a repair
      }
      if (hasRestrictions
          && !cursor.equals(start)
          && !Boolean.FALSE.equals(passability.get(cursor.cell()))) {
        awaitingVerdict = true; // cell unknown (or, defensively, impassable) — not yet confirmed
      }
      if (node.bestParent == null) {
        return !awaitingVerdict; // reached the start
      }
      Supplier<FutureOr<Boolean>> restricted = node.bestEdge.restricted();
      if (restricted != null) {
        // Drive the check here too: a best edge re-parented onto the path by a repair may never
        // have been popped, so pathConfirmed is the fallback that fires it.
        FutureOr<Boolean> verdict = checkEdge(node.bestParent, cursor, restricted);
        if (!verdict.isImmediate()) {
          awaitingVerdict = true; // its verdict will wake us; keep firing the rest meanwhile
        } else if (Boolean.TRUE.equals(verdict.value())) {
          removeEdge(node.bestParent, cursor); // barred — drop and repair; the route is gone
          return false;
        }
      }
      cursor = node.bestParent;
    }
  }

  private void finishSolved(CellState goal) {
    logger.debug("Solved; cost: {}; {}", nodes.get(goal).cost, stats());
    result.complete(new Tier2Result.Solved<>(reconstruct(goal), nodes.get(goal).cost));
  }

  private List<Movement<T>> unwrap(List<FutureOr<Collection<Movement<T>>>> results) {
    List<Movement<T>> movements = new ArrayList<>();
    for (FutureOr<Collection<Movement<T>>> futureOr : results) {
      Collection<Movement<T>> value = futureOr.value();
      if (value != null) {
        movements.addAll(value);
      }
    }
    return movements;
  }

  private List<RawStep<T, D>> reconstruct(CellState goal) {
    Deque<RawStep<T, D>> steps = new ArrayDeque<>();
    CellState cursor = goal;
    Node<T> node = nodes.get(cursor);
    while (node != null && node.bestParent != null) {
      Movement<T> movement = node.bestEdge;
      steps.addFirst(
          new RawStep<>(
              new Position<>(cursor.cell(), domain),
              movement.cost(),
              movement.time(),
              movement.payload()));
      cursor = node.bestParent;
      node = nodes.get(cursor);
    }
    return new ArrayList<>(steps);
  }

  /**
   * A search node: its cost, its chosen parent, and — the key to correct repair — every candidate
   * parent.
   */
  /**
   * One reached cell-state, and the bulk of what a solve costs in memory.
   *
   * <p>A node remembers more than its best route: every candidate parent that ever relaxed it (with
   * the {@link Movement} that would get there) and every child currently routed through it. That is
   * what lets a cell turning out to be impassable repair the affected subtree in place rather than
   * restart the solve — see {@link #repairFrom}.
   *
   * <p><b>Both are stored one-entry-first.</b> Held as a {@code HashMap} and a {@code HashSet} they
   * cost around 400 bytes per node before a single entry goes in — an empty map is some 48 bytes
   * and allocates an 80-byte table on first use — which for a solve allowed 200,000 cells is most
   * of the couple of hundred megabytes it can reach. The great majority of nodes have exactly one
   * parent and no more than one child, so the first of each lives in a field and a collection is
   * allocated only if a second arrives, small. Nothing about the search changes: it is the same
   * information, stored for what it usually is rather than for its worst case.
   *
   * <p>Slot zero is not privileged: it is simply the first place looked at, and emptying it does
   * not shuffle the overflow up. The accessors therefore consult both, which costs one extra lookup
   * on the rare path where a node has several parents and buys back not having to maintain — or
   * test — an ordering invariant between a field and a collection.
   */
  private static final class Node<T> {
    final CellState key;
    double cost = Double.POSITIVE_INFINITY;

    /**
     * The average per-block cost of the trail reaching this cell, inherited from {@link
     * #bestParent} and fed to the heuristic. A re-parenting recomputes it here but does not
     * propagate to descendants: {@code h} is a hint, and chasing the subtree would cost more than
     * the slightly stale estimate does.
     */
    double trailAverage;

    CellState bestParent;
    Movement<T> bestEdge;
    boolean closed;

    /** The first candidate parent; {@code null} when this node has none. */
    private @Nullable CellState parentKey0;

    /** The edge from {@link #parentKey0}. */
    private @Nullable Movement<T> parentEdge0;

    /** Candidate parents beyond the first; {@code null} until a second one turns up. */
    private @Nullable Map<CellState, Movement<T>> moreParents;

    /** The first child; {@code null} when nothing is routed through this node. */
    private @Nullable CellState child0;

    /** Children beyond the first; {@code null} until a second one turns up. */
    private @Nullable Set<CellState> moreChildren;

    Node(CellState key) {
      this.key = key;
    }

    /** Records a candidate parent, replacing the edge if that parent is already known. */
    void putParent(CellState parent, Movement<T> edge) {
      if (parent.equals(parentKey0)) {
        parentEdge0 = edge;
        return;
      }
      // Check the overflow before claiming an empty slot zero, or a parent already held there
      // would end up recorded twice and only half of it removed later.
      if (moreParents != null && moreParents.containsKey(parent)) {
        moreParents.put(parent, edge);
        return;
      }
      if (parentKey0 == null) {
        parentKey0 = parent;
        parentEdge0 = edge;
        return;
      }
      if (moreParents == null) {
        moreParents = new HashMap<>(4);
      }
      moreParents.put(parent, edge);
    }

    /** Forgets a candidate parent, returning the edge it held, or {@code null} if unknown. */
    @Nullable
    Movement<T> removeParent(CellState parent) {
      if (parent.equals(parentKey0)) {
        Movement<T> removed = parentEdge0;
        parentKey0 = null;
        parentEdge0 = null;
        return removed;
      }
      if (moreParents == null) {
        return null;
      }
      Movement<T> removed = moreParents.remove(parent);
      if (moreParents.isEmpty()) {
        moreParents = null;
      }
      return removed;
    }

    /** Returns the edge from a candidate parent, or {@code null} if it is not one. */
    @Nullable
    Movement<T> parentEdge(CellState parent) {
      if (parent.equals(parentKey0)) {
        return parentEdge0;
      }
      return moreParents == null ? null : moreParents.get(parent);
    }

    /**
     * Runs an action over every candidate parent.
     *
     * <p>The action must not add or remove parents of <i>this</i> node; any other node is fair
     * game, which is what the repair pass needs.
     */
    void forEachParent(BiConsumer<CellState, Movement<T>> action) {
      if (parentKey0 != null) {
        action.accept(parentKey0, parentEdge0);
      }
      if (moreParents != null) {
        moreParents.forEach(action);
      }
    }

    /** Records that a child is routed through this node. */
    void addChild(CellState child) {
      if (child.equals(child0) || (moreChildren != null && moreChildren.contains(child))) {
        return;
      }
      if (child0 == null) {
        child0 = child;
        return;
      }
      if (moreChildren == null) {
        moreChildren = new HashSet<>(4);
      }
      moreChildren.add(child);
    }

    /** Forgets a child, if it was one. */
    void removeChild(CellState child) {
      if (child.equals(child0)) {
        child0 = null;
        return;
      }
      if (moreChildren != null) {
        moreChildren.remove(child);
        if (moreChildren.isEmpty()) {
          moreChildren = null;
        }
      }
    }

    void clearChildren() {
      child0 = null;
      moreChildren = null;
    }

    /** Adds every child of this node to {@code sink}. */
    void collectChildren(Collection<CellState> sink) {
      if (child0 != null) {
        sink.add(child0);
      }
      if (moreChildren != null) {
        sink.addAll(moreChildren);
      }
    }
  }

  private record CellState(Cell cell, TraversalState state) {}

  private record Entry(CellState key, double currentCost, double estimatedTotalCost) {}

  private record RepairEntry(CellState key, double cost) {}

  private record Repair<T>(double cost, CellState parent, Movement<T> edge) {}

  private record Verdict(Cell cell, boolean impassable) {}

  private record EdgeRef(CellState parent, CellState child) {}

  private record PendingModes<T>(CellState node, List<FutureOr<Collection<Movement<T>>>> results) {
    boolean ready() {
      for (FutureOr<Collection<Movement<T>>> movements : results) {
        if (!movements.isImmediate() && !movements.future().isDone()) {
          return false;
        }
      }
      return true;
    }
  }
}
