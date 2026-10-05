# Odyssey — Parallel Multi-Endpoint Tier-2 Search (design sketch)

**Status:** tabled / future expansion. Not a v1 deliverable. Captures the "nearest of several
*distinct* regions" idea (e.g. the nearest bank in a town when there are two, and you start in the
Nether).

## Motivating example
A player in the Nether asks to navigate to `towny → mytown → bank`, and the town has **two** banks in
the Overworld. The correct answer is the *cheaper* of "path to bank A" and "path to bank B". Both
share a long common prefix: get out of the Nether and through the portal into the Overworld. Only the
final Overworld leg differs.

## Current model (recap)
A `Destination` is a `Collection<Region>` modelled as a virtual **super-sink** (`03`, `Tier1Node.Sink`).
In `Tier1Graph`, every destination region in a reachable domain contributes one edge
`… → Sink`, whose `VirtualPath` is the same-domain Tier-2 hop from the current position to that region.
Dijkstra over Tier-1 finds the cheapest origin→Sink path; because the several banks are several edges
into the *same* Sink, "nearest of many" already falls out.

`VirtualPath`s are **memoized**, so the shared prefix (Nether→portal) is solved once and reused. The
anytime recalc loop (`SearchImpl`) solves the first unsolved edge on the current best Tier-1 path,
then re-plans; as true edge costs replace heuristic estimates, it will solve *both* final legs if they
stay competitive and keep the minimum.

**So the current model is already correct.** What it does *not* do is exploit concurrency: the
competing final legs are solved **sequentially** (solve leg→A, re-plan, maybe solve leg→B, compare),
even though they are independent and could run on separate worker threads at once.

## Proposed expansion: solve distinct endpoints in parallel over a shared backbone
When Tier-1 planning reaches a frontier node from which several **unsolved, competitive** edges lead
to distinct endpoints (multiple `… → Sink` edges, or more generally several edges whose optimistic
totals are within the current bound), dispatch their `Tier2Search`es **concurrently** and select the
minimum as they complete, rather than solving one, re-planning, and solving the next.

Sketch:
1. Run Tier-1 as today until the best path's first unsolved edge is a **branch point** — a node with
   ≥2 unsolved outbound edges whose estimated totals are all within the incumbent best.
2. Dispatch a `Tier2Search` for each such competitive edge in parallel on the worker pool. They share
   the already-memoized prefix `VirtualPath`s (no recomputation) and each has its own goal region.
3. As solves complete, fold the real cost back into the edge and update the incumbent. **Cancel**
   branches whose optimistic total now exceeds the incumbent (they can't win).
4. When no competitive unsolved branch remains, the incumbent Tier-1 path is optimal; build it.

This is the anytime recalc loop with **breadth**: instead of "solve one unsolved edge then re-plan,"
it is "solve all currently-competitive unsolved edges, then re-plan." Selection is still by true cost,
so the result is identical to today's — only the wall-clock latency improves (parallel legs) and the
"distinct endpoints" case is made explicit.

## Interactions and constraints
- **Worker pool budget.** Parallel legs consume the shared executor. Cap the fan-out (e.g. a
  `max_parallel_legs` knob) so one over-branched search can't starve others; prefer the
  heuristically-cheapest branches first.
- **Search budgets.** `maxCellsVisited` / `maxWallClockMillis` are currently per-leg. Decide whether
  the budget is per-leg (simpler) or shared across the fan-out (fairer but needs accounting).
- **Cancellation.** Losing branches must be cancellable mid-solve (the `cancelled` flag already exists
  on `Tier2Search`); dominated branches should be cut as soon as the incumbent beats their optimistic
  bound.
- **Weighted A*.** Tier-2 is bounded-suboptimal (`heuristicWeight`), so a "solved" leg cost is an
  upper bound within the weight factor. Selection-by-minimum still holds; note the bound is on each
  leg, not tightened by parallelism.
- **Async restrictions / breakability.** Orthogonal — each parallel leg parks/resumes on its own
  pending checks as today. Parallelism actually *helps* batch those lookups across legs.
- **Memoization is load-bearing.** Correctness and efficiency both depend on shared prefix
  `VirtualPath`s being solved once; the branch legs must read, not duplicate, them.

## When it is worth it (and when it is not)
The distinction is **first-reached (approximation-guided) vs all-computed (exact per-endpoint)**.
Today's single estimate-guided search effectively returns a route to whichever region the heuristic
leads it to first. That is only *approximately* the nearest, and the approximation error grows when the
candidate regions are **wildly separated** — opposite corners of the world, different dimensions,
routes that diverge early and share little. There, the heuristic can commit toward a region that looks
closer but whose true route is worse, and computing an exact route to *each* region and taking the
minimum is meaningfully better.

For regions that are **co-located** — e.g. the many 16×16 chunks of a single town claim, or several
banks clustered in one town — this is **not worth it**: every region's heuristic and route are doing
roughly the same thing, so one estimate-guided search already lands on effectively the nearest, and
firing a separate search per chunk would be a large multiplier of work for no better answer. Treat a
town's claim as one super-sink over its chunks (co-located) and reserve per-endpoint parallel solving
for genuinely disparate goals (e.g. a town's far-flung **outposts**, or "nearest town" across the map).

A practical trigger: only branch into parallel per-endpoint solves when the candidate regions are
separated beyond some threshold (distance, or "different domain / different Tier-1 approach node");
otherwise fall through to the single super-sink search.

## What this is not
- Not a change to `Destination` semantics — "nearest of any region" is already the contract. This is
  about *how* the search realizes it (parallel/exact vs single/approximate), and only for disparate
  endpoints.
- Not the multi-endpoint super-sink itself (that exists and remains the right tool for co-located
  regions). This layers exact per-endpoint concurrency on top for the disparate case.

## Open questions
- Do we ever want *true* per-endpoint answers (return a route to each of the N, not just the min)?
  Not for `/nav`, but a future "show me all nearby banks" feature might.
- How to prioritise fan-out under a tight worker pool: cheapest-estimate-first, round-robin, or all-at-once?
- Should the branch-point detection live in `SearchImpl` (drive the loop) or in `Tier1Graph`
  (expose "competitive unsolved edges")?
