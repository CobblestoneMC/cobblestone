# Regions: the fine search's heuristic

`search.algorithm.heuristic: REGIONS`, the default. Classes in
`project/minecraft/core/src/main/java/org/cobblestonemc/minecraft/lod`.

## The idea
The fine search (`Tier2Search`) needs, for every cell it opens, an estimate of what the rest of
the route costs. RUNNING_AVERAGE extrapolates from the steps already taken, which knows nothing of
the terrain ahead. REGIONS reads the terrain ahead.

- **Regions.** Each chunk column is classed cell by cell (stand, soul sand, soul soil, water, water
  surface, climb, air, solid) and flood-filled into connected regions, one class each, within each
  16³ section. A region is split
  where walking inside it would bend more than 1.5x its straight line (Manhattan against
  Manhattan), so pricing a crossing as a straight line stays honest. Uniform sections skip the fill.
- **Portals.** Every contact between two regions, as one box: faces touching, a walking step up
  or down, and falls of up to 3 blocks (32 into water), which are one-way. Inside a column they are
  built with it; across columns they are built per search at the seams.
- **Agent-independent.** The graph knows terrain, not travellers. A `RegionClass` is only a tag;
  `RegionCost` is the agent, built from the player and the operator's exclusions, and says what
  each class costs it: soul sand is 2.5x ground on foot and fast in Soul Speed boots, and only a
  water surface takes a boat. A kind of terrain earns its own class only when some agent would pay
  differently for it. Rock is always usable, at the mining rate with a pickaxe and at a fallback
  rate without, so the graph stays connected.
- **Search.** Reverse Resumable A*: a backward search from the destination, focused on the fine
  search's origin, resumed only when a question needs it. A settled portal knows its true cost to
  the destination over the graph, so answers are final when given. Each portal's label carries
  the point its route crosses at, so a diagonal is not priced as a staircase.
- **Area destinations.** The search starts from every usable region inside the destination that
  connects to its nearest cell, found by a flood through the region graph that stops at the
  destination's edge (up to 4,096 regions). A town a river enters from the far side is reached by
  the river.
- **Weight.** The estimate is used as it is, under the search weight, 1.7 by default. The weight
  eases to 1 over the last 64 blocks before the target.
- **Loaded chunks stay in memory.** A chunk's column is built in the background when the chunk
  loads, and kept in memory while it stays loaded. Searches near players, such as the path back to
  a trail, never read a column from disk.

## Storage
- **One `RegionStore` per world,** shared by every search and agent: memory, then disk, then
  building from the chunk. Columns of loaded chunks are always in memory; the rest are kept up to
  128 MB by default, least recently used out.
- **On disk,** `regions/<world>/r.<x>.<z>.crg`, 32x32 columns a file, each record deflated, about
  6.4 KB a column. A file is replaced whole through a temporary file. A header that does not match
  the format, classifier, detour ratio or world height reads as empty and is rebuilt.
- **Invalidation:** every block change drops its column; the next search to need it rebuilds it.
  Paper listens for each kind of change event, Sponge for its one. Offline edits are not seen;
  the admin docs say to delete `regions/` after them.
- **Writes** every 60 s, on world save and on shutdown. Unwritten columns survive eviction.
  Chunks that load are built in the background through a bounded queue; when it is full, the column
  is built when a search first needs it, and then kept like the others.

## Measurements
All on the stonebrick corpus: 22 routes, 58 runs over the loadouts player, flyer,
player-with-boat and player-no-pickaxe. "Over best" is the path cost against the cheapest any
configuration found.

### REGIONS against RUNNING_AVERAGE (2026-10-13, weight 2.0 x scale 0.85)

| | RUNNING_AVERAGE | REGIONS |
| --- | --- | --- |
| Solved | 48/58 | 58/58 |
| Nodes expanded, geometric mean | 3,887 | 1,005 |
| Cells opened, mean | 70,605 | 15,906 |
| Over best: mean / median / p90 / worst | +17.9% / +5.8% / +55.6% / +117.2% | +5.5% / +4.2% / +11.7% / +26.1% |

`verify` (estimates against Dijkstra) on cave-lateral, vertical-small, along-river and
hilly-jungle: estimates 0.7-0.95 of the true cost, never above it.

### Weight (2026-10-14)
The 0.85 scale on estimates was folded into the weight: 1.7 is the old product. Near the target,
where the weight eases to 1, the estimate is now taken whole. All 58 runs, cold, no disk model:

| Weight | Solved | Nodes | Over best | Worst | Mean ms |
| --- | --- | --- | --- | --- | --- |
| old 2.0 x 0.85 | 58 | 1,005 | +1.3% | +8.9% | - |
| 1.5 | 58 | 1,146 | +0.1% | +0.8% | 1,321 |
| 1.6 | 58 | 924 | +0.8% | +6.4% | 1,275 |
| 1.65 | 58 | 852 | +1.1% | +8.9% | 1,260 |
| **1.7** | 58 | 800 | +1.5% | +8.9% | 1,236 |
| 1.75 | 58 | 757 | +1.8% | +12.3% | 1,228 |
| 2.0 | 58 | 600 | +4.4% | +23.0% | 1,103 |

Time hardly moves with the weight: building regions dominates a cold run. Not to be fitted more
finely until the new scenarios are in.

**The endgame taper stays.** Without it, at 1.7: 766 nodes against 800, but +2.5% over best
against +1.5%, and the worst route +22.5% against +8.9% (cliff-down, a target in a pocket).

### Area destinations (2026-10-14, weight 1.7)
Every route's destination made a box of radius 24, seeded from its nearest cell (before) or from
every region inside it (now). Over the 54 runs both solved:

| Seeded from | Nodes | Over best, mean | Worst |
| --- | --- | --- | --- |
| the nearest cell | 846 | +14.5% | +393% |
| the whole area | 918 | +0.7% | +19.0% |

The big gains are where the cheapest way in is not the nearest edge: deep-cave for a flyer 117.4
to 23.8, for a player 157.1 to 82.0; cliff-up 33.3 to 18.2; cliff-down 44.8 to 31.3. Point
destinations are unchanged node for node.

### Detour ratio (2026-10-13, weight 2.0)
1.25: 715 nodes, +1.9%. 1.5: 730, +1.4%. 2.0: 743, +1.5%. 3.0: 758, +1.5%. Flat; 1.5 stays.

### Time and memory (2026-10-13, weight 2.0)
Server time per run, all 58 runs, mean / median / p90 / max in ms, CPU plus modelled disk wait.
"Warm" is after a restart, reading columns back from disk.

| Disk | RUNNING_AVERAGE | REGIONS cold | REGIONS warm |
| --- | --- | --- | --- |
| none | 404 / 52 / 1,707 / 3,148 | 909 / 328 / 2,123 / 8,414 | 534 / 136 / 1,090 / 6,021 |
| NVMe | 470 / 85 / 1,969 / 3,144 | 1,196 / 464 / 2,772 / 10,113 | 556 / 147 / 1,190 / 6,080 |
| SATA | 470 / 89 / 1,852 / 3,124 | 1,199 / 463 / 2,793 / 10,162 | 551 / 146 / 1,185 / 6,000 |
| busy | 543 / 109 / 2,197 / 3,287 | 1,479 / 612 / 3,334 / 12,105 | 576 / 173 / 1,208 / 6,076 |
| spinning | 1,015 / 330 / 3,465 / 4,154 | 3,554 / 1,508 / 8,650 / 25,993 | 735 / 251 / 1,696 / 6,423 |

- Warm SATA, mean per run: reading and decoding columns 195 ms, seams 43, region search 196,
  fine search 92, disk wait 20.
- Region memory per search: median 12 MB, mean 27 MB, p90 62 MB, max 179 MB.
- The bench's region-file reads come from the OS cache; only chunk reads are modelled.
- **The slowest routes** are long ones, and the cost is how far the backward search reaches. Its
  focus is the straight line to the origin at the agent's cheapest rate, so it settles every
  portal in an ellipse around that line whose half-width is about `D/2 * sqrt(r^2 - 1)`, where `r`
  is the route's cost over that line's. On mixed-biome-surface (925 blocks) a flyer reads 767
  columns and a player 1,052, while the fine search reads about 130 chunks.

## Tried and not adopted
- **Section profiles (COARSE).** Each 16³ section summarized by how much of it each medium
  covered. Coverage cannot see connectivity: estimates 0.16-0.48 of the truth in caves, or above
  it by up to 2.5x, depending on the blend. Removed.
- **Face-to-face tables per section.** Designed, never built: regions made them unnecessary.
- **Coarse tiles beyond a fine radius** (branch `whim/coarse-tiles-experiment`). 4x4-chunk tiles
  merged every set of same-class regions connected inside them, and a search used them beyond
  256 blocks of the destination. With every tile fine the 58 runs matched node for node; coarse,
  it was slower and no better:
  - real terrain is a patchwork of small regions of different classes, so merging within a class
    kept about 3,700 of a tile's 4,700 regions;
  - the few big merged air and rock regions gained 1,500-2,000 portals each, and relaxing a region
    costs its portal count per settled portal: on mixed-biome-surface the region search went from
    585 ms to 4,031 ms;
  - merging across section layers flattened hillsides: hilly-jungle +2.6% cost, nodes +45%.
  Merging across classes would compress, but that is what COARSE did.
- **Reading cell maps lazily.** Only the fine search's own columns need their cell maps, but the
  maps compress to almost nothing: regions, portals and edges are 92-98% of a stored column.

## Open
- The backward search's reach on long routes (the ellipse above). Candidates: a sharper focus than
  the cheapest-rate straight line, or a hierarchy of portal-to-portal costs per tile computed per
  cost model (agent-dependent, unlike the graph).
- Doors and gates are priced as rock to the region layer; the fine search opens them.
- Boarding a boat (1 s) is not priced.
- Disk reads should be measured on a real spinning disk; the bench's come from the OS cache.

## Other measured facts
- A seed does not reproduce blocks: re-captures differ in decorations. Baselines are tied to the
  capture they measured (`capturedAt`).
- Dijkstra cannot verify `deep-cave`, `cave-to-surface`, `cliff-up` or `mountain-peaks-2` within
  4M cells, or any flyer route within 8 GB.
