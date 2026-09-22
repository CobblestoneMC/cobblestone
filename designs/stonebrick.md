# Stonebrick — a benchmarking & visual-debugging platform for Cobblestone

Status: design draft (2026-09-21). Not yet implemented.

Stonebrick is a fourth "platform" alongside Paper and Sponge, except the world it reads is a file
captured from a real server rather than a live one. It exists so the search can be run
reproducibly, benchmarked tightly, and watched frame by frame.

---

## 1. Module layout

```
project/stonebrick/
  format/       :stonebrick-format      pure Java. Reader/writer for the .sbr capture format.
  copier/       :cobblestonecopier      Paper plugin. /cobblestonecopychunks and friends.
  platform/     :stonebrick-platform    PlatformApi / MinecraftWorld / Chunk / Block, IO delay model,
                                        deterministic scheduler, fake agent + restrictions.
  bench/        :stonebrick-bench       JMH benchmarks, scenario runner, metrics, result JSON, compare tool.
  visualizer/   :stonebrick-visualizer  replay viewer (WASD), reads recordings produced by bench.
```

Gradle names stay flat like the rest of the tree (`project(":stonebrick:format").name = "stonebrick-format"`).

Why `format` is its own module: the copier needs Bukkit and ships inside a plugin jar; the platform
must stay pure Java so benchmarks have no server on the classpath. Both need the same codec, and
the codec is the contract between them — a version mismatch there is the one bug that silently
produces wrong benchmark numbers, so it lives in exactly one place.

**Dependencies**
- `format` → nothing (Java 21, `java.util.zip` only).
- `copier` → `format`, `:paper-core` (compileOnly Paper API), shaded plugin.
- `platform` → `format`, `:core`, `:minecraft-core`.
- `bench` → `platform`, JMH, `:core`.
- `visualizer` → `platform`, `format`, LWJGL3.

### 1.1 Reconciling with the existing placeholders

`:core-test` and `:playground` already exist as empty stubs with this job description
("pure-Java test engine (fake worlds/modes)" and "JavaFX 3D visualizer"). Recommendation:

- **Delete** `core-test`. Note: the algorithms we use are not supposed to be complete (optimal) so 
  it would be difficult to maintain unit-testable solutions to at-scale navigation tests
- **Delete `playground`** and let `:stonebrick-viz` be the visualizer. Two visualizers will not both
  get maintained, and the recording-replay design below is strictly better than a live JavaFX hook.

---

## 2. Capture format: custom vs. copying `.mca`

**Recommendation: the custom format, and it is not close.** The reasons, in order of weight:

1. **`.mca` is not self-contained.** A region file is a pile of NBT whose block-state names, section
   layout, and data-version you can only interpret against a specific Minecraft version's registry.
   Reading it in a pure-Java benchmark module means either vendoring a registry snapshot or
   depending on server internals — which is precisely what `stonebrick-platform` must not do.
2. **You would be benchmarking the wrong thing.** Decoding NBT and inflating full block states is a
   meaningful per-chunk cost that has nothing to do with the search. A capture that is already in
   the shape `MinecraftChunk` wants makes the *only* controllable cost the IO delay you inject —
   which is the point.
3. **Size.** Blocks-only, no entities, no block entities, no biomes, no lighting, no heightmaps: on
   typical terrain expect 5–15× smaller than the equivalent `.mca`. That matters when a single
   long-haul scenario wants a 200×200-chunk corridor.
4. **Version drift cuts the other way from what you feared.** You worried that a custom format means
   re-copying when the format changes. But `.mca` changes *underneath you* on every MC update, and
   worse, a re-copy of `.mca` from an updated server silently changes your benchmark inputs. A
   captured `.sbr` is frozen: the same bytes forever, so a 2027 benchmark number is comparable to a
   2026 one. That is the whole value of a benchmark corpus.

**The one real risk with a custom format**, and how to kill it: baking derived block traits at copy
time means a change to `PaperBlock`'s trait logic makes every capture stale, and re-deriving needs a
server. Fix it by storing **both**:

- the canonical **block-state string** (`minecraft:oak_door[facing=east,half=lower,open=false]`), and
- the **derived trait record** as `PaperBlock` computed it at capture time.

Stonebrick reads the trait record by default — zero duplication of trait logic, exactly production
semantics. When trait logic changes, you re-derive *in place* from the strings using a **trait table**
the copier also dumps (`traits.json`: every block-state string the server knows → its trait record).
Re-deriving is then an offline `stonebrick-format` tool, no server required, and the block data
never has to be re-copied. Captures record the copier version and MC version so a stale one is
detected rather than quietly used.

---

## 3. The `.sbr` format (Stonebrick Region)

One file per capture job, holding an arbitrary rectangle of chunks from one world.

```
Header (fixed)
  magic          "SBRK"  4 bytes
  formatVersion  u16
  flags          u16          bit0 = per-chunk deflate
  worldKey       utf          "minecraft:overworld"
  environment    u8           OVERWORLD | NETHER | END | CUSTOM
  minY, maxY     i32, i32
  mcVersion      utf          "1.21.4"  (provenance only)
  copierVersion  utf
  capturedAtEpochMillis i64
  minChunkX, minChunkZ, chunkCountX, chunkCountZ  i32 ×4
  paletteOffset, indexOffset  i64 ×2

Global palette (one per file)
  count          i32
  entry[count]:  blockStateString  utf
  index 0 is reserved for AIR; index 1 for UNKNOWN (never-captured).
  Traits are NOT stored here — see traits.json below.

Chunk index (chunkCountX * chunkCountZ entries, row-major, absent chunks = offset 0)
  offset i64, compressedLength i32, uncompressedLength i32, crc32 i32

Chunk payload (deflated)
  sectionMask    i64          which 16-block sections are present (minY..maxY / 16)
  per present section:
    kind         u8           0 = uniform, 1 = packed
    uniform:  globalPaletteId i32
    packed:   localCount i32, localIds i32[localCount], bitsPerEntry u8,
              data i64[ceil(4096 / floor(64 / bitsPerEntry))]     (no straddling — Anvil 1.16+ style)
  absent section = all air.
```

`traits.json` maps each blockstate string to a trait record: a `traitBits` long packing every boolean
on `MinecraftBlock` (passable, solidTop, halfHeight, water, lava, climbable, scaffolding, dangerous,
supportsBoat, door, trapdoor, open, opensByHand, pressurePlate) plus a 6-bit enterable mask and a
6-bit exitable mask (one per `Direction`), alongside `breakTimeSeconds`, `speedFactor` and
`damagePerSecond`. It lives once per capture directory, not once per chunk file, and it is the
*only* thing that goes stale when trait logic changes — regenerating it never touches block data.

Notes:
- **Global palette, section-local indices.** The global palette is what makes the file small and
  lets the visualizer color by block identity across the whole capture. Section-local index lists
  are what get `bitsPerEntry` down to 4 for almost every section.
- **Per-chunk deflate, not whole-file.** Random access per chunk is required — the whole point is to
  simulate one disk read per chunk fetch, and to keep memory bounded when a capture is 2 GB.
- **CRC per chunk** so a corrupt capture fails loudly instead of benchmarking garbage.

Companion files per capture directory:
```
stonebrick/data/<capture-name>/
  overworld.sbr
  nether.sbr
  traits.json          every block-state string → trait record (for offline re-derivation)
  manifest.json        capture jobs that produced this: world, chunk rects, command lines
  scenarios/*.json     scenario definitions (§6)
```

**Storage.** Do not commit large captures. Commit a small `smoke` capture (a few hundred chunks,
single-digit MB) so CI can run something; keep the full corpus out of git behind a manifest with
SHA-256 per file, downloaded on demand (or Git LFS if you prefer, but a manifest + a `downloadCaptures`
Gradle task avoids LFS quota pain).

---

## 4. `cobblestonecopier` — the Paper helper plugin

Lives at `project/stonebrick/copier`, applies `cobblestone.paper-plugin-conventions`, depends on
`:stonebrick-format` and `:paper-core`.

### Commands

```
/cobblestonecopychunks <x1> <z1> <x2> <z2> [--name <capture>] [--blocks] [--world <key>]
/cobblestonecopychunks here <radiusChunks> [--name <capture>]
/cobblestonecopychunks manifest <file>          replays a saved capture manifest
/cobblestonemark <scenario> origin|dest         stamps your current position into a scenario stub
/cobblestonetraits                              dumps traits.json for every known block state
/cobblestonecopystatus | /cobblestonecopycancel
```

- Coordinates are **chunk** coordinates by default (as you specified); `--blocks` accepts block
  coordinates and rounds outward. `~` relative coordinates supported.
- `here <radius>` is the one you will actually use most while standing in the world.
- `/cobblestonemark` is the piece that makes scenario authoring bearable: stand at the start, run
  `/cobblestonemark cave-descent origin`, walk to the end, run `... dest`, and the plugin writes a
  scenario JSON stub with both positions, the world keys, and your current capability flags. Then
  `/cobblestonecopychunks manifest` fills in the terrain. Without this you will be transcribing
  coordinates by hand for forty scenarios.

### Behavior

- Runs asynchronously, reading chunks through the **same offline read path Cobblestone already uses**
  (`NMSChunkReader` / `MoonriseRegionFileIO`), falling back to `getChunkAtAsync` where internals are
  missing. Never loads chunks into the live server if it can avoid it.
- Rate-limited: a configurable chunks-per-tick budget so a 60 000-chunk capture does not stall the
  server. Progress reported every few seconds with an ETA.
- Guard rails: refuse a region above `maxChunks` (default 100 000 ≈ 1600×1600 blocks) without
  `--force`; refuse to overwrite an existing capture without `--overwrite`.
- Writes to `plugins/CobblestoneCopier/out/<capture>/`.

### Where blockstate → traits lives

**The rule: the copier may reach into `paper-core` as deeply as it needs to. Nothing else in
stonebrick may depend on `paper-core` at all.** The copier is a Paper plugin that only ever runs on
a live server; the benchmarking modules must stay pure Java with no server on the classpath. The
`.sbr` + `traits.json` pair is the boundary between them, and it is a one-way boundary.

So `traits.json` is written by the copier using **the real `PaperBlock`**, which means benchmark
blocks answer `isPassable()` / `breakTimeSeconds()` / `supportsBoat()` byte-for-byte as production's
do, with no second trait table to drift.

`PaperBlocks.of(...)` and `PaperBlock` are package-private. The copier reaches them with **one shim
class declared in package `org.cobblestonemc.paper`**, inside the copier module:

```java
package org.cobblestonemc.paper;   // deliberately: package-private access to PaperBlock

import org.bukkit.block.data.BlockData;
import org.cobblestonemc.minecraft.MinecraftBlock;

/** Bridges the capture tool to Paper's real block traits. Dev tooling; never shipped. */
public final class PaperBlockBridge {
  private PaperBlockBridge() {}

  public static MinecraftBlock of(BlockData data) {
    return new PaperBlock(data);
  }
}
```

Package-private access works across jar boundaries (there is no JPMS here, and the copier shades
`paper-core` anyway). This adds **no public API to `paper-core`** and changes no production code —
the split package lives entirely in a module that never ships. If `paper-core` renames or reshapes
`PaperBlock`, the copier fails to compile, which is exactly the loud failure we want.

Rejected alternatives, for the record:
- *A third hand-written trait table in stonebrick.* Viable — the codebase already has two
  independent copies (`PaperBlock` over Bukkit `Material`/`Tag`, `SpongeBlock` over Sponge
  `BlockType`/`Keys`, with near-identical `DANGEROUS`/`BOAT_SURFACES`/`ICE` sets), and sponge-core's
  `PaletteResolver` is exactly the seam for it. But the day someone fixes a trait in `PaperBlock`
  and not in ours, every benchmark number silently stops predicting server behavior and nothing
  fails. Avoiding that is the whole point.
- *Widening `paper-core` with an `@ApiStatus.Internal` accessor.* Unnecessary given the shim.
- *Lifting the canonical table into `minecraft-core`* so both platforms adapt to it. The honest
  long-term fix for the existing duplication, but a production refactor to serve a test harness.

Scope note: the copier emits traits only for blockstates that actually appear in a capture's palette
— a few hundred entries, not the ~28 000-state space.

---

## 5. `stonebrick-platform`

### 5.1 The seam implementations

| Type | Notes |
|---|---|
| `StonebrickPlatformApi implements PlatformApi<FakeEntity>` | resolves worlds by key from loaded captures, hands fetches to `SimulatedChunkIo`. |
| `StonebrickWorld implements MinecraftWorld` | mirrors `PaperWorld` exactly: owns a `ChunkProvider`, `scopedForSolve()` returns a copy with a fresh provider, equality by key. This is important — the per-solve chunk cache behavior is part of what you are benchmarking. |
| `StonebrickChunk implements MinecraftChunk` | decoded section arrays + global palette; `block()` is an array index into a shared `StonebrickBlock[]`, allocating nothing. |
| `StonebrickBlock implements MinecraftBlock` | one immutable instance per palette entry, all methods reading `traitBits`. |
| `StonebrickPlayer implements CobblestonePlayer` | capability flags straight from the scenario JSON. |
| `StonebrickScheduler implements MinecraftScheduler` / `Scheduler` | two flavors, §5.3. |
| `ScriptedRestriction implements Restriction` | a scenario-declared box that answers "impassable" after a configurable delay — this is how you benchmark the Towny rollback path without Towny. |

A chunk outside the captured rectangle answers `ChunkFetch.Failed.permanent()`; a chunk inside the
rectangle whose index entry is empty answers permanent too (never generated). A scenario may declare
a set of chunks that answer `transientFailure()` the first N times, to exercise retry handling.

### 5.2 The IO delay model

```java
public record IoProfile(
    Mode mode,                  // ZERO | SIMULATED | VIRTUAL
    long coldLatencyMicros,     // first read of a chunk this run
    long warmLatencyMicros,     // a chunk read again after eviction (OS page cache)
    double jitterSigma,         // lognormal multiplier on latency
    double microsPerKiB,        // throughput term, applied to compressed length
    int queueDepth,             // concurrent reads permitted; further reads queue
    long perQueuedReadMicros,   // extra cost each time the queue is saturated
    long seed) {}
```

Defaults ship as named profiles you can swap on the command line — `nvme`, `sata-ssd`, `spinning`,
`busy-server` (high jitter, low queue depth) — and any field is overridable, which is the "easily
configurable" knob you asked for. Profiles live in JSON next to the scenarios so a benchmark run is
reproducible from a file rather than from flags someone remembers.

**Three modes, and you want all three:**

- `ZERO` — no delay at all. Measures pure algorithm CPU. This is the number you iterate on when
  tuning the heuristic, because it has the least variance.
- `SIMULATED` — real sleeps on a scheduled executor. Highest fidelity, slowest, noisiest. Use for
  the "does this change actually help on a real server" confirmation runs.
- `VIRTUAL` — **the important one.** No actual sleeping: the harness maintains a simulated clock,
  a chunk fetch completes immediately but advances that clock by the modeled delay, and the search's
  deadline and metrics read the simulated clock. You get spinning-disk-realistic *behavior* (park
  counts, prefetch effectiveness, deadline pressure) at ZERO-mode speed, perfectly reproducibly.

⚠️ **VIRTUAL requires a small core change.** `Tier2Search` calls `System.currentTimeMillis()` directly
(deadline checks, `Tier2Metrics` park accounting) and arms its deadline with
`CompletableFuture.delayedExecutor`. Introduce a `TimeSource` interface in `:core`
(`long millis(); void schedule(Runnable, long delayMillis)`) defaulting to the system clock, threaded
through `SearchSettings` or the `Scheduler`. It is a mechanical change, ~20 call sites, and without
it deterministic benchmarking is not achievable.

### 5.3 Determinism

This deserves its own heading because **a nondeterministic benchmark cannot detect a 10% improvement.**
Three sources of nondeterminism today:

1. **Concurrency.** The search pumps on an executor, modes complete on arbitrary threads, verdicts
   land in a mailbox in arrival order. → Ship a `DeterministicScheduler`: single-threaded, FIFO
   task queue, virtual clock, drained to quiescence. Same task interleaving every run.
2. **Priority-queue ties.** `BY_ESTIMATE` breaks ties on `f` then on `-g`; beyond that, order is
   whatever the binary heap gives, which depends on insertion history. On near-uniform lattice
   costs that is an enormous tie group. → Add a monotonically increasing **insertion sequence
   number** as the final comparator key. This is a one-line core change with a real payoff: it makes
   expansion order a pure function of the input, so two algorithm variants differ only where they
   actually differ. (It may also marginally improve behavior by making the search FIFO within a tie
   group instead of arbitrary.)
3. **Hash iteration order.** `HashMap<Cell, Set<CellState>>` iteration feeds repair ordering. →
   Use `LinkedHashMap`/`LinkedHashSet` in the search, or at minimum verify repairs are order-insensitive.

Run every benchmark in both deterministic and realistic-concurrency modes; the first is for A/B, the
second is for "does it still work when threads are real".

---

## 6. Scenarios

A scenario is a JSON file, loaded by the bench, the tests, and the visualizer alike:

```json
{
  "id": "ow-river-orthogonal",
  "description": "Destination inland; a wide river runs across the straight-line route.",
  "tags": ["overworld", "water-trap", "tier2", "medium-aware"],
  "capture": "corpus-2026-09",
  "origin":      { "world": "minecraft:overworld", "x": 1240, "y": 68, "z": -310 },
  "destination": { "world": "minecraft:overworld", "x": 2015, "y": 71, "z": -288, "radius": 2 },
  "agent": { "canFly": false, "canGlide": false, "hasBoat": true, "enderPearls": 0,
             "permissions": ["cobblestone.mode.mine"] },
  "excludedSteps": [],
  "transitions": [],
  "restrictions": [ { "box": [1500, 60, -350, 1560, 90, -250], "verdict": "deny", "delayMillis": 40 } ],
  "settings": { "heuristicWeight": 1.5, "maxCellsVisited": 200000, "maxWallClockMillis": 60000 },
  "io": "sata-ssd",
  "expect": { "outcome": "success", "maxCostRatio": 1.35 }
}
```

`expect.maxCostRatio` is checked against a **golden reference solve** — the same scenario run once
with `Heuristics.zero()` (Dijkstra), unlimited budget, ZERO io, result cached in
`scenarios/golden/<id>.json`. Without this, "faster" changes that quietly produce worse paths look
like wins. Regenerating goldens is an explicit, reviewed Gradle task.

Tier-1 scenarios declare `transitions` (portal pairs with their costs), so the benchmark exercises
`SearchImpl`'s whole re-plan loop, not just one `Tier2Search` — as you asked.

---

## 7. The capture & scenario catalogue

What to go stand in and copy. Sizes are suggested capture rectangles; **capture a corridor with
generous margin, not a straight line** — a weight-1.5 search on awkward terrain routinely explores
0.3–0.5 × route length perpendicular to the straight line, and a search that hits the capture edge
gets `Failed.permanent()` and behaves like it hit a wall, which invalidates the run. A good rule:
capture a rectangle whose short side is `max(64 blocks, 0.6 × route length)` centered on the route.

### A. Overworld surface

| # | Scenario | What it exercises | Capture |
|---|---|---|---|
| A1 | Flat plains, 200 blocks | baseline; regression canary | 40×40 chunks |
| A2 | Plains, 1 000 blocks | linear scaling | 100×80 chunks |
| A3 | Plains/varied, 3 500 blocks | the long haul that currently exhausts the cell limit | 250×160 chunks |
| A4 | Rolling hills / mountain range crossing | vertical detours, step-up costs | 100×100 |
| A5 | Dense jungle or dark forest | leaves: passable-but-not-footing; huge branching | 60×60 |
| A6 | Swamp, shallow water + lily pads | constant water/land medium switching | 60×60 |
| A7 | Badlands/mesa | sheer walls, mine-vs-go-around | 80×80 |
| A8 | **River running orthogonal to the destination** | *the* motivating failure: boat medium flood-fills the river | 120×120 |
| A9 | Ocean crossing 1 200 blocks, boat in inventory | boat medium legitimately optimal | 120×120 |
| A10 | Same ocean crossing, no boat | swim cost, should prefer coastal walk | reuse A9 |
| A11 | Coastal: destination inland, river leads away from it | the trap in its purest form | 100×100 |
| A12 | Frozen ocean / ice sheet | `speedFactor > 1` as an attractor | 80×80 |
| A13 | Ravine crossing | narrow impassable gash; detour discovery | 50×50 |
| A14 | Village: destination inside a house | doors, fences, gates, endgame | 30×30 |
| A15 | Destination in a room whose door faces away from origin | `ENDGAME_RADIUS` weight taper | 20×20 |
| A16 | Destination atop a 40-block tower | vertical endgame, climb/pillar | 20×20 |
| A17 | Destination at the bottom of a covered pit | fall mode, one-way descent | 20×20 |
| A18 | Route crossing a claimed/protected region | `ScriptedRestriction` rollback with delay | 60×60 |

### B. Caves & underground

| # | Scenario | What it exercises | Capture |
|---|---|---|---|
| B1 | Surface → deep cave (y ≈ −50) via a natural entrance | descent, entrance discovery | 60×60, full height |
| B2 | Deep cave → surface (the reverse of B1) | genuinely harder: exit discovery from inside | reuse B1 |
| B3 | Cave → adjacent cave through solid stone | mine mode as the only route | 40×40 |
| B4 | Deepslate + lava lakes | danger weighting, `damagePerSecond` | 60×60 |
| B5 | Mineshaft complex traversal | long twisty low-branching corridors | 60×60 |
| B6 | Underground destination reachable only by mining | mine mode must engage | 40×40 |
| B7 | **Underground destination genuinely unreachable** | must fail *fast*, not burn 60 s | 40×40 |
| B8 | Cave → surface → different cave | medium alternation walk/mine/walk | 100×60 |

### C. Mining

| # | Scenario | What it exercises | Capture |
|---|---|---|---|
| C1 | Thin wall (3 blocks) between origin and destination | mining is obviously right | 20×20 |
| C2 | 200-block-thick granite massif, open route around it | **mine-mode trap**: going around is right; this is the `local/notes.md` complaint | 120×120 |
| C3 | Mountain where digging straight through genuinely wins | the opposite call | 80×80 |
| C4 | Mining route through a denied protection region | rollback + re-search | 60×60 |

### D. Nether

| # | Scenario | What it exercises | Capture |
|---|---|---|---|
| D1 | Short hop across a lava sea | danger avoidance; bridging | 40×40 |
| D2 | Long haul through warped forest / soul sand valley | `speedFactor < 1` terrain | 100×100 |
| D3 | Fortress interior navigation | structure, narrow corridors | 40×40 |
| D4 | Nether roof route (above bedrock) | y-specific fast corridor | 80×40 |
| D5 | **Overworld → portal → Nether → portal → Overworld, 5 000 blocks apart** | Tier-1 transitions; the 8:1 ratio must make the nether leg win | OW 2× 60×60 + Nether 100×100 |
| D6 | Same route, portal route is *not* worth it (800 blocks apart) | Tier-1 must reject the portal | reuse D5 captures |
| D7 | Nether destination behind an impassable lava lake | failure with a lot of tempting terrain | 60×60 |

### E. End

| # | Scenario | What it exercises | Capture |
|---|---|---|---|
| E1 | Main island, ground-level traverse | baseline in a third environment | 40×40 |
| E2 | Ground → top of an obsidian pillar | vertical, no easy footing | 20×20 |
| E3 | Main island → outer island across the void, with ender pearls | `EnderPearlMode`, ballistic checks | 100×60 |
| E4 | Route skirting the void | fall danger, one-way mistakes | 60×60 |

### F. Flight

| # | Scenario | What it exercises | Capture |
|---|---|---|---|
| F1 | Creative fly, 3 000 blocks, open sky | 3D branching with a near-perfect heuristic — should be trivially fast; if it isn't, that's a finding | 200×120 |
| F2 | Creative fly through a cave system | worst-case 3D fanout in enclosed space | 60×60 |
| F3 | Elytra glide, descending 2 000 blocks | glide mode, one-way vertical budget | 150×100 |
| F4 | Fly with a ceiling (nether roof or build limit) | bounded 3D | 80×80 |

### G. Pathological & guard-rail

| # | Scenario | What it exercises | Capture |
|---|---|---|---|
| G1 | Sealed box, destination outside | immediate, cheap failure | 5×5 |
| G2 | Destination in never-generated chunks | permanent chunk failure handling | 40×40 with a hole |
| G3 | Capture with scattered holes | `MinecraftChunk.Unknown` behavior mid-search | 60×60 |
| G4 | Built maze / spiral | heuristic provides nothing | 30×30 |
| G5 | One-wide bridge over a void chasm | single viable corridor | 40×20 |
| G6 | Two plausible routes of nearly equal cost | tie-group behavior, determinism check | 80×80 |
| G7 | Destination 12 000 blocks away | the OOM case that motivates SMA* | 400×200 (large; keep out of git) |

### H. Destination shape

You said this barely matters to the algorithm. Mostly true for Tier-3 — but two of these hit real
hot paths, so they are worth having:

| # | Scenario | What it exercises | Capture |
|---|---|---|---|
| H1 | Destination is a 32×32×8 box region | baseline region destination vs. a point | 40×40 |
| H2 | **Destination is one composite region of ~200 chunk boxes** | `nearestBoundaryCell` on a composite, called **twice per relaxed edge** — the `projectedFrom`/`projectedTo` memo in `Tier2Search` exists precisely because this is not cheap, and nothing currently measures it | 60×60 |
| H3 | **Destination is ~200 *separate* regions (a Towny town)** | Tier-1 fan-out: `TownyRegions.plots()` returns one `BoxWorldRegion` per claimed chunk via `Destination.regions(...)`, so a 200-chunk town becomes 200 Tier-1 goal nodes and 200 candidate legs | 60×60 |
| H4 | Multi-region town where the *nearest* claim is walled off | Tier-1 must abandon the near region and re-plan onto a farther one — the anytime recalc loop | 60×60 |
| H5 | Multi-region town, one claim across a portal in the Nether | Tier-1 fan-out interacting with transitions | reuse D5 |
| H6 | Agent starts *inside* the destination region | degenerate zero-length solve; should be instant | 5×5 |
| H7 | L-shaped / non-convex single region | `nearestBoundaryCell` returning a point the agent cannot reach directly | 30×30 |

H2 and H3 are the two that matter and they are **different costs in different tiers** — H2 is a
Tier-3 per-edge projection cost, H3 is a Tier-1 goal-set cost. Worth separating because a town could
plausibly be modeled either way, and this will tell you which modeling is cheaper. You said Tier-1
isn't a priority yet; H3 is the exception, because it is the one Tier-1 cost that scales with
something a server operator controls (town size) rather than with the route.

That is ~52 scenarios. Start by capturing **A1, A2, A8, A14, B1, B2, C2, D5, F1, G7** — those ten
cover every distinct failure mode you described and are enough to start iterating. Add **H3** to the
starter set — it needs no new terrain, only a scenario file over a capture you already have.

**Practical capture procedure:** fly the route in spectator, `/cobblestonemark <id> origin` at the
start and `... dest` at the end, then `/cobblestonecopychunks here <r>` at a few points along it, or
let the mark command emit a manifest entry that computes the corridor rectangle for you and run
`/cobblestonecopychunks manifest`. Capture the nether and end into their own `.sbr` files in the same
capture directory; a scenario references whichever worlds it needs.

---

## 8. The benchmark harness

### 8.1 Two runners

**`StonebrickBench` (JMH)** — via the `me.champeau.jmh` Gradle plugin, in a `jmh` source set.
- `@BenchmarkMode(AverageTime)`, `@OutputTimeUnit(MILLISECONDS)`, `@Fork(3)`, 3 warmup / 5 measurement
  iterations for the fast scenarios; `SingleShotTime` with `@Fork(10)` for the multi-second ones,
  because an iteration you can only run twice per second makes JMH's steady-state assumptions a lie.
- Scenarios come in via `@Param` over scenario ids, so one benchmark class covers the corpus.
- Captures are loaded and decoded in `@Setup(Level.Trial)`; the per-solve chunk cache is fresh per
  invocation (that's what `scopedForSolve()` already gives you).
- Always run with IO mode `VIRTUAL` or `ZERO`. `SIMULATED` under JMH mostly measures `Thread.sleep`.

**`StonebrickRunner` (plain `main`)** — runs a scenario once (or N times), writes a result JSON and
optionally a search recording for the visualizer. This is what you use day to day; JMH is for the
numbers you publish to yourself.

### 8.2 What to measure

| Metric | How |
|---|---|
| wall time | the runner's own clock, or simulated clock in VIRTUAL mode |
| active vs. parked time | already in `Tier2Metrics` — expose it rather than only logging it |
| nodes expanded, nodes visited | already tracked |
| **peak retained node-table bytes** | measure `bytesPerNode` once with JOL, multiply by peak `nodes.size()`; sample peak inside the pump |
| **total allocated bytes** | JMH `-prof gc` (`gc.alloc.rate.norm`) — the most reliable memory number you will get |
| peak heap | `MemoryPoolMXBean` peak usage, reset per iteration, `System.gc()` before sampling — noisy, report as a secondary |
| chunk fetches (cold/warm/failed) | `ChunkProviderStats` already exists |
| path cost, path length, step count | from the result |
| **cost ratio vs. golden Dijkstra** | the quality metric; without it speed numbers are meaningless |
| success / timeout / limit-exceeded | outcome distribution |
| (new algorithm) per-medium expansions, medium switches, prunes, regenerations | §10 |

Results land as `results/<timestamp>-<git-sha>-<algorithm>.json`. A `compare` subcommand diffs two
result files into a table with per-scenario deltas and a geometric-mean summary, and flags any
scenario whose cost ratio regressed. Wire a Gradle task `benchCompare --base <file> --head <file>`.

### 8.3 Search recording (for the visualizer)

Add to `:core` a `Tier2Observer` with no-op defaults:

```java
public interface Tier2Observer {
  static Tier2Observer noop() { ... }
  default void opened(Cell c, double g, double f, int medium) {}
  default void closed(Cell c, double g, double f, int medium) {}
  default void parked(Cell c) {}
  default void removed(Cell c, RemovalReason reason) {}   // restriction rollback, SMA* prune
  default void solved(List<Cell> path) {}
}
```

The bench's recorder writes a compact binary event stream (varint-delta coordinates, ~8 bytes/event);
a 200 000-node search is a ~2 MB recording. The visualizer replays it. This decoupling matters:
recording never runs during a timed benchmark, and you can diff two algorithms' recordings of the
same scenario side by side.

⚠️ Performance flag: these calls sit on the hottest path in the codebase. Hold the observer in a
`final` field, default to the no-op singleton, and **benchmark with the observer off vs. a no-op
observer installed** to confirm the JIT inlines it away. If it doesn't, gate it behind a
`static final boolean` set from a system property so it compiles out.

---

## 9. The visualizer

**Design principle: replay, never live.** The visualizer opens a capture + a recording; it never
holds a running search. That keeps benchmark timings clean, lets you scrub backwards, and lets you
open a run from last week.

- **Rendering:** LWJGL 3 with an instanced/point-sprite shader. JavaFX 3D will not survive a few
  hundred thousand visualized cells. Terrain: greedy-meshed opaque surfaces within a configurable
  radius of the camera, streamed from the `.sbr` (which is already random-access per chunk — the
  format pays off twice). Search cells: GPU-instanced translucent cubes or points, one draw call.
- **Controls:** WASD + mouse look, Space/Shift for up/down, Ctrl to sprint, `F` to toggle
  cursor capture, `G` to teleport to a typed coordinate, `O`/`P` to jump the camera to origin/destination.
- **Timeline:** a scrubber over expansion order, with play/pause, speed, and step-by-one. This is the
  single most useful debugging feature — watching the frontier crawl into the river in slow motion
  will tell you more than any metric.
- **Toggles (keys 1–9 / a side panel):** open set · closed set · final path · candidate-parent edges ·
  parked cells · removed/rolled-back cells · SMA*-pruned cells · chunk boundaries · capture boundary ·
  restriction regions.
- **Coloring modes:** by medium (the money view for §10) · by f · by g · by h · by expansion order
  (heat over time) · by chunk (to see IO locality).
- **Y-slice clamp:** restrict rendering to `y ∈ [a, b]`. In practice you will use this constantly;
  a 3D cloud of 200 000 cells is unreadable without it.
- **Side-by-side / A-B:** load two recordings of the same scenario and toggle between them, or show
  one in blue and one in orange. This is how you will evaluate the new algorithm.

---

## 10. The new search: medium-aware, memory-bounded weighted A*

Your diagnosis is right, and it's worth stating precisely because it determines the fix.

`RunningAverageSolve` prices a node's *remaining* journey at the average per-block cost of the trail
*behind* it. In a hillside that's exactly right — three blocks into rock, the rest is priced as rock,
and the digging branch drops out. In a river it is exactly wrong: the trail behind a node in the
river is cheap boat travel, so the remaining 800 blocks are priced as cheap boat travel, so every
node in the river has a wonderful `f`, so the search drinks the whole river. The heuristic is
extrapolating a local medium to a global claim, and there is nothing in the algorithm that ever
notices the claim was false.

Two changes, and **you need both** — this is the key structural point:

1. A **medium-bucketed cost model** replaces the per-trail EMA, so the estimate is stable and
   comparable rather than a function of the last few steps.
2. A **scheduler across per-medium open sets** that notices when a medium has stopped making
   progress toward the goal and reallocates effort. The heuristic alone cannot fix the river,
   *because any heuristic that prices boat travel correctly still prefers the river* — the river
   really is cheap, it just doesn't go anywhere. Only something that watches progress can know that.

### 10.1 Mediums

```java
public enum Medium {
  WALK, SWIM, BOAT, CLIMB, FLY, MINE, HORSE,      // sustained — get their own open set
  FALL, DOOR, PEARL;                              // incidental — inherit the node's current medium
}
```

- `Movement` gains a `Medium medium` component, with a compatibility constructor defaulting it
  (same pattern already used for `restricted`). A mode usually returns one medium, but `WalkMode`
  over ice vs. soul sand is arguably different — start with one medium per mode and revisit.
- A node's medium is **the medium of the edge that reached it**, and is *not* part of the node key.
  Adding it to `CellState` would multiply the state space; the cost of not adding it is that a node
  can migrate between open sets when a better parent relaxes it, which the existing stale-entry
  check (`entry.currentCost() != node.cost`) already handles — extend that check to also compare the
  entry's medium against the node's current medium.
- **Incidental mediums must not get queues.** A fall is a free consequence of walking; giving FALL
  its own bucket with a near-zero per-block cost would make it the permanently most-promising
  medium and the scheduler would chase cliffs. Incidental edges carry through the parent's medium.
- ⚠️ **Medium-switch cost.** Entering a boat costs about a second and requires having one; leaving
  one likewise. Without an explicit switch penalty the search oscillates along every shoreline and
  the queues thrash. Add a per-(from,to) switch cost table, small and hand-tuned. (Vehicle state is
  already in `TraversalState` via `MinecraftKeys.Vehicle`, so the state machine mostly exists.)

### 10.2 The cost model

Per medium, maintain an EMA of the **observed** per-block cost of edges in that medium this solve,
seeded from a static table. Then

```
h(n) = distanceToTarget(n) × costPerBlock(medium(n))
```

⚠️ **This makes `f` incomparable across queues**, and that is not a bug to paper over — it is the
river pathology restated in arithmetic. A boat node's `f` is low *because boat is cheap*, so
"expand whichever queue has the lowest head `f`" degenerates to "always expand boat". So:

- **Within** a queue, order by `f` with the medium's own cost — correct and informative.
- **Across** queues, never compare raw `f`. Schedule by the credit system below, which uses
  *progress*, not *promise*, as its currency. If you want a cross-queue tiebreak, compute a second,
  **common-baseline** `f` using the globally cheapest per-block cost for all mediums; that one is
  comparable (and admissible), and it is what `Heuristics.euclidean` already gives you.

### 10.3 The scheduler — credits, not on/off

You hedged on "disable vs. round-robin", and the hedge is the right instinct. Binary disable has a
bad failure mode: the optimal route really *is* the river for 800 blocks, with a 150-block stretch
where it bends away from the goal. Disable it there and you either never come back or come back only
by luck. Proposal:

```
Each sustained medium m holds:
  bestH[m]            smallest distance-to-target ever reached by a node in m
  sinceImprovement[m] expansions in m since bestH[m] last improved
  credit[m]           a multiplier in [FLOOR, 1.0], FLOOR ~ 1/64
  quota[m]            expansions granted this round

Each round (e.g. 256 expansions total):
  weight[m] = credit[m] * softmax(-baselineF_head[m] / tau)
  quota[m]  = max(1, round(256 * weight[m] / sum(weight)))
  expand each medium up to its quota, in descending weight order.

On every expansion in m:
  if h(node) < bestH[m] - eps:  bestH[m] = h(node); sinceImprovement[m] = 0; credit[m] = 1.0
  else:                          sinceImprovement[m]++
  if sinceImprovement[m] > stallWindow:  credit[m] *= 0.5 (floored); sinceImprovement[m] = 0
```

Why this shape:

- **Progress is measured as minimum `h`, not `f`.** In a weighted A*, `f` generally *rises* along a
  route, so "no `f` progress" is ambiguous. "This medium has not gotten any closer to the goal in N
  expansions" is exactly what you mean and is directly measurable. A medium flood-filling an
  orthogonal river makes no `h` progress by construction — it's the cleanest possible signal.
- **Credit never hits zero**, so the river is always still being sipped at 1/64 of the budget. You
  get your "don't ever stop looking" property for free, without needing a re-enable rule, and the
  "go all the way down the list and come back" behavior falls out automatically.
- **Recovery is instant**: one node that beats `bestH[m]` (a new river closer to the goal, a lake
  in the right direction) resets credit to 1.0. This is exactly your re-enable condition, expressed
  as a continuous quantity.
- ⚠️ **Make `stallWindow` scale-relative.** 100 blocks is enormous for a 60-block route and nothing
  for a 3 000-block one. Measure it in *expansions*, not blocks (blocks aren't directly observable),
  with something like `stallWindow = clamp(64, 8 * initialDistance, 20_000)`. Tune it on the corpus —
  this is precisely the kind of constant the benchmark exists to pick.

Suggested first experiments once it runs: `stallWindow` sweep, `credit` decay factor (0.5 vs. 0.25
vs. 0.75), softmax temperature tau, round size, and floor. That is a five-dimensional sweep, so build
the runner to take these as parameters from the scenario/profile JSON from day one.

#### Remaining concerns with credits on long searches

I am happy with the shape, but four things worry me and they all get worse with route length:

1. **`bestH[m]` never decreases, so late in a long search *every* medium stops improving** and all
   credits decay to the floor together. The scheduler silently degenerates into uniform round-robin.
   That is a benign end state rather than a bug, but it means the mechanism stops steering exactly
   when the search is in the most trouble. Track "fraction of expansions spent with all mediums at
   the floor" as a metric — if it is high on the long scenarios, credits are not earning their keep
   there and the coarse tier is the answer instead.
2. **Long routes have phases** (land → ocean → land). A medium floored during phase 1 must come back
   in phase 3, and it only can if a floor-rate expansion happens to find the improving node. A 1/64
   floor over 200 000 expansions is ~3 000 expansions, probably enough — but it is luck, not design.
   Add an explicit phase reset: **when the *global* best-h improves by more than some margin
   (say 10% of the initial distance), reset every medium's credit to 1.0.** Cheap, and it makes
   recovery structural.
3. **Asymmetry is a feature — say so in the code.** Decay is gradual (halving), recovery is instant
   (snap to 1.0). That is deliberate: being wrong about abandoning a medium is much more expensive
   than being wrong about keeping one. Write that down or someone will "fix" it into symmetry.
4. ⚠️ **Keep quota allocation deterministic.** No sampling from the softmax — compute integer quotas
   by largest-remainder over the weights. If credits introduce run-to-run variance you lose the
   ability to A/B the thing you built credits for.

And the strategic note: **build the coarse tier first and re-measure before building credits.** If
§11 works, credits may have nothing left to fix on the scenarios that motivated them. They still
earn their keep under `LOADED_ONLY`, where the coarse tier cannot see far enough to help — so they
are complementary rather than redundant, but they are no longer the first thing to build.

### 10.4 The memory bound — alternatives to SMA*

Given the hazards below, here is the ranked menu. **My recommendation is to skip SMA* entirely** and
take (1) + (2), adding (3) only if measurement says you still need it.

**(1) Attack the cause: a coarse tier (§11).** Fewer nodes is the same thing as less memory, and an
informed `h` cuts node count by orders of magnitude rather than by a constant factor. This is the
real fix; everything below is damage control on a search that is exploring too much.

**(2) Bounded open set with beacons.** When the open set exceeds `N`, drop the worst-`f` entries in
a batch. That is it. Roughly twenty lines, no node reopening, no tree/graph conflict, no
regeneration thrash — the failure mode is "we might not find a path that exists", not "we spend
sixty seconds re-reading chunks". Keep the beacon set from §10.5 immune so the best-h progress is
never dropped. Compared to SMA* this trades a *completeness* guarantee you do not currently have
anyway (you already bail at `maxCellsVisited`) for a large reduction in complexity. The honest
framing: today the search *fails* at the cap; this makes it *degrade* at the cap, which is what you
actually wanted from SMA*.

**(3) Compress the closed set instead of forgetting it.** A closed node is retained for exactly two
reasons: duplicate detection and path reconstruction. Both can be made far cheaper.
  - *Duplicate detection* → a compact open-addressed hash or Bloom filter over `CellState` hashes:
    a few bits per visited state instead of a `Node` + its candidate-parent list + two map entries.
    A Bloom false positive means "we wrongly think we've seen this cell", which drops a route — so
    size it for a very low false-positive rate, or use an exact open-addressed `long` set (64 bits
    per state, still ~20× cheaper than a `Node`).
  - *Path reconstruction* → keep only every k-th node on the parent chain as an **anchor**, and
    re-run tiny searches between anchors at the end. The corridor makes those searches trivial.

  This is the "sparse-memory graph search" family and it fits this codebase far better than SMA*
  because it never requires reopening a closed node. Expect 20–50× on the node table. The catch:
  candidate parents (the rollback safety net) cannot be compressed away, so keep those for nodes
  with unresolved restrictions and compress only the fully-settled interior.

**(4) Spill cold closed nodes off-heap** (a `MemorySegment` or mapped file). Ugly, but it targets
your literal complaint — OOM — precisely, and needs no algorithmic change. Worth keeping in the back
pocket if (2) and (3) leave you short.

**(5) Frontier search** (Korf): store only the frontier, using per-node "used operator" bits to
prevent re-expansion, and reconstruct by divide-and-conquer. Theoretically the right answer, memory
∝ frontier rather than volume. But it fights the multiple-candidate-parents design head-on, and the
divide-and-conquer reconstruction re-reads chunks. Mention for completeness; I would not build it.

**(6) IDA* / RBFS.** O(depth) memory, but they re-expand enormously and every re-expansion here is a
potential disk read. Actively wrong for this problem. Don't.

#### Why SMA* specifically is a bad fit here — four sharp edges

1. ⚠️ **This search is a graph, not a tree.** Every node keeps *all* candidate parents that ever
   relaxed it, deliberately, so that a restriction rollback never forgets an alternative route. SMA*
   pruning must therefore only consider nodes with **no live successors and which are not a
   candidate parent of any live node**. Track a successor/dependent count per node; prune only at
   count zero. Without this, pruning silently deletes the rollback machinery's safety net.

2. ⚠️ **Regeneration here costs disk IO, not CPU.** Classic SMA* assumes cheap successor generation;
   here re-expanding a forgotten node means re-running modes, which means chunk fetches, which means
   parking. At the bound you can thrash catastrophically. Mitigations:
   - prune in **batches** (drop 10–20% of the table at once) rather than one node per insertion, so
     you don't sit exactly at the boundary;
   - prefer pruning nodes far from the current frontier, which are the least likely to be wanted
     back soon and whose chunks have already fallen out of the per-solve LRU anyway;
   - track `regenerations / expansions` as a first-class metric and **abort with `LIMIT_EXCEEDED`
     when it exceeds a threshold**, rather than burning the full 60 s wall clock thrashing. This
     turns the current OOM-ish failure into a fast, honest failure, which is itself a win.

3. ⚠️ **Closed nodes are never reopened today.** `loop()` skips entries whose node is `closed`.
   SMA* fundamentally requires reopening (a forgotten subtree's parent goes back on the open set).
   That is a real change to the pump, and it interacts with the repair logic — plan for it explicitly
   rather than discovering it.

4. ⚠️ **Pruning must respect the medium quotas.** If you prune globally by worst `f`, and boat has
   the best `f` values, you will prune away all of WALK and MINE and undo the entire point of §10.3.
   Give each medium a memory quota proportional to its credit (with a floor), and prune within the
   medium that is over its quota. This interaction is easy to miss and would make the two features
   cancel each other out.

Also: **express the bound in bytes, not nodes.** `maxCellsVisited` is a node count today; measure
bytes-per-node once with JOL and let `SearchSettings` take `maxSearchHeapBytes`, deriving the count.
Remember the node table is not the only consumer (the `byCell` map, candidate-parent lists, and the
per-solve chunk cache all scale), so the budget should account for them.

Finally, be honest in the naming and the docs: with a weighted, inadmissible `h`, SMA*'s optimality
and completeness guarantees are gone, and its backed-up `f` values assume consistency they won't
have. What you're building is "memory-bounded weighted A* with medium scheduling, SMA*-style
forgetting". That's fine — but it means **quality has to be measured empirically**, via the
cost-ratio-vs-golden metric in §6, not argued from theory.

### 10.5 The "reset to the start" idea

Rather than a full restart when every medium has stalled: keep a small immune-from-pruning
**beacon set** — the K best-`h` nodes ever seen, per medium (K ≈ 32). When everything stalls, re-seed
the open sets from the beacons with restored credit. It costs nothing, it never loses the search's
best-ever progress to the pruner, and it gives you the "go back to boating" behavior without
discarding work. A true restart should be the last resort, and if you reach it, consider instead
raising the weight (more greedy, less memory) or reporting failure — a restart that repeats the same
deterministic search will produce the same result.

### 10.6 Alternatives the benchmark should also measure

So the comparison is honest, run the corpus against:

- **current weighted A*** (the baseline you must beat);
- **admissible euclidean A***, unlimited — the golden reference for path quality;
- **the new medium-aware SMA*** with a few parameter sets;
- **a plain beam / bounded-open-set** variant — dead simple to implement (cap the open set, drop the
  worst), and if it gets 80% of the benefit for 5% of the complexity, that is worth knowing *before*
  you build SMA*.

And one strategic flag: **the medium scheduler treats the symptom; an abstraction layer treats the
cause.** The reason the search drinks the river is that `h` has no idea the river ends. That is
§11 — and having thought it through, I now think it should be built *before* the credit scheduler,
not after.

### 10.7 A note on mine mode and `local/notes.md`

Your note says mine mode wrecks ender-pearl mode by mining the whole island first, and you suspected
the medium concept would fix it. It does, and for the right reason: MINE is a sustained medium whose
per-block cost is enormous, so the credit scheduler will give it a small quota from the start and
decay it to the floor the moment it stops making `h` progress inside solid stone. You should not
need the "turn mine off by default and retry" workaround — but keep C2 and B6 in the corpus so you
can prove that rather than assume it.

---

## 11. The coarse tier (new Tier 2)

Renaming: **Tier 1** = transition graph (portals, teleports) · **Tier 2** = coarse section graph
(new) · **Tier 3** = fine cell A* (today's `Tier2Search`, renamed). Agreed on avoiding "Tier 1.5".

I think this idea is substantially better than the medium-credit scheduler, for a reason worth
stating plainly: **credits notice that a medium has stopped helping; the coarse tier knows the river
doesn't reach the goal before the fine search ever enters it.** One is a reaction, the other is
knowledge. Below is your design with five changes, in rough order of how much I think they matter.

### 11.1 Run it backwards, and use it as a heuristic — not as a fence

This is the biggest change and it makes most of the other problems evaporate.

Run the coarse search **backward from the destination**. Then every coarse node's `g` *is* its
cost-to-goal, and Tier 3's heuristic becomes

```
h3(cell) = coarseCostToGoal(component(cell)) + intraComponentCost(cell)
```

Consequences:

- **The river problem is solved outright, with no corridor at all.** Cells in the orthogonal river
  belong to components whose coarse cost-to-goal is large, so their `f` is bad, so the fine search
  never fans out into them. No credits, no stall windows, no tuning constants.
- **Completeness is preserved.** A fence risks excluding the only real route; a heuristic cannot.
  If the profile is wrong about a section, the fine search pays a little extra and carries on — the
  worst case is a slow search, never a failed one. That is a much better failure mode than a
  corridor that is subtly wrong, and it removes the "what if we walled off the only door" hazard
  entirely.
- **The corridor becomes an optional optimization**, not the mechanism. If you still want it,
  express it as a *soft* cost multiplier on cells outside the tube, with the fine search free to
  leave when it must, plus a widen-and-retry if it exhausts. But measure first — I suspect the
  heuristic alone gets you nearly all of it.
- **Tier 1 gets smarter for free.** `Tier1Estimator` currently corrects `distance × cheapestCostPerBlock`
  using legs it has actually solved. A backward coarse search gives it a genuinely informed edge
  cost *before* solving anything — which is exactly what scenarios D5/D6 (is the portal worth it?)
  and H3/H4 (which of 200 town claims?) need. This is a real, separate win.

Keep the coarse costs deliberately **optimistic** (price a component by its best plausible medium,
not its average) so `h3` stays near a lower bound, and let `heuristicWeight` supply greediness
explicitly. An `h` that overestimates in one place produces a bad path silently; the weight is at
least a knob you can see.

### 11.2 Components, not cubes

Your unit is the cube. Make it the **connected component within a cube**. One 16³ section becomes
1–3 nodes, not 1.

A cube with a wall down the middle is two components with no edge between them — so the "cube looks
traversable but the two sides aren't connected" false positive, which is the main way a coarse layer
lies to you, largely disappears. The cost is one flood fill over ≤4096 cells with a bitset, which is
microseconds against an IO you already paid.

Node key packs into a `long`: section x/z 22 bits each, section y 5 bits, component id 3 bits = 52
bits. No object needed for the key.

### 11.3 Don't sample — compute exactly, it's already cheaper

You proposed polling blocks, and inferring neighbors from one cube to save IO. I'd drop both:

- **Once you've fetched the chunk, you have the whole 16×16×384 column in memory.** The IO is the
  cost; scanning it is not. So profile the *entire column* — all ~24 sections — for the one fetch.
  There is nothing to save by sampling and nothing to infer in Y.
- **The palette answers most questions for free.** A section whose palette is `{stone}` is uniform:
  solid, no components, known without touching the packed data at all. `{stone, air}` tells you it
  has openings before you scan. Sections that need a real scan are the minority, and this is exactly
  the trick the `.sbr` uniform-section encoding and vanilla Anvil already use. The format and the
  coarse tier reinforce each other.
- **Inferring XZ neighbors from one cube is where false negatives come from** — the "cube looks
  sealed so we never look at the tunnel" case. Since the neighbors' data is one chunk fetch away and
  you'll want it anyway, don't guess.

### 11.4 Granularity: 16³, and here's the deciding argument

Not "coarser is cheaper" — **16³ is the chunk section, which is the IO unit, the storage unit, and
the palette unit**. 32³ spans 2×2 chunks in XZ, so profiling one coarse cube costs *four* chunk
fetches instead of one, which makes the granularity you chose to save IO cost more IO. 16³ wins on
the metric you were optimizing.

If 16³ turns out too fine for the 12 000-block routes, the answer is not 32³ — it is a **second
coarse level** over the first (16³ components → 128³ super-regions), which is what multi-level HPA*
does and which costs no extra IO because it is built from profiles you already have. Defer it until
the benchmark says you need it.

### 11.5 Anisotropy: a face-to-centroid cost, not a per-cube scalar

You're right that boat potential must not cheapen vertical movement. The clean way to express that
is not special-casing directions — it's to give each component a **per-face cost to its centroid**,
and price a crossing as `costToCentroid(entryFace) + costToCentroid(exitFace)`.

That makes anisotropy fall out: bottom-face-to-top-face in a water component is a swim-up cost;
in an air component with a floor and no fly mode, it is simply unreachable and there is no edge.
Six quantized shorts per component — much cheaper than a full 6×6 matrix and close enough at this
granularity.

For face adjacency, store a **4×4 coarsened occupancy mask per face** (16 bits, one bit per 4×4
quadrant): two components are adjacent only if their facing masks overlap. Twelve bytes per
component, and it kills most spurious adjacencies without storing full 16×16 face bitmaps.

Agreed on **no diagonals** — 6-connected. The fine search recovers diagonal movement anyway, and
corner connectivity at this granularity is fiddly for very little gain.

### 11.6 Profiles are agent-independent; costs are not

Important for reuse: a profile describes *terrain* (open volume, water volume, floor area, boat
surface, hazard fraction, average break time, face masks). What it costs to cross depends on the
agent's mode set, and that is computed at search time from the profile.

Keep them separate and the profile cache becomes **shareable across players and across searches**,
unlike anything in the fine search — cache it per chunk column (~24 sections × ~40 bytes ≈ 1.5 KB),
invalidate on block change, and it is worth persisting to disk. This is where the win compounds: the
second player to walk the same continent pays no profiling cost at all.

### 11.7 The catch: this converts a memory problem into an IO problem

⚠️ **This is the main risk and it deserves to be measured before you commit to the design.** A
12 000-block route may want tens of thousands of chunk columns profiled. At ~1 ms per fetch that is
tens of seconds — worse than the problem you set out to fix.

**A correction to something I claimed earlier.** I said a pyramid makes long routes pay *less* IO
per block because they reason at a coarser level. That is only true with a **warm** cache. Levels
≥ 1 are aggregated from level 0, so on a cold cache you cannot have a level-2 value without having
built level 0 underneath it — the hierarchy by itself saves nothing. What actually holds:

- **Warm** (level 0 already built and persisted for the area): upper levels are derived and
  resident, long-range reasoning happens in RAM, and chunk IO is ~zero. The claim holds here, and
  only here.
- **Cold**: you must build level 0 before anything above it exists. The pyramid still helps, but by
  a different mechanism — **optimistic partial aggregates** (§12.6) let the coarse search run over
  mostly-unbuilt cells immediately, so level 0 is built only in a *tube* along the chosen route
  rather than across the whole explored disc. Cold cost is proportional to route length × tube
  width, not to area explored.

So the pyramid's real contribution is that it **converts a per-search cost into a one-time-per-area
cost**, and confines the cold-cache cost to a tube. The amortization is what makes it cheap, not the
hierarchy as such — which makes persistence and the prewarm command load-bearing parts of the
design, not conveniences.

Three further things make it survivable, and the harness is what will tell you whether they're enough:

1. **The coarse frontier is wide and IO-bound, so it parallelizes.** The fine search is essentially
   sequential; the coarse search can have hundreds of fetches in flight. Your note that region-file
   IO is cheap now is exactly what this design is spending.
2. **New terrain is profiled for free at generation time**, where the blocks are already in memory.
3. **The coarse search is A\* too**, not a flood fill — it should touch a corridor of columns, not a
   disc. Weight it, and consider giving *it* the medium-credit treatment from §10.3, where a river
   is thirty nodes instead of thirty thousand and the whole mechanism costs nothing.

Under `ChunkLoadPolicy.LOADED_ONLY` the coarse tier is largely blind, which is precisely where the
fine-grained credit scheduler still earns its keep.

**This is the strongest argument for building the harness before the algorithm.** The viability of
Tier 2 rests entirely on an IO throughput assumption, and §5.2's IO model is the thing that can test
that assumption at fifty different disk profiles without touching a server.

### 11.7a When Tier 3 asks about a cell the coarse search never settled

Three different causes, and they must not be conflated:

1. the backward coarse search was budget-limited and stopped short;
2. the component is coarse-*unreachable* from the goal — possibly a false negative from an
   imperfect profile;
3. the area is not profiled at all (a cold-cache miss).

⚠️ **Never answer with infinity.** An infinite `h` is a hard fence wearing a heuristic's clothes, and
it reintroduces precisely the completeness hazard §11.1 removed: wall off the one real door and a
solvable search fails.

**The rule:** an `h3` query on an unsettled component **resumes the backward coarse search** until
that component settles or a small budget expires, then caches the answer for the solve. The backward
search is not a preprocessing pass; it is a lazily-expanded search that does exactly as much work as
the fine search turns out to need. Resumption at level 0 is itself guided by level 1, so it is cheap
(§12.5).

**The backstop**, when the budget expires or the component really is coarse-unreachable:

```
h3(cell) = max(bestSettledCoarseValue, euclideanDistance(cell, goal) * cheapestCostPerBlock)
```

Optimistic, never pessimistic. The fine search will still go there if it must — just last. That one
rule is what keeps completeness intact.

⚠️ **Refined `h` values rise**, and changing `h` mid-search breaks the ordering A* assumes. A
component that got the optimistic backstop and later settles at its true (higher) coarse cost has
had its `f` understated. **Decision: freeze a component's `h` for the remainder of the solve once it
has been handed out.** Slightly stale, but simple and decoupled from the trickiest code in the
search. Revisit later: the alternative is to let it change and lean on the repair machinery, which
already handles `f` moving when restriction verdicts land — more accurate, more entangled. Worth
measuring the accuracy cost of freezing once the harness can see it.

### 11.7b The fine search should get less weighted, possibly not at all

`heuristicWeight = 1.5` exists because today's heuristic is uninformative, so speed is bought with
greed. A coarse `h` removes that reason. Worse, the profiles are approximations, so **`h3` is already
inadmissible** — stacking an explicit 1.5 on top double-counts the same trade, and only one half of
it is visible as a knob.

Expect the optimum to land near 1.0–1.25, with better paths at equal speed. `ENDGAME_RADIUS` may
also become unnecessary, since the pocket problem it patches is caused by a weighted search refusing
to retreat. Both are one-line sweeps; run them the moment the coarse tier produces an `h`.

### 11.8 Re-planning fits the architecture you already have

When Tier 3 discovers a component is much worse than profiled — or impassable, or restricted —
update the profile, re-run the backward coarse search (it is small and cheap), and continue with a
corrected `h`. That is the same anytime-recalc loop `SearchImpl` already runs at Tier 1 after every
leg solve. It is a pattern the codebase has, not a new mechanism.

### 11.9 New things the benchmark must measure

Add to §8.2: coarse components profiled · chunk columns fetched for profiling vs. for fine search ·
profile cache hit rate · coarse search nodes and wall time · **`h3` accuracy** (coarse estimate vs.
the true cost the fine search found, per component — this is the single number that says whether the
profile model is any good) · re-plan count · corridor escapes, if you keep a soft corridor.

`h3` accuracy is the one to build first. If the coarse estimate correlates poorly with realized cost,
no amount of scheduler tuning will save it, and you'll know in an afternoon instead of a month.

### 11.10 Verdict against the original plan

| | Credits (§10.3) | Bounded open set (§10.4.2) | Coarse tier (§11) |
|---|---|---|---|
| Fixes the river | reactively, after ~a stall window wasted | no | yes, before entering it |
| Fixes mine-mode boring into stone | yes | no | yes |
| Memory | unchanged | bounded, degrades | much lower (far fewer nodes) |
| IO cost | none | none | **significant** — the risk |
| Completeness | preserved | lost at the bound | preserved |
| Build size | small | tiny | large |
| Helps Tier 1 | no | no | yes |

Recommended order: **harness → baseline → coarse tier (§11) → re-measure → bounded open set if still
needed → credits only if `LOADED_ONLY` or the measurements still want them.** That inverts what I
said last time, and the reason is §11.1: running the coarse search backward turns it from a corridor
generator (which has a correctness hazard) into a heuristic (which has none), and at that point it
dominates the scheduler on every axis except IO.

## 12. The LOD pyramid

Level 0 is measured from blocks. Every level above it is aggregated from the level below and costs
**no chunk IO at all**. That is the whole point of the structure.

### 12.1 The ladder

Edge length quadruples per level: `edge(L) = 16 << (2L)`, so each cell has **64 children** (4×4×4).

| Level | Edge | Children | Cells in a 10k×10k world | Resident? |
|---|---|---|---|---|
| L0 | 16 (one chunk section) | — (blocks) | 625×625×24 ≈ 9.4 M slots, ~1–2 M non-empty | on disk, mmap'd |
| L1 | 64 | 64 | 157×157×6 ≈ 148 k | mostly resident |
| L2 | 256 | 64 | 40×40×2 ≈ 3.2 k | resident |
| L3 | 1024 | 64 | 10×10×1 ≈ 100 | resident |

4× rather than 8× per level: it gives smoother refinement (you are never guessing across a 512×
jump), and a block edit dirties 64 siblings' worth of parent rather than 512.

**Vertical saturation.** A world is ~384 blocks tall, so vertical subdivision runs out before
horizontal does: `cellHeight(L) = min(edge(L), worldHeight)`. L2 and above are therefore **2-D slabs
of full world height**, which is the right shape for long-range overworld routing anyway. State the
rule explicitly in code — it is the kind of thing that silently produces a wrong index.

`MAX_LEVEL` is derived from world size, capped at 3. The Nether (128 tall) saturates one level
earlier; the End is sparse and will mostly be empty cells.

### 12.2 The node at every level is a component, not a cell

At L0 a component is a connected set of open cells within the 16³ cube (flood fill over a 4096-bit
mask). At L(n+1) a component is a connected set of L(n) components within the parent cell, connected
through the L(n) adjacency graph *restricted to that cell*. The recursion is uniform, which is what
makes the whole thing implementable once.

Component counts stay small — at higher levels the world is one big connected surface plus a few
isolated cave systems. **Cap at 4 components per cell** (K), merging the smallest into a catch-all
beyond that. The cap costs a little precision in pathological cells and buys a fixed-stride record,
which is what makes mmap work. Record the merge in a flag so the fine search knows that cell's
estimate is coarser than usual.

Node key packs into a `long`: `level` 2 bits · cell x 22 · cell z 22 · cell y 5 · component 2.

### 12.3 What a profile holds

**L0 record (~40 bytes)** — measured from blocks:

| Field | Bytes | Notes |
|---|---|---|
| `faceMask` | 1 | which of the 6 faces this component touches |
| `faceQuad[6]` | 12 | 4×4 coarsened occupancy per face (16 bits); adjacency requires overlap |
| `faceCost[6]` | 12 | quantized cost from face to component centroid; `0xFFFF` = unreachable |
| `openVol`, `waterVol`, `floorArea`, `boatArea`, `hazard` | 5 | quantized counts |
| `dominantMedium` | 1 | argmax by volume |
| `avgBreakTime` | 2 | for mine potential in near-solid cells |
| `flags` | 1 | built · partial · merged · all-solid · all-air |

**L1+ record (~28 bytes)** — aggregated, no `faceQuad`: face adjacency at higher levels is derived
from child adjacency that was already checked exactly at L0, so re-storing quadrant masks buys
nothing.

Aggregation rules, all cheap:
- volumes → sums of children, requantized
- `faceMask` → union of children's masks on the parent's outer faces
- `faceCost` → a Dijkstra over the child graph *within* the parent cell, from each outer face to the
  component centroid. That is ≤ 64 children × ≤ 4 components ≈ 256 nodes per parent. Trivial, and it
  is exactly HPA*'s intra-cluster edge precompute.
- `dominantMedium` → volume-weighted argmax
- `partial` → set if **any** child is unbuilt (see §12.6)

**Profiles are agent-independent.** Everything above describes terrain only; the cost of crossing is
computed at search time from the querying agent's mode set. That is what makes one cache serve every
player (§11.6).

### 12.4 Storage: mmap'd fixed-stride region files, not a database

Access is dense, purely spatial, and never queried by anything but coordinates — so an embedded
database buys indexing you do not need and adds a dependency, a write path, and write amplification.

```
plugins/Cobblestone/lod/<world-key>/
  L0/r.<rx>.<rz>.lod
  L1/r.<rx>.<rz>.lod
  L2/...            (small enough to be one file per level)
  meta.json          world key, min/max Y, level count, format version, build state
```

Each file covers 32×32 cells in XZ, all Y layers.

**Sparsity without losing O(1) addressing.** A fixed slot for every L0 cell would be ~1.3 GB on a
large world, most of it solid stone. Instead, each *column* carries a `long` **presence mask** of
which of its ≤64 Y-layers have any component at all, and a cell's slot index is

```
slot = columnBase + popcount(presenceMask & ((1L << layer) - 1))
```

One popcount, no index structure — the same trick as `sectionMask` in the `.sbr` format (§3) and in
Anvil itself. A typical column has 4–8 interesting layers rather than 24, which brings L0 to roughly
**300–500 bytes per column, ~150–300 MB for a fully-explored 10k×10k world**. On disk, not heap.

Because slots are fixed-stride, the file is read by `mmap` and the OS page cache *is* the cache —
warm reads are memcpy-speed, eviction is the kernel's problem, and it survives restarts for free.
L1 and above are small enough to load fully resident at startup.

⚠️ **Concurrency.** A background profiler writes while searches read from worker threads. Use a
**seqlock per cell**: an even version means stable, odd means being written; a reader reads the
version, the record, then the version again, and retries on a mismatch. Cheap, lock-free on the read
side, and it makes a torn read impossible. Do not skip this — a torn profile is a wrong heuristic
that produces a plausible-looking bad path, which is the hardest kind of bug to notice.

⚠️ **Disk budget is operator-visible.** Cap it in config, evict coldest L0 region files when over,
and never let the pyramid grow unbounded on a server with a 200 GB world.

### 12.5 Searching the pyramid: top-down, every level, each one cheap

To answer your question directly: **yes, you search every level, in order, top down.** You do not
skip L1 and jump from L2 to L0. The reason each pass is cheap is that **the level above it is its
heuristic**:

1. **Pick the entry level** from straight-line distance — the level at which the route spans roughly
   `TARGET_SPAN` ≈ 32 cells. `L = clamp(0, floor(log4(distance / 16 / TARGET_SPAN)), MAX_LEVEL)`.
   A 12 000-block route is 750 L0 cells, 187 L1, 47 L2 → enter at **L2**.
2. **Backward A\* at level L**, from the destination toward the origin, over resident data. Its `g`
   values are cost-to-goal for every L2 component it settles.
3. **Descend.** Backward A* at level L−1, seeded from the destination, using **the settled level-L
   values as its heuristic** and biased to the children of the level-L route. Because the heuristic
   is near-exact at this scale, this pass expands close to a tube, not a disc.
4. Repeat down to **L0**. Its `g` values are what `h3` reads.
5. Tier 3 runs with `h3(cell) = coarseCostToGoal(L0 component) + intraComponentCost(cell)`, resuming
   the L0 pass on demand (§11.7a) — and that resumption is itself guided by L1, so a miss costs a
   handful of node expansions rather than a new search.

Each descent is a *fresh* search at a finer level, guided by the coarser one. This is standard
hierarchical A* with an abstraction heuristic, and it is what keeps the total work close to linear
in route length instead of quadratic in it.

**Short routes skip levels naturally.** A 200-block route enters at L0 and the pyramid never
engages, which is correct — the overhead should scale with the problem.

### 12.6 Optimism, partial cells, and the cold cache

An unbuilt or partial cell is **optimistic**: treated as traversable at the best plausible cost for
its level. This is the freespace assumption, and it is what lets step 2 above run *before* level 0
exists underneath it.

The consequence is a plan-execute-replan loop: the coarse route crosses optimistic unknown cells,
refinement builds L0 along it, and if what is found is materially worse than assumed, mark the cells
and re-plan at the level above. This is the same anytime-recalc shape `SearchImpl` already runs at
Tier 1 (§11.8).

⚠️ **Bound the re-planning.** Adversarial terrain — a long peninsula that looks open at L2 and
dead-ends at L0 — can oscillate. Cap re-plans per level (say 3), then fall back to widening the
refinement tube rather than re-planning again, and finally to the euclidean backstop. Track re-plan
count per solve as a metric; a high number is the signal that the profile model is too optimistic.

Partial cells must also be **recomputed, not repaired**, when their last child is built: a partial
aggregate was optimistic, and incrementally patching it would leave the optimism baked in.

### 12.7 Building and invalidating

**Lazy by default, eager on request.** Profile on demand during searches and write through; the
cache fills where players actually go, which on most servers is a small fraction of generated
chunks, and there is no first-run tax. Offer `/cobblestone lod prewarm <radius|world>` for operators
who want predictable performance — throttled, resumable, with progress and an ETA.

**Free profiling at generation.** Hook chunk generation and profile the column while its blocks are
still in memory. New terrain then costs zero extra IO, forever.

**Invalidation.** A block edit dirties one L0 cell → mark it and its ancestor chain dirty → a
background job re-aggregates dirty ancestors on a timer, coalescing edits. The pyramid shrinks 64×
per level, so propagation is nearly free: a single block change touches 1 + 1 + 1 + 1 cells.
Batch per tick; bulk edits (WorldEdit) should dirty a region and rebuild once.

**Degrade, never block.** While the pyramid is cold the plugin must still navigate — fall back to
the euclidean heuristic, and let searches warm the cache as a side effect.

### 12.8 What the harness must measure here

Add to §8.2: chunk columns profiled per solve (cold vs. warm) · L0 slots written · pyramid bytes on
disk · profile cache hit rate per level · nodes expanded per level · **re-plan count per level** ·
seqlock read retries · and above all **`h3` accuracy**, per level: the coarse estimate against the
true cost Tier 3 ultimately found. Build `h3` accuracy first — if the correlation is poor, no
amount of tuning below it matters, and you will know within a day.

## 13. Suggested phasing

**Nothing in §10–§12 gets built until phase 7 is done and baselines are recorded.** That is the
whole point of the ordering: every algorithm decision below is a bet, and the harness is what
settles them.

### Part 1 — the harness (build this now)

1. `stonebrick-format` + round-trip tests (synthetic chunks, no server needed).
2. `cobblestonecopier`, with the `PaperBlockBridge` shim (§4). Capture the ten starter scenarios
   plus H3.
3. `stonebrick-platform` with `ZERO` IO only; get one scenario solving end to end.
4. Core prerequisites: `TimeSource`, the insertion-sequence tiebreak, `Tier2Observer`.
5. `SimulatedChunkIo` (all three modes) + `DeterministicScheduler`.
6. `stonebrick-bench`: runner, metrics, result JSON, goldens, `compare`.
7. **Take baseline numbers.** Current weighted A*, plus the admissible-euclidean reference for path
   quality. Everything after this is measured against it.
8. `stonebrick-viz`. Worth doing before any algorithm work — you will want to *see* the river.

### Part 2 — the coarse tier (design further before building)

9. L0 profiling + component extraction, measured in isolation: profile cost per column, component
   counts, `.lod` size on disk.
10. **`h3` accuracy harness** — L0 coarse estimate vs. the true cost Tier 3 found. This is the
    go/no-go measurement, and it comes before any of the pyramid.
11. Backward L0 search + `h3` wired into Tier 3, with the optimistic backstop and frozen `h`
    (§11.7a). Re-measure. Sweep `heuristicWeight` down (§11.7b).
12. The pyramid: L1+ aggregation, mmap'd storage, seqlocks, top-down refinement (§12.5).
13. Optimistic partial aggregates + the re-plan loop (§12.6); lazy build, prewarm command,
    invalidation (§12.7).

### Part 3 — only if measurement still wants them

14. Bounded open set with beacons (§10.4.2) if memory is still a problem after the coarse tier.
15. Medium-bucketed costs + credit scheduler (§10.3) — likely only earns its keep under
    `LOADED_ONLY`, where the coarse tier is blind.
16. Parameter sweeps across the corpus; pick every constant from data.

## 14. Decisions taken

- **Traits**: copier uses the real `PaperBlock` via a split-package shim; no `paper-core` widening,
  no second trait table. No other stonebrick module may depend on `paper-core`.
- **Capture format**: custom `.sbr`, blockstate strings in the palette, traits in a sibling
  `traits.json` that can be regenerated without re-copying blocks.
- **Memory bound**: no SMA*. Coarse tier first; bounded open set with beacons if still needed.
- **Coarse tier**: runs backward from the destination and feeds Tier 3 a heuristic, not a fence.
- **Unprofiled components**: lazily resume the backward search; optimistic euclidean backstop;
  never infinity.
- **Refined `h`**: frozen per component for the rest of a solve. Revisit once measurable.
- **Fine-search weight**: expected to drop toward 1.0; `ENDGAME_RADIUS` may become unnecessary.
- **LOD storage**: mmap'd fixed-stride region files with per-column presence masks. Not a database.
- **Tier naming**: Tier 1 transitions · Tier 2 coarse (new) · Tier 3 fine (today's `Tier2Search`).
- **Towny regions**: tracked as [issue #23](https://github.com/CobblestoneMC/cobblestone/issues/23),
  separate from this work.

## 15. Open questions

- Chunk coordinates vs. block coordinates as the default for `/cobblestonecopychunks` — this design
  assumes chunk, with `--blocks` available. Confirm.
- Is the capture corpus going in Git LFS, or a manifest + external storage?
- Should `core-test`'s synthetic fixtures be migrated to scenario JSON so one loader serves both?
- Does the visualizer need to show Tier-1 graph state at all, or is Tier-3 enough for now?
- Is `:playground` safe to delete, or is something depending on it that isn't in this tree?
- Should §11–§12 eventually move to their own `tier2-coarse.md`? They are production architecture
  living in a test-harness document, and they will keep growing.
