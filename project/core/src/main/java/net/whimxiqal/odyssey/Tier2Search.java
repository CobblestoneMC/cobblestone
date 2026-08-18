/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.whimxiqal.odyssey.api.TraversalState;

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

  private final OdysseyLogger logger;
  private final A agent;
  private final D domain;

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
  private final PriorityQueue<Entry> open =
      new PriorityQueue<>((a, b) -> Double.compare(a.estimatedTotalCost(), b.estimatedTotalCost()));
  private int expandedCount;
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

  Tier2Search(
      OdysseyLogger logger,
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
    this.logger = logger;
    this.agent = agent;
    this.domain = virtualPath.domain();
    this.target = virtualPath.targetRegion();
    this.modes = modes;
    this.restrictions = restrictions;
    this.hasRestrictions = !restrictions.isEmpty();
    this.heuristic = heuristic.newSolve(runningAverageWidth);
    this.heuristicWeight = heuristicWeight;
    this.maxCellsVisited = maxCellsVisited;
    this.cancelled = cancelled;
    this.executor = executor;
    this.deadlineMillis = deadlineMillis;

    this.start = new CellState(virtualPath.fromCell(), virtualPath.state());
    Node<T> startNode = getOrCreate(start);
    startNode.cost = 0.0;
    open.add(
        new Entry(
            start, 0.0, heuristicWeight * heuristic.estimate(start.cell(), target, start.state())));
  }

  CompletableFuture<Tier2Result<T, D>> solve() {
    wake();
    return result;
  }

  /** Signals that there is work and schedules a single {@link #pump()} run if one is not active. */
  private void wake() {
    signalled.set(true);
    if (scheduled.compareAndSet(false, true)) {
      executor.execute(this::pump);
    }
  }

  private void pump() {
    try {
      // Consume signals: each pass runs the loop until it hits a wait; re-run while new work
      // arrived.
      while (signalled.compareAndSet(true, false)) {
        loop();
        if (result.isDone()) {
          return;
        }
      }
    } catch (Throwable throwable) {
      result.completeExceptionally(throwable);
    } finally {
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
      if (deadlineMillis > 0 && System.currentTimeMillis() > deadlineMillis) {
        logger.debug("Tier2Search(agent:{},target:{}) timed out", agent, target);
        result.complete(new Tier2Result.Failed<>(Tier2Result.FailureOutcome.TIMED_OUT));
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
        logger.debug("Tier2Search(agent:{},target:{}) failed: open set is empty", agent, target);
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
      if (node.bestParent != null) {
        heuristic.observe(node.bestEdge.cost(), node.bestParent.cell().distance(node.key.cell()));
      }
      if (target.contains(node.key.cell())) {
        if (pathConfirmed(node.key)) {
          finishSolved(node.key);
          return;
        }
        pendingGoal = node.key; // reached optimistically; wait for its path's checks to confirm
        continue;
      }
      if (expandedCount++ > maxCellsVisited) {
        logger.debug(
            "Tier2Search(agent:{},target:{}) visited cells ({}) > max ({})",
            agent,
            target,
            expandedCount,
            maxCellsVisited);
        result.complete(new Tier2Result.Failed<>(Tier2Result.FailureOutcome.LIMIT_EXCEEDED));
        return;
      }
      expand(node);
    }
  }

  private void expand(Node<T> node) {
    List<FutureOr<Collection<Movement<T>>>> results = new ArrayList<>(modes.size());
    boolean anyPending = false;
    for (Mode<A, T, D> mode : modes) {
      FutureOr<Collection<Movement<T>>> movements =
          mode.step(agent, node.key.cell(), domain, node.key.state());
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
      neighbor.parents.put(parentKey, movement); // retained candidate parent
      // A mode-scoped edge restriction (mining breakability, pearl ballistics) is checked lazily —
      // when this node is popped, not here — so its supplier fires only for edges we commit to.
      // Use the parent's current g: a repair may have raised it while these modes were pending.
      double tentative = parent.cost + movement.cost();
      if (tentative < neighbor.cost) {
        setBestParent(neighbor, parentKey, movement, tentative);
        open.add(
            new Entry(
                key,
                tentative,
                tentative + heuristicWeight * heuristic.estimate(cell, target, movement.state())));
      }
    }
  }

  private void setBestParent(Node<T> node, CellState parentKey, Movement<T> edge, double g) {
    if (node.bestParent != null) {
      Node<T> old = nodes.get(node.bestParent);
      if (old != null) {
        old.children.remove(node.key);
      }
    }
    node.cost = g;
    node.bestParent = parentKey;
    node.bestEdge = edge;
    Node<T> parent = nodes.get(parentKey);
    if (parent != null) {
      parent.children.add(node.key);
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
        parent.children.remove(key);
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
      return cached;
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
    return verdict;
  }

  /** Combined verdict over all restrictions: impassable if any bars the cell. */
  private FutureOr<Boolean> impassable(Cell cell) {
    List<FutureOr<Boolean>> verdicts = new ArrayList<>(restrictions.size());
    for (Restriction<A, D> restriction : restrictions) {
      verdicts.add(restriction.impassable(agent, cell, domain));
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
        seeds.addAll(node.children); // its dependents lose their best route
      }
      removeNode(root);
    }
    repairFrom(seeds);
  }

  /** Drops one mode-scoped edge; if it was the child's best route, repairs the child's subtree. */
  private void removeEdge(CellState parentKey, CellState childKey) {
    Node<T> child = nodes.get(childKey);
    if (child == null || child.parents.remove(parentKey) == null) {
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
      if (!affected.add(key)) {
        continue;
      }
      Node<T> node = nodes.get(key);
      if (node != null) {
        frontier.addAll(node.children);
      }
    }
    if (affected.isEmpty()) {
      return;
    }
    logger.trace(
        "Tier2Search(agent:{},target:{}) repairing {} node(s) after a wall",
        agent,
        target,
        affected.size());

    // Invalidate the subtree, seed each node from its surviving external parents, and index the
    // internal (affected→affected) edges for the mini-Dijkstra.
    Map<CellState, List<CellState>> internalEdges = new HashMap<>();
    Map<CellState, Repair<T>> tentative = new HashMap<>();
    for (CellState key : affected) {
      Node<T> node = nodes.get(key);
      node.children.clear();
      node.cost = Double.POSITIVE_INFINITY;
      node.bestParent = null;
      node.bestEdge = null;
      for (Map.Entry<CellState, Movement<T>> candidate : node.parents.entrySet()) {
        CellState parentKey = candidate.getKey();
        if (affected.contains(parentKey)) {
          internalEdges.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(key);
          continue;
        }
        Node<T> parent = nodes.get(parentKey);
        if (parent == null || parent.cost == Double.POSITIVE_INFINITY) {
          continue; // removed, dangling, or not itself reachable
        }
        relaxRepair(tentative, key, parentKey, candidate.getValue(), parent.cost);
      }
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
        open.add(
            new Entry(
                key,
                node.cost,
                node.cost + heuristicWeight * heuristic.estimate(key.cell(), target, key.state())));
      }
      List<CellState> children = internalEdges.get(key);
      if (children == null) {
        continue;
      }
      for (CellState childKey : children) {
        if (settled.contains(childKey)) {
          continue;
        }
        Movement<T> edge = nodes.get(childKey).parents.get(key);
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
   * Whether {@code goal}'s current best path is fully confirmed: every cell has a passable verdict
   * (for global restrictions) and every edge's mode-scoped restriction has resolved as not-barred.
   */
  private boolean pathConfirmed(CellState goal) {
    CellState cursor = goal;
    while (true) {
      Node<T> node = nodes.get(cursor);
      if (node == null) {
        return false; // vanished under a repair
      }
      if (hasRestrictions
          && !cursor.equals(start)
          && !Boolean.FALSE.equals(passability.get(cursor.cell()))) {
        return false; // cell unknown (or, defensively, impassable) — not yet confirmed
      }
      if (node.bestParent == null) {
        return true; // reached the start
      }
      Supplier<FutureOr<Boolean>> restricted = node.bestEdge.restricted();
      if (restricted != null) {
        // Drive the check here too: a best edge re-parented onto the path by a repair may never
        // have been popped, so pathConfirmed is the fallback that fires it.
        FutureOr<Boolean> verdict = checkEdge(node.bestParent, cursor, restricted);
        if (!verdict.isImmediate()) {
          return false; // the edge check is pending; its verdict will wake us
        }
        if (Boolean.TRUE.equals(verdict.value())) {
          removeEdge(node.bestParent, cursor); // barred — drop and repair; not confirmed
          return false;
        }
      }
      cursor = node.bestParent;
    }
  }

  private void finishSolved(CellState goal) {
    logger.debug("Tier2Search(agent:{},target:{}) solved. visited:{}", agent, target, nodes.size());
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
  private static final class Node<T> {
    final CellState key;
    double cost = Double.POSITIVE_INFINITY;
    CellState bestParent;
    Movement<T> bestEdge;
    final Map<CellState, Movement<T>> parents = new HashMap<>();
    final Set<CellState> children = new HashSet<>();
    boolean closed;

    Node(CellState key) {
      this.key = key;
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
