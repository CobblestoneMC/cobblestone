# Odyssey — `core` (the algorithm)

Package `net.whimxiqal.odyssey.core`. Implements `OdysseyApi` and the two-tier search over the
abstractions in `core-api`. Depends only on `core-api`.

## The two tiers, in one paragraph
Most real routes cross domains or exploit "wormholes" (portals, teleports, horses, rail lines). So
**Tier 1** builds a graph whose *nodes are `Transition`s* and *edges are `VirtualPath`s* (same-domain
hops between transition endpoints) with **optimistic** cost estimates, and runs Dijkstra to pick the
cheapest transition sequence. **Tier 2** then *solves* each chosen `VirtualPath` with A* inside its
one domain, turning it into concrete `Step`s. Because the estimates are optimistic, a solved edge may
come back more expensive (or infeasible); when it overshoots by more than a threshold we raise that
edge's known cost and re-run Tier 1, converging on a route that is *good* (not provably optimal).

## Cooperative, resumable execution

### `Search` is an object, not a call stack
A `Search` owns all of its mutable state on the heap (Tier-1 graph, current Tier-2 A* frontier,
cursors, visited sets). The `Scheduler` drives it by calling `advance()`. Pseudocode:
```
class Search<A, T, I> {
  advance():
     while not done:
        FutureOr<X> fo = doOneUnitOfWork()      // pop a node, ask a Mode to step, etc.
        if fo.isImmediate():
           applyImmediately(fo.value())          // stay on this worker (cache hit)
        else:
           fo.future().whenComplete((v,e) -> scheduler.runAsync(this::advance))  // PARK
           return                                 // worker freed for other searches
     complete(result)                             // resolve the CompletableFuture
}
```
Because state lives in fields, re-entering `advance()` just continues the loop. Interleaving of many
searches on one worker falls out naturally. **Park once per expansion, not per block:** when
expanding a node, first collect every block cell the expansion needs; if any are pending, request
them all and park until all are ready; on resume the whole expansion re-runs with everything
cache-resident (all `isImmediate()`), so expansions are idempotent and simple.

### Cancellation
`Search` holds an `AtomicBoolean cancelled`. `advance()` checks it at the top of each loop turn;
`SearchHandle.cancel()` sets it, completes the future with `FailureReason.CANCELLED`, and stops
rescheduling. Parked continuations detect the flag on resume and exit. In-flight chunk requests are
allowed to complete (harmless) but their results are dropped.

### Limits
Checked in `advance()`:
- `maxCellsVisited` (default 10 000) across the *current* Tier-2 A* → `DESTINATION_UNREACHABLE` for
  that edge (treated like an infeasible solve, see recalc).
- `maxWallClockMillis` (default 60 000) across the whole search → `TIMED_OUT`.
These are per-search; concurrency limits are enforced by the caller (see `06`).

## Tier 1 — graph / Dijkstra

### Generic graph
An abstract, unit-testable shortest-path engine, independent of transitions.
```java
public abstract class Graph<N, E> {
  protected abstract Iterable<E> outboundEdges(N node);   // lazily produced
  protected abstract N head(E edge);                      // edge → destination node
  protected abstract double cost(E edge);                 // may be POSITIVE_INFINITY
  /** Dijkstra from source to any goal node; returns the alternating node/edge result or empty. */
  public Optional<GraphPath<N,E>> shortestPath(N source, Predicate<N> isGoal);
}
```
`GraphPath<N,E>` is the alternating **Node, Edge, Node, …, Node** structure with a traversal cursor
(`currentNode()`, `hasNextEdge()`, `next() → (Edge, Node)`), used internally here and **flattened
into a single `Path` of `Step`s** for consumers (see "Building the result").

### Wrapping transitions as graph nodes/edges
- Nodes = `GraphTransition` wrapping a `Transition`, plus its accumulated `TraversalState` along the
  Dijkstra label (see state-aware estimates). It holds a reference to the **transition index**: all
  transitions memoized by the domain of their `origin` region and of their `destination`.
- `GraphTransition.outboundEdges()` produces a `GraphVirtualPath` to **every transition whose origin
  region is in the same domain as this transition's destination** — generated lazily and cached, so
  we never materialize the full O(T²) edge set. Only the frontier Dijkstra touches is built.
- Edge cost = the `VirtualPath`'s current cost:
  - initially the **optimistic estimate** (a true lower bound; see below),
  - `+∞` if a Tier-2 solve proved it infeasible,
  - the **true solved cost** once solved,
  - a raised **lower bound** if its solve was paused by the recalc threshold.

### Bookends
- The **origin transition** (a synthetic `Transition` with destination = player's `Position`,
  `cost 0`) is the Dijkstra source.
- Each destination `DomainRegion` gets a synthetic **destination transition** (origin = that region).
  A synthetic **super-sink** node has zero-cost inbound edges from all destination transitions, so
  multi-endpoint destinations ("closest town") are one Dijkstra to the sink.
- `isGoal` = "is the super-sink." These synthetic transitions are recognized internally by reference
  (no public `isPseudo` flag) and contribute no geometry to the flattened result.

### Optimistic edge estimate (must be a lower bound, and is state-aware)
For a `VirtualPath` from cell `a` to a target region `R` in one domain, with accumulated state `s`:
`estimate = euclidean(a, R.nearestBoundaryCell(a)) × minPerCellCost(s)`, where `minPerCellCost(s)` is
the minimum single-cell cost over the modes available **in state `s`** (e.g. after a mount
`Transition` applied `VEHICLE=HORSE`, this is the horse's per-cell cost, so Dijkstra prefers horse
routes before anything is solved). This never overestimates the true same-domain cost, keeping Tier-1
optimistic. The state need only be *optimistic*, not exact — Tier-2 + the recalc loop correct any
error — so carrying it on the Dijkstra label is a cheap refinement, not a correctness burden.

## Tier 2 — VirtualPath / A*

### What it solves
Given a `VirtualPath` (start cell → a target `DomainRegion`, both in one domain), the agent's modes,
and a `TraversalState` seed, find a low-cost sequence of `Step`s. Uses the classic A* loop with a
`candidate` priority queue ordered by `f = g + h` and a `visited` set keyed on
**`(cell, TraversalState)`**. Completion = the popped cell is `region.contains(cell)`.

### Per-step expansion
Pop the lowest-`f` candidate. Ask **every** available mode to `step(agent, cell, domain, state)`.
Union all resulting `Movement`s; for each destination `(cell, state)` keep only the cheapest arriving
movement (carrying its `stepType` and any `instruction`). Push/relax neighbors. Each node stores a
back-reference to its predecessor so we reconstruct the steps by backtracking from the satisfying
cell. Modes return `FutureOr`; the search gathers pending ones and parks per the cooperative model.

### Heuristic (`HeuristicStrategy`, pluggable)
```java
public interface HeuristicStrategy {
  /** Estimated remaining cost from `node` toward the target DomainRegion. */
  double estimate(HeuristicContext ctx);
}
```
`HeuristicContext` exposes the current cell, the target `DomainRegion` (so the heuristic picks its
own metric over `region.nearestBoundaryCell(cell)`), the current `TraversalState`, the mode/cost of
the last N steps (a ring buffer), and settings.

Two provided strategies:
1. **`AdmissibleHeuristic` (default).** `h = euclidean(cell, region.nearestBoundaryCell(cell)) ×
   minPerCellCost(state)`. Never overestimates ⇒ A* returns the optimal path *for the terrain it
   explored*. Weak (fans out), but correct; used by `core-test` exact-path assertions. (Uses
   euclidean, not manhattan, so diagonal movement can't make it overestimate.)
2. **`RunningAverageHeuristic` (opt-in "fast").** Projects remaining cost using a running average of
   the last `runningAverageWidth` (default 5–10) step costs × remaining euclidean distance. This is
   the fix for the "infinitely thick wall" problem: one `MINE` step among four `WALK` steps averages
   out to nearly-walk, so a 1-block wall doesn't look infinite; only sustained mining drives the
   estimate up. **Not admissible** — faster and terrain-aware but drops the optimality guarantee.
   Documented as such.

The distance term is euclidean throughout.

### Cost model reminders (Minecraft-specific numbers live in `04`)
- Cost unit = seconds. Danger/damage steps cost `damageMultiplier × timeToHeal(damage)`.
- Mining cost = block break-time assuming stone tools; unbreakable ⇒ mode yields nothing ⇒ impassable.

## Recalc loop (Tier-1 ↔ Tier-2 handshake)

```
solve():
  loop:
    gp = tier1.shortestPath(origin, isSuperSink)     // uses current edge costs
    if gp is empty: return failure(NO_ROUTE)
    for each VirtualPath edge vp along gp (in order), if not already fully solved:
        astar = new Tier2Search(vp, modes, stateAtVp)          // state carried from the Dijkstra label
        run astar cooperatively; while running, watch min-f of its open set:
           if min-f > threshold × vp.optimisticEstimate:      // overshoot detected early
               vp.raiseCost(min-f)                             // monotonic lower-bound bump
               pause astar (keep its state)
               goto loop                                       // re-plan Tier 1 with the new cost
        on success: vp.solve(steps, cost)                      // memoize true cost & solved steps
        on infeasible/limit: vp.markInfeasible()  (cost = +∞); goto loop
    // every edge on gp is fully solved:
    return success(buildPath(gp))
```

### Why this terminates and stays sane
- Each pause or infeasibility **monotonically raises** some edge's known lower bound (a bump to
  `min-f`, or to `+∞`). Edge costs never decrease.
- There are finitely many edges the frontier can reach, and Dijkstra always returns the current
  cheapest; the best-path cost is non-decreasing and bounded above by the cheapest feasible route.
- A paused A* keeps its frontier, so if Tier 1 re-selects the same edge we **resume** rather than
  restart — no wasted work, no thrash.
- Result: an *anytime* hierarchical search that converges to a good route. If a solved edge comes
  back *cheaper* than estimated, that's a free improvement — we just record the lower true cost.

### Building the result
`buildPath(gp)` walks the alternating graph path and **flattens** it into a single ordered
`List<Step>`: for each `VirtualPath` edge it appends that edge's solved movement steps (each tagged
with its `stepType`/`instruction` and its domain), and for each real `Transition` node it appends one
`Step` carrying the transition's `stepType` (e.g. `PORTAL`/`COMMAND`/`MOUNT_HORSE`), its
`instruction`, and its destination `Position`. The synthetic origin/destination transitions
contribute no `Step`. A domain change between consecutive steps marks a crossing. `Path.cost()` is
the sum of edge costs + real transition costs. The result is wrapped as `NavigationResult.Success`.

## Graph-scale safeguards (config, optional)
Lazy edges bound memory; to bound *exploration* on pathological servers:
- `maxTransitionsConsidered` (default unbounded, but recommended set by proximity to origin/destination),
- Tier-2 `maxCellsVisited` already caps each solve.
These are surfaced through `SearchSettings`.

## Testability
- `Graph.shortestPath` is unit-tested in isolation with hand-built node/edge fixtures.
- The Tier-2 A* is exercised by `core-test` worlds with the `AdmissibleHeuristic` so exact optimal
  paths can be asserted; `RunningAverageHeuristic` tests assert "valid & within X% of optimal."
- The recalc loop has targeted tests: an edge whose true cost overshoots forces a re-plan onto a
  cheaper alternative; an infeasible edge forces `+∞` and reroute; no-route yields failure.
