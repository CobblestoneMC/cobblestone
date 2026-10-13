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
`RegionGraph`, `RegionSearch`, `RegionHeuristic` (`search.algorithm.heuristic: REGIONS`).
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

## Other measured facts worth keeping
- A seed doesn't reproduce blocks: re-captures differ in decorations. Baselines are tied to the
  capture they measured (`capturedAt` stamp).
- Weight 2.0 beats 1.5 under COARSE for a regular player: 18/22 routes solved against 15/22, and
  about half the nodes, for +5.4% against +1.6% excess cost.
- Dijkstra can't verify `deep-cave`, `cave-to-surface`, `cliff-up` or `mountain-peaks-2` within 4M
  cells, or any flyer route within 8 GB.
