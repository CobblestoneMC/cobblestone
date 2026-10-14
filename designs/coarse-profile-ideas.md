# Coarse profiles: where the design landed (2026-10-06)

## Goal
COARSE must beat RUNNING_AVERAGE before #24 merges. Measured on held-out captures at the production
weight (2.0):
- it solves at least as many routes;
- its mean excess path cost is lower;
- it expands no more than 1.5× RUNNING_AVERAGE's nodes;
- about 90% of `verify` samples beyond 64 blocks land in 0.8–0.9 of the true cost. This is flexible:
  some samples outside the band is fine, as long as the spread stays low.

## What measurement taught us
- **Slice coverage can't see connectivity.** It measures what share of slices contain a medium, not
  whether the open parts connect.
  - CHEAPEST (optimistic) never over-estimates, but sits at 0.28 in caves.
  - SHARED over-estimates up to 2.5×.
  - SOLID (mining priced through fully-solid slices) lifts the floor, but over-estimates on 20% of
    samples.
  - Calibration shifts this error; it can't remove it.
- **Underground mining was badly under-priced:** a flat 1.4 s/block against about 4.7 s/block for
  stone. MineMode only moves in the 4 cardinal directions, and per step it breaks:

  | Step | Blocks broken | Plus |
  | --- | --- | --- |
  | Up | 3 | a walk × √2, and one block of sideways travel for free |
  | Flat | 2 | a walk |
  | Down | 1 | a fall |

- **Half-section slicing (rejected):** it fixes a symptom of the half-and-half edge split, and
  doesn't compose up an LOD pyramid.

## Options considered
1. **Coverage + cost per capability, cheapest-first blend, a few fitted constants.** Simple, but
   structurally blind to walls and tunnels.
2. **Neural nets per capability with a blend net, trained end to end.** Opaque, data-hungry, and
   extrapolates poorly to superflat worlds and player builds. Rejected.
3. **Face-to-face crossing costs. Recommended.**

## Recommended: face-to-face profiles (option 3)
- **Per section:** a 6×6 table of the cheapest cost from each face to each other face. It's computed
  by a small in-section search using the profile's *own* simplified physics, independent of the
  Modes:
  - standable = solid floor + 2 blocks of headroom; step up 1, drop up to 3;
  - swim in water; boat on navigable water;
  - dig at hardness × tool rate;
  - soul sand costs more unless the agent has Soul Speed.
- **The coarse search runs over faces,** not section centres. That removes the half-and-half split.
- **LOD pyramid:** a parent's table comes from a search over its 8 children's tables (HPA*). The
  same operation works at every level.
- **Fitting:** expect an estimate a little under 1.0, since each section picks its best entry
  independently. One global scale factor then targets 0.85.
- **Validation:** the Modes are used only as the yardstick, through `verify`. The bench flags drift
  between the profile physics and MovementCosts.
- **Costs:**
  - CPU per section, per distinct capability set; cache them.
  - A second, simpler physics model to keep loosely in sync with the Modes.
  - One node per face is optimistic by up to about a section width. Split each face into its open
    regions later if it matters.

## Agent model (replaces Medium)
- **Form**, exactly one: `PLAYER`, `RIDING_HORSE`, later `IN_BOAT`. Decides body size and which rules
  apply at all.
- **Capability**, any set: `MINE_HAND`, `MINE_SHOVEL`, `MINE_PICKAXE`, `BOAT`, `FLY`, `SOUL_SPEED`,
  `DEPTH_STRIDER`, … Each switches rules on or changes their cost. A no-mining flag removes the
  mining capabilities.
- Ice needs no medium of its own; it's just cheaper walking. A capability exists only where an agent
  condition changes cost or passability.

## Plan
1. Capability model and profile physics rules.
2. 6×6 face tables.
3. Coarse search over faces.
4. `verify` and `sweep` against RUNNING_AVERAGE on held-out captures.
5. Delete the slice-coverage profile, the SHARED/CHEAPEST/SOLID blends and the fallback rate.
6. New biome captures (ice, desert, swamp, badlands, deep dark, ravines, more nether, player
   builds), so there's held-out terrain to test on.

## Branch state when this was written
- **Pushed to #24:** up to "Default the heuristic weight to 2.0".
- **Committed, not pushed:**
  - the loadout trim: player, flyer, player-with-boat on surface, player-no-pickaxe on 3 routes;
  - `verify` reporting the band, and sampling along Dijkstra's path when the weighted search fails.
- **Uncommitted, a prototype to discard or park:** the SOLID blend, half-section open coverage, and
  `CoarseCostTest`.
- **Uncommitted data change:** `needed-chunks.yml` grew `the_nether/distance` from 42 to 44 chunks,
  so that route needs re-capturing.
- **Open:** baselines need re-accepting with the configuration stamp. #28 (design/ removal) and #27
  (RUNNING_AVERAGE never boards a boat) are separate.

## Regions (2026-10-13): built instead of the face tables
`RegionGraph`, `RegionSearch`, `RegionHeuristic` (`search.algorithm.heuristic: REGIONS`). Now the
default; the section profiles, CoarseSearch and the blends above are deleted, and the face tables
were never built.
- **Graph:** each chunk column is classed per cell (stand, soul, water, climb, air, solid) and
  flood-filled into regions per section. Regions are capped at a 1.5× walking detour, so a
  straight-line price stays honest. Portals join regions inside a column, and across column seams
  once both are read. Falls of up to 3 blocks (32 into water) are one-way portals. The graph knows
  nothing about the agent; `RegionCost` decides what each class costs and whether it is usable.
  Rock is always usable, at the fallback rate without a pickaxe, so the graph stays connected.
- **Search:** Reverse Resumable A*, run backward from the goal and focused on the fine search's
  origin, resumed only when a query needs it. Each portal's label carries the point its route
  crosses at, so diagonals are not priced as staircases. Answers are final when given.
- **Measured at w=2.0, scale 0.85, 58 runs:** 58/58 solved (COARSE 50, RUNNING_AVERAGE 48).
  On the 48 runs all three solved: 685 nodes against 1,913 and 1,872; paths +1.3% over the best
  any of them found, against +3.6% and +13.8%; about half COARSE's chunk columns; the sweep took
  90 s against COARSE's 131 s. `verify` on cave-lateral, vertical-small, along-river and
  hilly-jungle: unscaled estimates 0.7–0.95 of Dijkstra's cost and never above it. COARSE sits at
  0.16–0.48 in the nether climb and the cave.
- **Weak spots:** a plain player on `ocean` (+11.8% against COARSE), `vertical-small` (+12.4%) and
  `cave-lateral` (+10.3%). The estimate is not too high on any of these; a near-exact estimate
  under weight 2 commits early. Boarding a boat (1 s) is not priced, and the boat runs expand more
  nodes than the others.

### Tuning sweeps (2026-10-13), after the uniform-section and Manhattan-detour build change
- **Weight**, scale 0.85, on the 55 runs every weight solved: 1.5 expands 2,754 nodes for +0.2%
  and solves 55/58; 1.75 expands 1,365 for +1.8%, 58/58; 2.0 expands 952 for +3.0%, 58/58. An
  earlier full grid (1.0–3.0, before the build change) put 1.0 at 27/58 solved and 3.0 at +13.1%.
  The default stays 2.0. Scale and weight multiply, so sweep them as one product.
- **Detour ratio** (`-Dregions.detour`), w=2.0: 1.25 → 715 nodes, +1.9%; 1.5 → 730, +1.4%;
  2.0 → 743, +1.5%; 3.0 → 758, +1.5%. Flat: once the cap measures Manhattan against Manhattan
  it hardly bites. It stays 1.5.
- **Where the time goes**, whole sweep at w=2.0: graph building 31 s of 53 s, search 14 s,
  reading columns and joining seams the rest. Graphs average 21 MB per run; the largest was
  142 MB, a flyer on `cave-to-surface`.
- **Region census:** on `along-river` about two-thirds of solid regions touch at most two others
  (tree leaves and logs isolated in the air). In `cave-lateral`, 5,000 of 16,500 regions are
  climbables, mostly cave vines and lichen breaking up the floor. Both are cheap targets.

### The scale folded into the weight (2026-10-14)
The 0.85 scale on region estimates is gone; the default weight is 1.7, the old product. They
are not quite the same search: the fine search eases its weight to 1 over the last 64 blocks,
so near the target the estimate is now taken at 1.0 where it was taken at 0.85. All 58 runs, no
disk model, cold:

| Weight | Solved | Nodes (geometric mean) | Paths over best | Worst | Mean ms |
| --- | --- | --- | --- | --- | --- |
| old 2.0 × 0.85 | 58 | 1,005 | +1.3% | +8.9% | – |
| 1.5 | 58 | 1,146 | +0.1% | +0.8% | 1,321 |
| 1.6 | 58 | 924 | +0.8% | +6.4% | 1,275 |
| 1.65 | 58 | 852 | +1.1% | +8.9% | 1,260 |
| **1.7** | 58 | 800 | +1.5% | +8.9% | 1,236 |
| 1.75 | 58 | 757 | +1.8% | +12.3% | 1,228 |
| 2.0 | 58 | 600 | +4.4% | +23.0% | 1,103 |

Time hardly moves with the weight: building the regions dominates a cold run. Not to be fitted
more finely until the new scenarios are in.

### Shared, persistent store (2026-10-13)
Columns are built once into a `RegionStore` per world, shared by every search, written to
`regions/` in 32x32-column files, and invalidated on block change. `bench sweep --warm` measures
a restart: one run fills the cache, and a new store reads it back.

Server time per run, w=2.0, all 58 runs, mean / median / p90 / max (ms), CPU plus modelled wait:

| Disk | RUNNING_AVERAGE | REGIONS cold | REGIONS warm (after a restart) |
| --- | --- | --- | --- |
| none | 404 / 52 / 1,707 / 3,148 | 909 / 328 / 2,123 / 8,414 | 534 / 136 / 1,090 / 6,021 |
| NVMe | 470 / 85 / 1,969 / 3,144 | 1,196 / 464 / 2,772 / 10,113 | 556 / 147 / 1,190 / 6,080 |
| SATA | 470 / 89 / 1,852 / 3,124 | 1,199 / 463 / 2,793 / 10,162 | 551 / 146 / 1,185 / 6,000 |
| busy | 543 / 109 / 2,197 / 3,287 | 1,479 / 612 / 3,334 / 12,105 | 576 / 173 / 1,208 / 6,076 |
| spinning | 1,015 / 330 / 3,465 / 4,154 | 3,554 / 1,508 / 8,650 / 25,993 | 735 / 251 / 1,696 / 6,423 |

- Paths and nodes are the same cold or warm, and under every disk model.
- Warm SATA, mean per run: reading and decoding columns 195 ms, seams 43, region search 196,
  fine search 92, disk wait 20. A stored column is 6.4 KB on disk.
- The slowest warm run is a flyer on `cave-to-surface`: 6.0 s, of which region search 3.3 s
  (906,000 portals settled over 2,100 columns, for a fine search that touched 49 chunks) and
  reading columns 1.4 s. The backward search's reach, not building, is now the cost to cut.
- Region memory per search (its graph plus the columns it used): median 12 MB, mean 27 MB, p90
  62 MB, max 179 MB on that same route.
- Disk reads in the bench are real reads of files just written, so they come from the OS cache;
  only chunk reads go through the modelled disk.

## Other measured facts worth keeping
- A seed doesn't reproduce blocks: re-captures differ in decorations. Baselines are tied to the
  capture they measured (`capturedAt` stamp).
- Weight 2.0 beats 1.5 under COARSE for a regular player: 18/22 routes solved against 15/22, and
  about half the nodes, for +5.4% against +1.6% excess cost.
- Dijkstra can't verify `deep-cave`, `cave-to-surface`, `cliff-up` or `mountain-peaks-2` within 4M
  cells, or any flyer route within 8 GB.
