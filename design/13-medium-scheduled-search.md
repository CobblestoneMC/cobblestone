# Odyssey — Medium-Scheduled Tier-2 Search (design)

**Status:** design agreed, not yet implemented. Introduces a pluggable **`OpenNodeExpansionPolicy<M>`**
seam over the Tier-2 A\* frontier and a new **medium-scheduled** policy that stops a locally-fast
"medium" (water, air, …) from luring the search into flooding a dead pocket. The primary near-term
goal is *modularity for A/B tuning in a live world*; medium-scheduling is the first non-trivial
policy we want to test against the current single-queue baseline. The medium is a first-class,
generically-typed field on `Movement` (§"Where the medium lives") — decided in favor of stronger
typing over the lower-churn alternative.

## The problem, precisely

Tier-2 is A\* over `(cell, TraversalState)` with `f = g + heuristicWeight · h`, where the default
"fast" heuristic (`RunningAverageHeuristic`, see `03`) projects remaining cost as *a running average
of recent per-block cost × straight-line distance to goal*. That running average is the trap: inside
a **locally-cheap medium** it stays low, so a huge lateral swath of that medium has low `f` and gets
exhausted before the search commits to the more expensive medium the real route needs.

**Island example.** You stand on an island; shore stretches horizontally; the goal is a little inland.
Boating over water is cheaper per block than walking. From any water cell near shore, `g` barely
grows (water is cheap) and `h ≈ small_cost · distance` — and moving *laterally* along the shore
barely changes the distance to an inland goal. So `f` stays low across a wide band of ocean, and A\*
drains the ocean before giving in and walking inland. The Nether shows the mirror image: the real
route is to **mine** toward the goal, but there is so much walkable ground to fan into (all of it
cheaper per block than mining) that the estimate stays too optimistic about "we'll surely walk there"
and defers breaking blocks far too long.

The pathology is **not** a cost-correctness bug — `g` is exact, and the repair/restriction machinery
is untouched. It is an **expansion-order** bug: the frontier is ordered so that a fast medium's dead
lateral pocket outranks the slow medium's genuine progress. The right altitude to fix it is therefore
*which node we pop next* — a scheduler over one A\* graph, not a change to costs, closed set, or
optimality of `g`.

## Core idea: bucket the frontier by medium, demote a medium that stalls

A **medium** is the algorithm-side cost-regime label of a step (§"Where the medium lives"). Partition
the frontier into one priority queue **per medium**. A meta-policy decides which bucket to pop from:

1. **Default:** pop from the *unlocked* bucket with the lowest `peek().f` — i.e. behave like ordinary
   A\* across mediums when nothing is misbehaving.
2. **Stall detection (the fix):** per medium, track the **best (smallest) straight-line
   distance-to-goal ever reached in that medium** — its *watermark* — and a counter of *expansions
   since the watermark last improved*. Each real expansion out of a medium increments its counter;
   an expansion that beats the watermark by at least `minProgress` (δ) resets the counter and lowers
   the watermark. When the counter hits `stallLimit` (e.g. 1000), **lock** that medium.
3. **Locked mediums are skipped** by the default pop — the search "pops up" to the next-best unlocked
   medium.
4. **Thrash-safe unlock:** while a medium is locked we keep expanding others, which constantly
   generate neighbors *back into* the locked medium. Do **not** unlock on any such neighbor — only
   unlock when a newly-added node in that medium **beats its watermark by δ** (genuine new progress,
   not lateral re-entry). Then reset its counter and resume considering it.
5. **Completeness fallback:** if every non-empty medium is locked, **unlock them all** (optionally
   raising `stallLimit`) and fall back to plain lowest-`f`. The scheduler may never itself decide the
   search has failed — `UNREACHABLE`/`LIMIT_EXCEEDED` stay owned by `Tier2Search`.

### Why the watermark definition is load-bearing

Measuring progress as **distance-to-goal getting smaller**, not *blocks traversed*, is what makes a
fixed `stallLimit` robust to medium *size*:

- **Legitimately large fast medium** (goal is genuinely across a wide ocean): every step toward it
  lowers the watermark, so the counter keeps resetting and the medium is **never** locked. Good —
  boating across is the right answer and we don't sabotage it.
- **Dead lateral pocket** (island case): lateral ocean steps don't beat the watermark, the counter
  climbs to `stallLimit`, water locks, and the search commits to walking inland. Good.

A naive *consecutive-expansions* counter fails the island case: a diagonal shoreline occasionally
yields a node a hair closer to goal, resetting a consecutive counter forever. Requiring a **δ
improvement of a persistent watermark** to reset closes that. δ also gates the unlock (step 4), so
the same knob prevents lock/unlock thrash.

### What this costs us

Locking can discard the true optimum — e.g. an optimal route that must thread a long *goal-away*
detour through one medium (a tunnel curving away before curving back) gets that medium locked and
abandoned. For a player-facing nav plugin that is an acceptable trade (we already ship the
non-admissible `RunningAverageHeuristic`), but it must be **named**: medium-scheduling is
**bounded-suboptimal**, and it will break the exact-path assertions in `Tier2SearchTest`. Those tests
either pin the `single-queue` policy or use worlds small enough that no medium reaches `stallLimit`.

## The seam: `OpenNodeExpansionPolicy`

Abstract the frontier behind an interface owned by `Tier2Search`, so policies are plug-and-play and
A/B-testable from config in a live world. It must preserve the existing lazy-deletion contract:
staleness stays in `Tier2Search.loop()` (it needs `node.cost`), the policy only orders.

```java
/** Owns the Tier-2 frontier and decides expansion order. Not thread-safe; only pump() touches it.
 *  `M` is the medium type — opaque to core, used only as a bucket key (equals/hashCode). */
interface OpenNodeExpansionPolicy<M> {
  /** Offer a (possibly duplicate) frontier entry. `medium` is its incoming edge's medium;
   *  `goalDistance` is straight-line distance from its cell to the target region. */
  void add(Entry entry, M medium, double goalDistance);

  /** The next entry to try, honoring medium locks + fallback; null iff the frontier is empty.
   *  May return a stale entry — the caller re-checks `entry.currentCost() == node.cost`. */
  Entry poll();

  /** True iff no entries remain anywhere (locked or not). */
  boolean isEmpty();

  /** Feedback for a *real* expansion (a poll the caller did not discard as stale), so a stateful
   *  policy can advance its per-medium watermark/stall counters. */
  default void onExpanded(M medium, double goalDistance) {}
}
```

`Entry` (already a `Tier2Search` record: `key`, `currentCost`, `estimatedTotalCost`) is unchanged;
the policy receives `medium`/`goalDistance` alongside it rather than swelling the record. `M` is
opaque to core — the policy uses it only as a bucket key (`equals`/`hashCode`) — and is bound by the
same generic `M` threaded through `Movement`/`Mode`/`Tier2Search` (§"Where the medium lives").
`SingleQueueExpansionPolicy` ignores its `M` entirely.

### Wiring into `Tier2Search`

Replace the raw `PriorityQueue<Entry> open` at its four touch-points:

- **seed** (constructor): `policy.add(startEntry, START_MEDIUM, dist(start))` — the start has no
  incoming edge, so it goes in a default/never-locked medium.
- **`loop()`**: `open.isEmpty()` → `policy.isEmpty()`; `open.poll()` → `policy.poll()`; after the
  staleness check passes and the node is a real expansion, call
  `policy.onExpanded(node.bestEdge == null ? START_MEDIUM : node.bestEdge.medium(), dist(node))`
  right where `heuristic.observe(...)` already fires.
- **`relaxAll`**: `policy.add(entry, movement.medium(), dist(cell))` when a neighbor becomes a new
  best.
- **`repairFrom`**: `policy.add(entry, node.bestEdge.medium(), dist(node))` for each re-parented,
  still-open node.

`START_MEDIUM` is a sentinel `M` for the seed node (no incoming edge); it lives in a never-locked
bucket. For the Minecraft `MinecraftMedium` enum, add a `GROUND`-like default or a dedicated `START`
constant.

`dist(cell) = cell.distance(target.nearestBoundaryCell(cell))` — pure geometry, deliberately **not**
the cost heuristic, so a cheap medium's low `h` can't corrupt the progress measure.

## Where the medium lives

The medium is a Minecraft-specific enum of cost regimes, one per mode in practice — not a hard rule,
but for our modes it holds: `WalkMode` emits only `WALK`-medium edges, `SwimMode` only `SWIM`, etc.
Suggested `MinecraftMedium`: `GROUND` (WALK/JUMP/FALL/CLIMB/door), `WATER` (SWIM), `BOAT`, `AIR`
(FLY), `MINE`, `HORSE`. Grouping is by *shared cost profile*, which is why WALK+JUMP+FALL share
`GROUND`. Core never enumerates these — it only buckets by them.

**Decision: the medium is a first-class, generically-typed field on `Movement`.** We accept the
wider refactor for the stronger typing — the compiler enforces that every edge declares its medium,
rather than a `Function<T, M>` extraction that could silently misclassify. Concretely:

- `Movement<T>` → **`Movement<T, M>`**, gaining an `M medium()` component (and the convenience
  constructor keeps the `restricted`-defaulting overload). The medium sits alongside the payload `T`,
  not inside it — unlike `MinecraftStepType`, which stays in the payload — because core must read the
  medium as a bucket key without understanding Minecraft.
- `Mode<A, T, D>` → **`Mode<A, T, D, M>`**; `Mode.step` returns `FutureOr<Collection<Movement<T, M>>>`.
- `Tier2Search<A, T, D>` → **`Tier2Search<A, T, D, M>`**, holding an
  `OpenNodeExpansionPolicy<M>` and reading `movement.medium()` at the four wiring points above.
- The generic cascades through **`SearchImpl`** and the Tier-1 types that name the mode/movement
  parameters, plus every concrete mode class (`WalkMode`, `SwimMode`, …) and the test fakes. Tier-1's
  Dijkstra never *uses* `M`, but it must thread the type parameter through.

Minecraft binds `M = MinecraftMedium`; each mode stamps its medium via the `move(...)` helper in
`AbstractMinecraftMode`. Core stays medium-agnostic — `M` is only ever a map key. Because this widens
signatures across `core` and `minecraft/core`, land the generic-threading refactor as its own
step (no behavior change — `SingleQueueExpansionPolicy<M>` ignores `M`) *before* adding the
medium-scheduled policy on top.

## Policies to ship (for A/B)

All selectable from config so we can tune against real start/goal pairs, modes, and obstacles without
recompiling:

1. **`SingleQueueExpansionPolicy<M>` (baseline / default).** One heap by `f`; ignores `M`. With
   `heuristicWeight = 1` it is exact A\*; with `> 1` it is weighted A\* (the cheap, medium-blind
   greedy baseline the new policy must beat). This is today's behavior, so it is also the safe
   fallback and what the exact-path tests pin.
2. **`MediumScheduledExpansionPolicy<M>` (new).** The bucketed watermark scheduler above. Params:
   `stallLimit` (default ~1000), `minProgress` δ (default a few blocks), fallback `stallLimit`
   multiplier, and tie-break among unlocked buckets (`lowest-peek-f`, default).
3. **(optional extremes for the sweep)** `GreedyBestFirstPolicy<M>` (`f = h` only) and a pure
   `weighted` shorthand, to bracket how much of the win is just "be greedier toward the goal."

### Config / settings

Extend `SearchSettings` (surfaced in `config.yml`, consumed where `Tier2Search` is constructed today
alongside `heuristicWeight`, `runningAverageWidth`, `maxCellsVisited`):

```yaml
search:
  expansion-policy: single-queue        # | medium-scheduled | greedy
  heuristic-weight: 1.0
  medium-scheduled:
    stall-limit: 1000
    min-progress: 3.0                    # blocks the watermark must improve to reset/unlock
    fallback-stall-multiplier: 4
```

## Interactions

- **Restrictions / incremental repair.** Untouched — `g`, the closed set, `parents`/`children`, and
  `repairFrom` are unchanged; repaired nodes just re-enter through `policy.add`. A repaired node's
  medium is `mediumOf(node.bestEdge)` (or `START_MEDIUM` when `bestParent == null`). Stale per-bucket
  entries are discarded by the same `currentCost != node.cost` check, so lazy deletion is per-bucket
  but otherwise identical.
- **`RunningAverageHeuristic`.** Complementary. The heuristic still shapes `f` within a bucket; the
  scheduler decides *between* buckets. Note the interplay: a per-medium average would be a natural
  future refinement (each bucket projecting with its own medium's cost), but is out of scope here.
- **Multi-endpoint / parallel legs (`12`).** Orthogonal — the policy is per `Tier2Search`, so each
  parallel leg carries its own scheduler.
- **Tier-1 optimism.** Unchanged; Tier-1 still uses the admissible lower bound. Bounded-suboptimal
  Tier-2 legs already feed the recalc loop (see `12`), and medium-scheduling only widens that
  suboptimality within the same handshake.

## Testing & tuning

- **Unit-test the policy in isolation** with synthetic `(medium, goalDistance, f)` entries: lock
  after `stallLimit` no-progress expansions; watermark reset on δ-progress; unlock only on δ-progress
  re-entry (no thrash); fallback unlock-all when every non-empty bucket is locked; `single-queue`
  reproduces plain-A\* order exactly.
- **`Tier2SearchTest`** keeps exact-path assertions on `single-queue`; add medium-scheduled cases on
  the island and Nether-wall fixtures asserting the frontier commits to the correct slow medium
  (assert *bounded* optimality / expansion-count reduction, not exact optimum).
- **Live A/B harness.** Instrument each solve with `policy`, `expandedCount`, path cost, and wall
  time; sweep policies/params over a fixed battery of start/goal pairs in a real world. This is the
  primary deliverable's payoff and dovetails with the planned metrics (`10`) and the future
  visualization tool — the visualizer should color the frontier by medium and mark lock/unlock events
  so the stall dynamics are legible.

## Open questions

- **`stallLimit` units:** a flat block count, or scale it (e.g. with straight-line start→goal
  distance) so short hops don't over-explore and long hauls aren't starved?
- **Per-medium heuristic averaging:** worth folding into the same policy later, or keep the heuristic
  and the scheduler strictly separate?
- **Watermark scope:** global-per-medium (as specified) vs per-medium-per-region-of-entry. Start
  global; revisit only if a medium with several disjoint productive pockets under-explores.
