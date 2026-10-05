# Stonebrick — a benchmarking & visual-debugging platform for Cobblestone

Status: partly implemented (2026-10-04, PR #24). Drafted 2026-09-22; the sections below are the
design as written, and where the build departed from it the commit history says why.

**Built**
- `stonebrick-format`, `stonebrick-copier`, `stonebrick-platform`, `stonebrick-bench` (§1–§8).
  `bench` commands: `run`, `accept`, `list`, `sweep` (heuristic × weight grid), `verify`
  (estimates against a Dijkstra optimum), `h3`, `profile`.
- Core seams: `TimeSource`, `SearchObserver` (the §8.3 `Tier2Observer`, renamed), the
  insertion-order tie-break, and `SolveHeuristic#prepare` so the fine search parks on an estimate
  whose chunks are not resident.
- The corpus is generated from a seed (`./gradlew captureCorpus`), scenarios are run under every
  agent loadout in `agents.yml`, and the capture region grows to a fixpoint from `needed-chunks.yml`.
- Tier 2 at L0 only: `SectionProfile`, `SectionProfiler`, `CoarseCost`, a backward 26-connected
  `CoarseSearch`, and `CoarseHeuristic`, wired into Paper and Sponge as the default
  (`search.algorithm.heuristic: COARSE`).

**Not built**
- The visualizer (§9), and the deletion of `:core-test` and `:playground` (§1.1).
- The pyramid: L1–L3, persistent storage, sharing profiles between solves (§10.9–§10.13). Each solve
  profiles its own terrain today.
- Tier 1 work (§11) and the production behaviour in §13.
- Committed baselines and CI gating (§8.4). Baselines are local: a seed does not reproduce the
  blocks (§6.3), so a baseline is a fact about one capture.

Stonebrick is a fourth "platform" alongside Paper and Sponge, except the world it reads is a file
captured from a real server rather than a live one. It exists so the search can be run
reproducibly, benchmarked tightly, and watched frame by frame.

---

## 1. Module layout

```
project/stonebrick/
  format/       :stonebrick-format      pure Java. Reader/writer for the .sbc capture format.
  copier/       :stonebrickcopier       Paper plugin. /copier. Capturing world data is nothing to do
                                        with navigation, so it does not carry the Cobblestone name.
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
- **Delete `playground`** and let `:stonebrick-visualizer` be the visualizer. Two visualizers will not both
  get maintained, and the recording-replay design below is strictly better than a live JavaFX hook.

---

## 2. Capture format: custom vs. copying `.mca`

**Recommendation: the custom format, and it is not close.** The reasons, in order of weight:

1. **`.mca` is not self-contained.** A region file is a pile of NBT whose block-state names, section
   layout, and data-version you can only interpret against a specific Minecraft version's registry.
   Reading it in a pure-Java benchmark module means either vendoring a registry snapshot or
   depending on server internals — which is precisely what `stonebrick-platform` must not do.
2. **You would be benchmarking the wrong thing.** Decoding NBT and inflating full block states is a
   meaningful per-chunk cost that has nothing to do with the search. A capture already in the shape
   `MinecraftChunk` wants makes the *only* controllable cost the IO delay you inject — the point.
3. **Size.** Blocks-only, no entities, no block entities, no biomes, no lighting, no heightmaps: on
   typical terrain expect 5–15× smaller than the equivalent `.mca`. That matters when a single
   long-haul scenario wants a 200×200-chunk corridor.
4. **Version drift cuts the other way from what you feared.** You worried that a custom format means
   re-copying when the format changes. But `.mca` changes *underneath you* on every MC update, and
   worse, a re-copy of `.mca` from an updated server silently changes your benchmark inputs. A
   captured column is frozen: the same bytes forever, so a 2027 benchmark number is comparable to a
   2026 one. That is the whole value of a benchmark corpus.

**The one real risk with a custom format**, and how it is killed: if derived block traits were baked
into the block data, a change to `PaperBlock`'s trait logic would make every capture stale and
re-deriving would need a server.

So they are not. **Block data stores only blockstate strings**
(`minecraft:oak_door[facing=east,half=lower,open=false]`); traits live beside it in `traits.json`,
one table per capture, written by the copier from the real `PaperBlock` (§4).

That splits the two lifetimes cleanly. Block data is captured once and frozen. When trait logic
changes, only `traits.json` is regenerated — and even that can be done offline from the strings,
without a server, by a `stonebrick-format` tool. The blocks are never re-copied. `capture.json`
records the copier and MC versions, so a stale table is detected rather than quietly used.

---

## 3. The capture format: one file per chunk column

**Captures are machine-local and are not version-controlled.** Each developer captures their own
with `/copier`; `project/stonebrick/data/` is gitignored. A shared team would put the corpus on
shared storage rather than in the repository — a benchmark corpus is build input, and putting
hundreds of megabytes of it in git makes every clone pay for terrain most of them will never read.

What survives that decision is **byte-stability**: re-capturing unchanged terrain still produces
identical files, so the copier can skip the write and a re-capture after editing one hut rewrites a
handful of columns rather than everything. That property is what makes iterating on a test fixture
cheap, and it is worth having whether or not git is involved.

### 3.1 Layout

```
stonebrick/data/<capture-name>/
  capture.meta                       world keys, environment, minY/maxY, MC version,
                                     copier version, capture time
  traits.tsv                         blockstate string -> trait record (§4)
  overworld/c.<x>.<z>.sbc            one file per chunk column
  the_nether/c.<x>.<z>.sbc
  scenarios/*.json                   scenario definitions (§6)
```

**One file per chunk column**, holding only the 16³ cubes that were actually captured. That is your
per-chunk instinct and your per-cube instinct combined: a cube-level `sectionMask` gives partial
vertical capture and near-free uniform cubes, while the column keeps the file count 4–24× lower than
one file per cube. File count is the binding constraint here — git's index and Windows checkouts
degrade badly past ~50 000 files, and a full-column capture at cube granularity would blow through
that on a single large scenario.

There is **no `manifest.json`**. Every file states its own coordinates and contents; the directory
listing *is* the manifest, and `capture.json` holds only what is genuinely per-capture.

### 3.2 `c.<x>.<z>.sbc`

```
magic        "SBC1"  4 bytes
version      u8
flags        u8               reserved, must be 0
chunkX       i32              self-describing: a renamed or moved file still says what it is
chunkZ       i32
minSectionY  i16              the section index bit 0 of sectionMask refers to
sectionMask  i64              which 16³ cubes are present
palette      count u16, then count × (u16 byteLength, UTF-8 bytes)   column-local
per present cube, in ascending Y:
  kind       u8               0 = uniform, 1 = packed
  uniform:   paletteId u16
  packed:    localCount u16, localIds u16[localCount], bitsPerEntry u8,
             data i64[ceil(4096 / floor(64 / bitsPerEntry))]   (no straddling — Anvil 1.16+ style)
crc32        u32              over every preceding byte
```

A packed cube indexes a **cube-local table** which in turn indexes the column palette. A column may
hold sixty states while any one cube in it holds three, and it is the cube's own count that sets the
entry width — so a stone-and-air cube costs one bit per block rather than the six its column would
otherwise impose. On mixed terrain that is a 512-byte cube instead of 3 KB.

Column-local palette rather than capture-global: a global palette would make every column file
depend on a shared file that changes whenever any column is re-captured, which is exactly the
diff-locality we are buying this layout to get. A column's palette is 10–60 entries.

A cube **absent from `sectionMask` was never captured** — which is not the same as air, and the
distinction is load-bearing (§3.5). All-air cubes that *were* captured are stored as `uniform → air`,
costing three bytes.

### 3.3 Partial vertical capture

The copier takes a vertical mode, because the difference between "surface band" and "full column" is
a 6× difference in corpus size:

| Mode | Meaning |
|---|---|
| `--surface <below> <above>` | per-column band around that column's terrain height — **follows the terrain**, so a mountain route does not need a band tall enough for the mountain |
| `--y <min> <max>` | a fixed absolute band |
| `--full` | the whole column |

The surface-following mode is the important one. A fixed band sized for the tallest point of a route
wastes most of its volume everywhere else; a following band is uniformly thin. It means the captured
region is not a box, but since `sectionMask` records exactly what exists, nothing has to guess.

### 3.4 Raw payloads, not deflated — and why

Cube payloads are stored **uncompressed**.

The deciding argument is byte-stability. `Deflater` output depends on the JDK's zlib and its
level and strategy defaults, so a re-capture on a different JDK would produce different bytes for
identical blocks. Raw payloads are a pure function of the world, so re-capturing an unchanged region
is a genuine no-op: the copier compares and skips the write, and `/copier copy --overwrite` over
untouched terrain reports every column unchanged.

(In practice a live server is never quite untouched — grass grows, vines descend — so a re-capture
of a populated area typically reports one or two columns changed out of eighty. That is the
mechanism working, not failing.)

### 3.5 Size

Measured per 16³ cube, raw:

| Cube kind | Distinct states | bits/entry | Raw |
|---|---|---|---|
| uniform (all stone, all air) | 1 | — | ~3 B |
| sparse (stone + air) | 2 | 1 | ~530 B |
| typical surface | ~20 | 5 | ~2.6 KB |
| dense (village, mineshaft) | ~60 | 6 | ~3.2 KB |

Per column: a **surface band** (3 cubes) ≈ 6–8 KB, a **full overworld column** (24 cubes, mostly
uniform stone below) ≈ 16 KB. Measured on a real capture: 81 columns of a surface band came to
406 KB, and 25 Nether columns to 62 KB.

A capture is therefore a few megabytes for a scenario-sized region and a few hundred for the longest
routes. None of it is version-controlled, so the only budget that matters is the disk of whoever is
running the benchmark — which is why `/copier estimate` reports size and file count before a job
rather than after.

**Use `--full` for a scenario-sized capture.** Vertical trimming is a real lever at hundreds of
chunks, but below that it buys a few megabytes and costs a class of failure: a band too thin for
what the search wants to do produces a degenerate run (§5.1a), and until Tier 2 gives the search a
real heuristic there is no way to predict how far it will wander. The first real scenario ran off a
±24 surface band twice before a `--full` capture of the same region — 288 columns, 6.6 MB — worked
first time. Trim only when the capture is big enough for it to matter.

---

## 4. `stonebrickcopier` — the Paper helper plugin

Lives at `project/stonebrick/copier`, applies `cobblestone.paper-plugin-conventions`, depends on
`:stonebrick-format` and `:paper-core`. Named for what it does, not for Cobblestone.

### Commands

All under one root, `/copier`:

```
/copier copy <x1> <z1> <x2> <z2> [options]     capture a chunk rectangle
/copier copy here <radiusChunks> [options]
/copier estimate <same args as copy>           size/file-count report; captures nothing
/copier mark <scenario> origin|dest            stamp your position into a scenario stub
/copier traits [capture]                       rebuild traits.tsv from the capture's own palettes
/copier status | /copier cancel
```

Options: `--name <capture>` · `--world <key>` · `--blocks` (coordinates are block, not chunk) ·
`--surface <below> <above>` | `--y <min> <max>` | `--full` · `--overwrite` · `--force`.

Coordinates are **chunk** coordinates by default; `~` relative coordinates are supported.

**`/copier estimate` is not optional.** The region a capture covers cannot be adjusted afterwards
without re-running the whole job, so knowing the cost while still standing in the world is what
makes the vertical mode a decision rather than a guess. It samples a scattered subset of columns,
encodes them for real, and reports:

```
minecraft:overworld  2,408 columns  --surface 24 24
  on disk   18.9 MB   (sampled 32 of 2,408 columns)
  files     2,408   (capture total after this: 2,489)
```

Everything a command reports goes to **both** the sender and the console. Chat scrolls away, cannot
be copied out of the client, and is gone if the player logs off midway through a long job; the
console log is the one that can be pasted into a bug report.

Flag names are offered as **tab-completions** on the options argument rather than modelled as
literal nodes: the vertical modes are mutually exclusive and the rest combine freely, so a literal
tree would need a branch per legal combination. A suggestion list buys the discoverability without
the thicket.

**`/copier mark`** is what makes authoring 50 scenarios bearable: stand at the start, `mark <id>
origin`; walk to the end, `mark <id> dest`. It writes a scenario stub with both positions, world
keys, and your current capability flags, and it computes the corridor rectangle so `copy` can be run
straight from it.

### Behavior

- Runs asynchronously, reading through the **same offline read path Cobblestone already uses**
  (`NMSChunkReader` / `MoonriseRegionFileIO`), falling back to `getChunkAtAsync` where internals are
  missing. Never loads chunks into the live server if it can avoid it.
- Rate-limited by a configurable chunks-per-tick budget; progress and ETA every few seconds.
- Re-capturing an unchanged region rewrites byte-identical files (§3.4), so `git status` stays clean.
- Guard rails: refuse above `maxChunks` (default 100 000) without `--force`; refuse to overwrite
  without `--overwrite`; warn when a capture would push the corpus past the file-count soft limit.
- Writes to `plugins/StonebrickCopier/out/<capture>/`, laid out exactly as §3.1 so the directory can
  be copied into the repo as-is.

### Where blockstate → traits lives

**The rule: the copier may reach into `paper-core` as deeply as it needs to. Nothing else in
stonebrick may depend on `paper-core` at all.** The copier is a Paper plugin that only ever runs on
a live server; the benchmarking modules must stay pure Java with no server on the classpath. The
`.sbc` + `traits.tsv` pair is the boundary between them, and it is a one-way boundary.

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

### 5.1a Reading outside the capture — loud by default

When the search asks for a cube that was never captured, the platform does one of two things,
selected per run by `onMissingCapture`:

| Policy | Behavior | Use |
|---|---|---|
| **`ERROR`** (default) | fail the run with the coordinates and a ready-to-paste `/copier` command | benchmarking |
| `WALL` | answer `ChunkFetch.Failed.permanent()`, i.e. behave as production does for ungenerated chunks | scenarios that are *about* ungenerated terrain (G2, G3) |

`ERROR` is the default because a benchmark that silently walks into a fabricated wall is a
**degenerate run reported as a real number** — the worst failure this harness can have. The error
should be directly actionable:

```
Scenario ow-river-orthogonal read minecraft:overworld cube (78, 4, -20),
which this capture does not contain. The run is degenerate and was aborted.
  /copier copy 72 -26 84 -14 --name corpus-2026-09 --surface 24 24
```

`WALL` is not a lesser mode — treating uncaptured terrain as impassable is exactly what a production
server does under `LOADED_ONLY` or at a world border, and G2/G3 exist to measure that. It just must
be chosen, never defaulted into.

A scenario may additionally declare cubes that answer `transientFailure()` the first N times, to
exercise retry handling.

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
   whatever the binary heap gives. On near-uniform lattice costs that tie group is enormous. → Add a
   monotonically increasing **insertion sequence number** as the final comparator key.

   ⚠️ Worth being precise about what this buys, because it is less than it first appears. Heap order
   among equals is deterministic for a given sequence of operations — it is just *unspecified*, a
   consequence of `PriorityQueue`'s sift implementation. So the tie-break does not fix run-to-run
   variance; it pins down an order the JDK is free to change, and picks FIFO within a group rather
   than arbitrarily. **If insertion order itself varies — which it does whenever modes complete
   asynchronously — the sequence numbers vary with it and the tie-break changes nothing.**
   Reproducibility comes from (1), the deterministic scheduler; this only removes a source of drift
   underneath it.
3. **Hash iteration order.** `HashMap<Cell, Set<CellState>>` iteration feeds repair ordering. →
   Use `LinkedHashMap`/`LinkedHashSet` in the search, or at minimum verify repairs are order-insensitive.

Run every benchmark in both deterministic and realistic-concurrency modes; the first is for A/B, the
second is for "does it still work when threads are real".

---

## 6. Scenarios, and capture on demand

### 6.1 One file, keyed by id

Every scenario lives in the corpus's `scenarios.yml`, rewritten in id order whenever it changes, so
that marking a position in-game produces a one-entry diff rather than a reshuffle.

```yaml
smoke-walk:
  description: Overworld, mixed walking and swimming
  capture: smoke
  world: minecraft:overworld
  tier: LOCAL
  io: zero
  origin:      { x: 13, y: 62, z: -43 }
  destination: { x: 51, y: 69, z: 22, radius: 1 }
  agent:    { hasBoat: true }
  settings: { heuristicWeight: 1.5, maxCellsVisited: 200000 }
  ungenerated: ["4,-2", "5,-2"]
```

`/copier mark <id> origin|dest` updates only the position it names and leaves the rest of the entry
alone, so hand-edited fields survive a mistyped command.

**`ungenerated`** declares chunks the platform presents as never generated, whatever the capture
holds. A live server does meet terrain that does not exist — at a world border, or under a policy
that will not generate it — and a corpus captured from a seeded world would otherwise never
exercise that. Declaring it keeps the terrain complete and the case tested, and it is obvious to a
reader which run is about that and which is not.

### 6.2 Nobody picks a radius

The terrain a scenario needs is **measured, not guessed**:

```
1  a scenario declares only its origin and destination
2  bench run    the search reads terrain; MissingCaptureLog already records every read
                outside the capture, so one run discovers the whole gap rather than the
                first block of it
3               that region is folded into needed-chunks.yml
4  captureCorpus boots the seeded server and captures what is missing
5  repeat until a run touches nothing new — usually two or three rounds
```

The converged region is exactly what the search reaches for, which is the thing a human guessing a
radius can only approximate. `MissingCaptureLog` was built as a safety net and turns out to be the
discovery mechanism.

**A capsule, not a circle.** Every chunk within a radius of the origin-to-destination segment. A
circle is the same idea and is quadratically wasteful on the routes that matter — a 3 000-block
route needs an enclosing circle of radius 1 500, some 27 000 chunks, where the corridor the search
touches is nearer 3 000 — and when the route is short the segment collapses and the capsule *is* a
circle.

⚠️ **Union only, never pruned.** A better heuristic explores less; shrinking the capture to match
would mean two versions of the algorithm were measured against different worlds. The needed region
only ever grows.

⚠️ **A guard on the growth.** A scenario records the chunk count it was accepted at, beside its
baseline. A run wanting more than twice that aborts *before* the next capture, rather than quietly
pulling in gigabytes — a search suddenly reaching for ten times its terrain is either a bug or a
real behavioural change, and either way it wants looking at. A ratio rather than an absolute,
because an optimality-for-speed trade can legitimately move it either way.

### 6.3 What this buys back

With terrain a function of a seed, everything that describes the corpus becomes shareable:

| | committed | why |
|---|---|---|
| `scenarios.yml` | yes | coordinates in a seeded world |
| `corpus.yml` — seed, Minecraft version | yes | the identity of the world |
| `needed-chunks.yml` | yes | derived, small, reproducible |
| baselines | no | a fact about one capture; see below |
| `.sbc` captures | no | large, and regenerable from the above |

⚠️ **Measured since: a seed does not reproduce the blocks.** Two captures of `along-river` from the
same seed and Paper build differ in 157 of 521 columns, down to which decorations exist (cocoa, jungle
logs, a melon), and the route moves with them: 89.53, 87.13 and 88.78 s across three captures, and
777 against 1,137 coarse expansions. That is far past the gate's tolerances. Every run is therefore
stamped with the capture it read (by `capturedAt`) along with its heuristic, weight, cell cap and IO
model, and a baseline that measured anything else is reported as `CONFIGURATION` rather than
compared. Comparisons *within* one capture -- a sweep, an A/B -- are unaffected: both sides read the
same blocks. Shareable baselines would need shared captures.

### 6.4 No golden solves

A true Dijkstra over these distances is not computable — it is the problem the coarse tier exists to
avoid — so a "cost ratio versus optimal" would be either unobtainable or restricted to toy cases.

Instead **every result records both `solveMillis` and `pathCost`**, and neither is read alone. A
change that halves solve time and raises path cost by 30% is not a win; one that does the reverse
may well be. The harness makes both visible in the same row and leaves the judgement to a human.
Regression detection is against committed baselines (§8.4), not against an ideal.

## 7. The capture & scenario catalogue

What to go stand in and copy. Sizes are suggested capture rectangles; **capture a corridor with
margin, not a straight line** — a weight-1.5 search on awkward terrain routinely explores 0.3–0.5 ×
route length perpendicular to the straight line. A search that runs off the capture aborts the run
loudly (§5.1a), naming the region to capture, so the failure is a prompt rather than a silent bad
number. Rule of thumb: a rectangle whose short side is `max(64 blocks, 0.6 × route length)`, and
expect to narrow that considerably once the coarse tier supplies a real heuristic.

### 7.1 Two corpus tiers, and a file budget

28 700 files for one scenario is not acceptable, so the corpus is split:

| Tier | Budget | In git | CI |
|---|---|---|---|
| **`ci`** | **≤ 6 000 files, ≤ 20 MB** | yes | every PR |
| **`local`** | whatever your disk allows | no | never |

A scenario declares its tier. `local` scenarios are the long-haul ones whose value is precisely that
they are enormous (A3, G7, F3); they live on your machine, get run by hand when you are working on
long-distance behavior, and never gate a build.

**The trick that makes the `ci` budget generous rather than cramping: scale the budget, not the
distance.** A3 exists to exercise "the search exhausts its cell limit" — but a 3 500-block route is
only one way to arrange that. A 1 000-block route through awkward terrain with
`maxCellsVisited: 40000` hits the same code path, the same failure mode, and the same rollback and
limit handling, on 3% of the capture. Scenario settings are per-scenario for exactly this reason.

Use real distance only where distance itself is the variable under test — which is a handful of
scenarios, and those are the ones that go in `local`.

### 7.2 Capture-first, not scenario-first

Scenarios are cheap (a JSON file); captures are expensive (thousands of files). So the corpus is
organized around **~15 captures**, each hosting several scenarios:

| Capture | Columns | Hosts |
|---|---|---|
| `fixtures` — hand-built: hut, thin wall, tower, pit, maze, sealed box, one-wide bridge, L-shaped claim | 169 | A15 A16 A17 C1 G1 G4 G5 H6 H7 |
| `plains-short` | 169 | A1 G6 |
| `plains-mid` | 500 | A2 A18 G8 |
| `river` | 450 | A8 A11 |
| `coast` | 400 | A9 A10 A12 |
| `forest-swamp` | 300 | A5 A6 |
| `hills` | 400 | A4 A7 A21 |
| `cave` (full column) | 300 | B1 B2 B3 B5 B6 B7 B8 |
| `massif` | 400 | C2 C3 C4 |
| `nether` | 500 | D1 D2 D3 D4 D7 F4 |
| `portal-pair` (2 overworld ends + nether) | 700 | D5 D6 |
| `end` | 250 | E1 E2 E3 E4 |
| `village` | 200 | A14 H1 H2 H3 H4 H5 |
| `deceptive` | 400 | A19 A20 |
| `fly-open` (surface band only) | 400 | F1 F2 |
| **total** | **≈ 5 540 columns, ≈ 12 MB** | |

Sizing rules that get there:

- **Vertical band first.** A surface traversal needs 2–3 cubes, not a column. Only `cave` and the
  digging scenarios take `--full`, and they are the smallest captures for it.
- **Corridor at 0.35 × route length**, not 0.6. The wider figure guards a search running on today's
  uninformed heuristic; where a baseline scenario genuinely needs that room, shorten the route
  instead and lower `maxCellsVisited` to match.
- **One capture, many scenarios.** Most of the catalogue varies the *agent* or the *destination*, not
  the terrain — A9 and A10 differ only by whether a boat is in the inventory.
- **`/copier estimate` before every capture**, and it reports the running corpus total so the budget
  is visible while you are standing in the world rather than after the commit.

### 7.3 The fixtures capture

### A. Overworld surface

| # | Scenario | What it exercises | Capture |
|---|---|---|---|
| A1 | Flat plains, 200 blocks | baseline; regression canary | 13×13, surface band |
| A2 | Plains, 1 000 blocks | linear scaling | 63×38, surface band |
| A3 | Plains/varied, 3 500 blocks | the long haul that currently exhausts the cell limit | 219×131 — **out of git** |
| A4 | Rolling hills / mountain range crossing | vertical detours, step-up costs | 100×100 |
| A5 | Dense jungle or dark forest | leaves: passable-but-not-footing; huge branching | 60×60 |
| A6 | Swamp, shallow water + lily pads | constant water/land medium switching | 60×60 |
| A7 | Badlands/mesa | sheer walls, mine-vs-go-around | 80×80 |
| A8 | **River running orthogonal to the destination** | *the* motivating failure: boat flood-fills the river | 50×30 |
| A9 | Ocean crossing 1 200 blocks, boat in inventory | boat medium legitimately optimal | 80×50 |
| A10 | Same ocean crossing, no boat | swim cost, should prefer coastal walk | reuse A9 |
| A11 | Coastal: destination inland, river leads away from it | the trap in its purest form | 100×100 |
| A12 | Frozen ocean / ice sheet | `speedFactor > 1` as an attractor | 80×80 |
| A13 | Ravine crossing | narrow impassable gash; detour discovery | 50×50 |
| A14 | Village: destination inside a house | doors, fences, gates, endgame | 30×30 (natural village) |
| A15 | Destination in a room whose door faces away from origin | `ENDGAME_RADIUS` weight taper | **fixtures** |
| A16 | Destination atop a 40-block tower | vertical endgame, climb/pillar | **fixtures** |
| A17 | Destination at the bottom of a covered pit | fall mode, one-way descent | **fixtures** |
| A18 | Route crossing a claimed/protected region | `ScriptedRestriction` rollback with delay | reuse A2 |
| A19 | **Deceptive open plain ending in a cliff; the real route is a winding canyon** | coarse profiles will love the plain — the sharpest test of whether the LOD heuristic is honest | 80×60 |
| A20 | **Optimistic peninsula: looks open at L2, dead-ends at L0** | the re-plan loop and its bound (§10.11) | 70×70 |
| A21 | Amplified / extreme-verticality terrain | L2+ vertical saturation; walk-medium vertical reach | 60×60, full column |

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
| C1 | Thin wall (3 blocks) between origin and destination | mining is obviously right | **fixtures** |
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
| E2 | Ground → top of an obsidian pillar | vertical, no easy footing | reuse E1 |
| E3 | Main island → outer island across the void, with ender pearls | `EnderPearlMode`, ballistic checks | 100×60 |
| E4 | Route skirting the void | fall danger, one-way mistakes | 60×60 |

### F. Flight

| # | Scenario | What it exercises | Capture |
|---|---|---|---|
| F1 | Creative fly, 3 000 blocks, open sky | 3D branching with a near-perfect heuristic — should be trivially fast; if it isn't, that's a finding | 200×120 |
| F2 | Creative fly through a cave system | worst-case 3D fanout in enclosed space | 60×60 |
| F3 | Elytra glide, descending 2 000 blocks | glide mode, one-way vertical budget | 150×100 |
| F4 | Fly with a ceiling (nether roof or build limit) | bounded 3D | reuse D4 |

### G. Pathological & guard-rail

| # | Scenario | What it exercises | Capture |
|---|---|---|---|
| G1 | Sealed box, destination outside | immediate, cheap failure | **fixtures** |
| G2 | Destination in never-generated chunks | permanent chunk failure handling | 40×40 with a hole |
| G3 | Capture with scattered holes | `MinecraftChunk.Unknown` behavior mid-search | 60×60 |
| G4 | Built maze / spiral | heuristic provides nothing | **fixtures** |
| G5 | One-wide bridge over a void chasm | single viable corridor | **fixtures** |
| G6 | Two plausible routes of nearly equal cost | tie-group behavior, determinism check | reuse A4 |
| G7 | Destination 12 000 blocks away | the long-haul case the coarse tier exists for | **out of git**, fetched |
| G8 | **Deliberately narrow capture around a known-good route** | with `onMissingCapture: ERROR`, asserts the search stayed in its corridor — a width regression test | narrow A2 |

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
| H6 | Agent starts *inside* the destination region | degenerate zero-length solve; should be instant | **fixtures** |
| H7 | L-shaped / non-convex single region | `nearestBoundaryCell` returning a point the agent cannot reach directly | **fixtures** |

H2 and H3 are the two that matter and they are **different costs in different tiers** — H2 is a
Tier-3 per-edge projection cost, H3 is a Tier-1 goal-set cost. Worth separating because a town could
plausibly be modeled either way, and this will tell you which modeling is cheaper. You said Tier-1
isn't a priority yet; H3 is the exception, because it is the one Tier-1 cost that scales with
something a server operator controls (town size) rather than with the route.

That is ~56 scenarios across **15 CI captures** (≈12 MB, ≈5 500 files) plus three `local` ones.
Start with **`fixtures`, `plains-short`, `plains-mid`, `river`, `cave`, `deceptive`** — six captures,
about 2 000 files and 4 MB, hosting roughly twenty scenarios including every distinct failure mode,
the re-plan loop (A20) and the corridor-width assertion (G8). `portal-pair` follows when Tier 1
becomes interesting.

**Practical capture procedure:** fly the route in spectator, `/copier mark <id> origin` at the start
and `... dest` at the end — that writes the scenario stub *and* computes the corridor rectangle.
Then `/copier estimate` it, adjust the vertical mode until the size is sane, and `/copier copy`.
Nether and End columns land in their own subdirectory of the same capture; a scenario references
whichever worlds it needs.

---

## 8. The benchmark harness

### 8.1 The runner

`stonebrick-bench` runs a scenario end to end: load the capture, build the platform with the
scenario's `IoProfile`, start the search on a `DeterministicScheduler`, drain it to completion, and
report. Every run is single-threaded against a virtual clock, so a scenario produces the same
numbers on any machine at any speed — which is what makes a committed baseline mean anything.

```
bench run    [--scenario id] [--tier ci|local] [--tag t]   run and compare against baselines
bench accept [--scenario id] [--tier ci|local]             run and record the results as accepted
bench list                                                 show the corpus
```

`run` exits non-zero when a gated metric moved.

**Scenarios are YAML**, not JSON: they are hand-authored fifty times over, so comments and readable
nesting are worth more than machine-friendliness. Results and baselines are JSON, written with a
fixed key order so a regenerated baseline diffs readably, and read back with the same parser (JSON
being valid YAML).

```yaml
id: ow-river-orthogonal
description: Destination inland; a wide river runs across the straight-line route.
tier: CI
capture: river
tags: [overworld, water-trap]
io: sata-ssd
origin:      { world: minecraft:overworld, x: 1240, y: 68, z: -310 }
destination: { world: minecraft:overworld, x: 2015, y: 71, z: -288, radius: 2 }
agent:    { hasBoat: true }
settings: { heuristicWeight: 1.5, maxCellsVisited: 200000 }
```

A JMH harness is deliberately *not* part of this. Under a virtual clock the metric that matters —
nodes expanded, chunk reads, simulated elapsed time — is exact and needs no statistical treatment,
and JMH's steady-state machinery buys nothing for an operation that takes seconds and is
deterministic. Wall time still gets recorded; it just never decides anything (§8.4).

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
| **path cost** and **path time** | the quality half of every result; never read without the time half, and never the reverse |
| success / timeout / limit-exceeded | outcome distribution |
| coarse-tier counters | §10.15 |

Results land as `results/<timestamp>-<git-sha>-<algorithm>.json`.

### 8.3 Search recording (for the visualizer)

Add to `:core` a `Tier2Observer` with no-op defaults:

```java
public interface Tier2Observer {
  static Tier2Observer noop() { ... }
  default void opened(Cell c, double g, double f, int medium) {}
  default void closed(Cell c, double g, double f, int medium) {}
  default void parked(Cell c) {}
  default void removed(Cell c, RemovalReason reason) {}   // restriction rollback, open-set trim
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

### 8.4 Committed baselines and CI

There is no optimal path to compare against, so the reference is **our own best accepted result**,
committed to version control:

```
stonebrick/baselines/<scenario>.json
{
  "scenario": "ow-river-orthogonal",
  "acceptedAt": "2026-09-22", "commit": "2c8853a", "algorithm": "weighted-astar",
  "deterministic": { "nodesExpanded": 148203, "chunkFetches": 3120, "pathCost": 412.75,
                     "pathSteps": too_many, "outcome": "success" },
  "advisory":      { "solveMillis": 1840, "peakNodeBytes": 41205760 },
  "tolerance":     { "pathCost": 0.005, "nodesExpanded": 0.02 }
}
```

**The split between `deterministic` and `advisory` is the important part.** CI machines vary by
2–3× in wall time, so gating on milliseconds produces a test that fails for reasons nobody can act
on. Gate instead on the counters that are exact functions of the input under the deterministic
scheduler (§5.3) with `VIRTUAL` IO: **nodes expanded, chunk fetches, path cost, outcome**. Wall time
is recorded, trended, and shown in the diff, but never fails a build.

⚠️ **This cannot run in CI**, because the captures are machine-local (§3). `bench run` is a local
discipline: run it before opening a pull request, and paste the table if anything moved. Everything
below applies the same way, just with a human rather than a build as the thing that notices.

`bench run` **fails on any out-of-tolerance change**, printing a table:

```
scenario                 nodesExpanded      pathCost     solveMillis (advisory)
ow-river-orthogonal      148203 -> 51882    412.75 ->  418.90    1840 ->   640
                         -65.0%  FAIL       +1.5%   FAIL          -65%
```

A failure is not necessarily a regression — halving node count for 1.5% more path cost is probably
the trade you wanted. The point is that it **cannot pass silently**. The PR author reviews the
table, and if the new numbers are right, regenerates with `./gradlew benchAccept --scenario <id>`
and commits the updated baseline, so the change is reviewed as a diff like any other.

Tolerances default tight (0.5% cost, 2% expansions) because under a deterministic scheduler an
unchanged algorithm should produce *identical* numbers; a non-zero tolerance only absorbs
floating-point drift across JDK versions.

`./gradlew benchCompare --base <file> --head <file>` does the same diff between two ad-hoc runs.

## 9. The visualizer

**Design principle: replay, never live.** The visualizer opens a capture + a recording; it never
holds a running search. That keeps benchmark timings clean, lets you scrub backwards, and lets you
open a run from last week.

- **Rendering:** LWJGL 3 with an instanced/point-sprite shader. JavaFX 3D will not survive a few
  hundred thousand visualized cells. Terrain: greedy-meshed opaque surfaces, streamed per chunk
  column from the capture (already random-access per column — the format pays off twice). Search
  cells: GPU-instanced points or translucent cubes, one draw call.
- **Live radius around the camera**, for both terrain and search cells, and they want *different*
  radii — search cells outnumber visible terrain surfaces by orders of magnitude, so the cell radius
  is typically a fraction of the terrain radius. Both configurable at runtime (`[` / `]`, with
  modifier for the other).

  **Stream in cubes, render in a sphere.** Loading and eviction work on whole chunk columns, because
  that is the storage unit and column-aligned culling is a couple of integer comparisons. The
  *render* cutoff is then a spherical distance test with a short fade band, done per-instance in the
  vertex shader, which costs nothing and avoids the visible square edge that pure cubic culling
  gives you as you fly. Best of both, and no measurable performance difference from cubic.
- **Controls:** WASD + mouse look, Space/Shift for up/down, Ctrl to sprint, `F` to toggle
  cursor capture, `G` to teleport to a typed coordinate, `O`/`P` to jump the camera to
  origin/destination, `N` to follow the expansion frontier as the timeline plays.
- **Timeline:** a scrubber over expansion order, with play/pause, speed, and step-by-one. This is the
  single most useful debugging feature — watching the frontier crawl into the river in slow motion
  will tell you more than any metric.
- **Toggles (keys 1–9 / a side panel):** open set · closed set · final path · candidate-parent edges ·
  parked cells · removed/rolled-back cells · chunk boundaries · capture boundary ·
  restriction regions · **LOD cell bounds and their profiles** (§10), colored by dominant medium.
- **Coloring modes:** by medium · by f · by g · by h · by **h-error** (coarse estimate minus
  realized cost — the money view for §10) · by expansion order (heat over time) · by chunk (IO locality).
- **Y-slice clamp:** restrict rendering to `y ∈ [a, b]`. In practice you will use this constantly;
  a 3D cloud of 200 000 cells is unreadable without it.
- **Side-by-side / A-B:** load two recordings of the same scenario and toggle between them, or show
  one in blue and one in orange. This is how you will evaluate the new algorithm.

---

## 10. Tier 2: the coarse search and its LOD pyramid

This is one piece of work, not two. The pyramid is the data structure; the coarse search is what
reads it; neither is useful alone.

### 10.1 Three tiers, four levels

| | | |
|---|---|---|
| **Tier 1** | transition graph | portals, teleports, mounts. Unchanged in shape; better estimates (§11). |
| **Tier 2** | coarse search over LOD profiles | **new.** Runs backward from the destination and produces a heuristic. |
| **Tier 3** | fine A* over cells | today's `Tier2Search`, renamed. |

**The LOD levels are internal to Tier 2, not tiers of their own.** There is one coarse tier that
happens to search a four-level pyramid — L0 through L3 at 16³, 64³, 256³ and 1024³. Calling the
levels "tiers" would imply they are separate stages with separate outputs, and they are not: they
are one search descending through resolutions.

### 10.2 It produces a heuristic, not a corridor

Tier 2 runs **backward from the destination**. Every coarse node it settles therefore carries a
cost-*to-goal* in its `g`, and Tier 3's heuristic is

```
h3(cell) = coarseCostToGoal(L0 component containing cell) + intraComponentCost(cell)
```

This is the whole design in one line, and the consequences are worth being explicit about:

- **The river pathology disappears before it starts.** Cells in a river that runs away from the goal
  belong to components with a large cost-to-goal, so their `f` is bad and the fine search never fans
  into them. Nothing has to notice a stall and react.
- **Completeness is preserved.** A corridor that gates the fine search can exclude the only real
  route; a heuristic cannot. If a profile is wrong, the fine search pays extra and carries on. The
  worst case is a slow search, never a failed one.
- **A corridor remains available as an optimization**, expressed as a soft cost multiplier outside
  the tube rather than a fence — but measure before adding it. The heuristic alone is expected to do
  nearly all of the work.

Keep coarse costs **optimistic** (§10.8) so `h3` stays near a lower bound, and let `heuristicWeight`
carry greediness explicitly where it can be seen and tuned.

### 10.3 The ladder

Edge length quadruples per level: `edge(L) = 16 << (2L)`, so every cell has 64 children.

| Level | Edge | Cells, 10k×10k world | Residency |
|---|---|---|---|
| L0 | 16 — one chunk section | 625×625×24 ≈ 9.4 M slots, ~1–2 M non-empty | on disk, mmap'd |
| L1 | 64 | 157×157×6 ≈ 148 k | resident |
| L2 | 256 | 40×40×2 ≈ 3.2 k | resident |
| L3 | 1024 | 10×10×1 ≈ 100 | resident |

4× rather than 8×: smoother refinement, and a block edit dirties 64 siblings' worth of parent rather
than 512.

**L0 is 16³ because that is the chunk section** — the IO unit, the storage unit, and the palette
unit. A 32³ base level would span 2×2 chunks horizontally, so profiling one cell would cost four
chunk fetches instead of one; a granularity chosen to save IO would cost more of it.

**Vertical saturation.** A world is ~384 blocks tall, so vertical subdivision runs out before
horizontal does: `cellHeight(L) = min(edge(L), worldHeight)`. L2 and above are therefore 2-D slabs
of full world height, which is the right shape for long-range surface routing. Write the rule down
explicitly — it is exactly the kind of thing that silently produces a wrong index.

`MAX_LEVEL` derives from world size, capped at 3. The Nether (128 tall) saturates a level earlier.

### 10.4 Components at L0, a plain grid above it

At L0, one flood fill over the cube's passable cells splits it into **connected components**. A cube
with a wall down the middle becomes two components with no edge between them, so the main way a
coarse layer lies — "looks traversable, but the two halves are not connected" — goes away for the
price of a single BFS over ≤4096 cells.

**Cap at 4 components per cell**, merging the smallest beyond that and setting a `merged` flag. The
cap costs precision in pathological cubes and buys a fixed-size record, which is what makes the
storage in §10.9 work.

**Above L0 there are no components — just one node per cell.** Deriving parent components would need
a connectivity pass over the child graph, and that is exactly the machinery §10.5 is designed to
avoid. The upper pyramid is a plain dense grid of profile vectors: a mipmap. It is more optimistic
than the truth, which costs expanded nodes and never costs correctness (§10.2), and the descent to
L0 corrects it.

Node key packs into a `long`: level 2 bits · x 22 · z 22 · y 5 · component 2.

### 10.5 The profile: per-medium, per-axis coverage fractions

**Mediums, not modes.** A mode is a fine-grained step generator with exact costs; a medium is the
coarse cost-regime a stretch of travel belongs to. Tier 2 reasons in mediums:

```
WALK (including jumping and step-up)   SWIM   BOAT   CLIMB   FLY   MINE
```

For each component, each of the **3 axes**, and each medium, store one number:

```
coverage[axis][medium] ∈ [0,1]
```

— **the fraction of the 16 slices perpendicular to that axis that contain at least one cell this
medium can occupy**, counted within the component. Four bits each: 3 × 6 × 4 bits = **9 bytes** per
component.

Computing it is **one counting pass, no per-medium search**. For every cell, test which mediums can
occupy it and set a bit in that medium's per-axis slice bitmask:

| Medium | Occupiable cells |
|---|---|
| WALK | solid top below, 2 passable above |
| SWIM | water |
| BOAT | water with air above (a navigable surface) |
| CLIMB | ladders, vines, scaffolding |
| FLY | any passable cell |
| MINE | any breakable cell (bedrock excluded) — a cube property, not confined to a component |

Then `coverage = popcount(mask) / 16`. Six `short`s per axis, one pass over the cube.

**Three axes, not six directions.** A slice has no direction, so `+X` and `−X` share a number.
Direction asymmetry — falling is free, climbing is not — is a property of *cost*, not of terrain, and
belongs in §10.8. This halves the record and removes a class of "did we populate both directions
consistently" bugs.

**Why slices rather than volume.** The cost model needs *distance* coverage — "how much of the
crossing can this medium carry" — and a slice count answers exactly that. A volume fraction does
not: a cube that is 50% water as a single deep pool covers far less crossing distance than one that
is 50% water as a river running the length of it.

**Anisotropy falls out rather than being special-cased.** A lake surface occupies one Y-slice, so
`coverage[Y][BOAT] = 1/16` — nobody writes a rule saying boats do not go up. WALK's Y coverage is the
fraction of Y-slices holding walkable surface, which is your "variability of surface blocks"
intuition, counted rather than estimated. FLY's Y coverage is the air column. MINE covers nearly
everything, at mining cost.

⚠️ **What this deliberately gives up.** Coverage is a *marginal* statistic: 50% boat and 50% walk on
the same axis does not prove the water and the land connect, and interleaved strips with a wall at
every boundary would score identically to a clean half-and-half crossing. We accept that, for two
reasons. First, over-optimism in a heuristic costs **expanded nodes, never correctness** — the fine
search meets the wall, pays, and carries on — whereas the per-medium connectivity search that would
detect it is fiddly, easy to get subtly wrong, and would have to be reimplemented for aggregation at
every level. Second, the L0 component split already catches the coarsest version of the error (a
wall that separates open space), which is the common case.

⚠️ The real risk is not any single wrong cell, it is **systematic optimism making `h3`
uninformative**: if every cell reports "cheap", `h3` collapses toward `euclidean ×
cheapestCostPerBlock` and we are back where we started. `h3` accuracy (§10.15) is the measurement
that detects this, per level, and §10.6 is the mitigation.

### 10.6 Aggregation, and paying for optimism

A parent's coverage along an axis is the mean of its children's coverage along that axis. Sixty-four
children, one average per axis per medium — trivially cheap, and **levels ≥1 cost no chunk IO at
all**, which is the property the whole pyramid rests on.

⚠️ **Optimism compounds upward.** Each level averages away more structure, so an L3 cell is a much
more generous liar than an L0 one. Unchecked, the entry-level search (which runs at L2 or L3) would
be the least trustworthy part of the whole system.

Rather than guessing a correction, **learn one**: keep a per-level scalar `levelBias[L]`, applied to
that level's estimated costs, and fit it from the `h3`-accuracy data the harness already collects
(§10.15) — coarse estimate against realized cost. Ship defaults from a corpus run; let a server
refine them from its own solves if that proves worthwhile. A constant per level is crude, but it is
measured rather than invented, and it directly targets the failure mode that would otherwise be
invisible until someone wondered why long routes plan badly.

Volumes and flags aggregate by summation and union. `partial` is set if any child is unbuilt
(§10.11).

### 10.7 Diagonals — in the coarse search only

The backward passes use **26-connectivity**: all 6 faces, 12 edges, 8 corners. At coarse resolution a
diagonal jump is a real saving in admissibility — forcing an L2 route through axis-aligned steps
overestimates a diagonal crossing by up to 41% (2-D) or 73% (3-D), and that error propagates into
`h3` as systematic pessimism exactly where we want the estimate tight.

Diagonal coverage is **derived, not stored**: `coverage[diag][m] = min` over the 2 or 3 constituent
axes, with span scaled by √2 or √3. Deriving costs nothing to store, and the `min` is appropriately
conservative about connectivity while staying optimistic about cost.

Tier 3 keeps its existing neighborhood; this is a Tier-2-only change.

### 10.8 Turning a profile into a cost, at query time

Coverage is agent-independent; this function is the only place the agent's medium set `M` enters.

Take the mediums available to the agent, order them cheapest-first, and let each claim coverage
along the axis until the crossing is accounted for. Whatever is left over is **not impassable** — it
is priced at a deliberately high but finite fallback:

```
blended(axis, M):
  remaining = 1.0
  cost      = 0.0
  for m in M sorted by costPerBlock ascending:
      share     = min(coverage[axis][m], remaining)
      cost     += share × costPerBlock(m)
      remaining = remaining - share
      if remaining <= 0: break
  cost += remaining × FALLBACK_COST_PER_BLOCK
  return span(axis) × cost × levelBias[level]

FALLBACK_COST_PER_BLOCK = 2 × mineCostPerBlock      // tunable
```

Your 80/10 example: 80% boat, 10% walk, and the last 10% at the fallback rather than at infinity.
Half water and half land averages the two, on the optimistic assumption that they follow one another
rather than interleaving behind a wall.

**Cheapest-first claiming is what makes overlap behave.** FLY can occupy every passable cell, so its
coverage overlaps everything; claiming greedily by cost means a flying agent simply prices the whole
crossing at fly rate, and a walker's FLY coverage never enters the sum at all.

⚠️ **The fallback constant is doing real work, and it is a genuine tuning risk.** Too low and every
cell looks crossable, so `h3` flattens toward euclidean and the tier stops informing anything. Too
high and sparsely-covered cells look like walls, `h3` becomes pessimistic, and the fine search is
pushed away from routes that are in fact fine — which costs path quality, the one thing optimism was
protecting. Sweep it on the corpus early; it is one number and it moves everything.

A strictly-admissible variant — price the whole crossing at the cheapest medium with any coverage at
all — is worth keeping behind a flag as an A/B baseline. It is a true lower bound and a weak
estimate, and the harness should say which trade is better rather than this document guessing.

### 10.9 Storage: a server-side runtime cache, not capture data

⚠️ **The pyramid and the captures of §3 are unrelated things** and the design should never conflate
them. Captures are machine-local benchmark *input*, one file per chunk column, optimized for
byte-stable re-capture. The pyramid is a *derived runtime cache* living on a production server, optimized for
dense spatial reads. Different lifetimes, different consumers, different layouts.

```
plugins/Cobblestone/lod/<world-key>/
  L0/r.<rx>.<rz>.lod      32×32 cells in XZ, all Y layers
  L1/r.<rx>.<rz>.lod
  L2/, L3/                small enough to be one file per level
  meta.json               world key, minY/maxY, level count, format version
```

"32×32 cells per file" is a *grouping* choice for this cache: it bounds file count and gives read
locality, and unlike the captures there is no reason to keep diffs small because nothing versions it.

**Not a database.** Access is dense, purely spatial, and never queried by anything but coordinates,
so an embedded DB buys indexing we do not need and adds a dependency, a write path, and write
amplification.

**Sparsity without losing O(1) addressing.** A fixed slot for every L0 cell would be over a gigabyte
on a large world, most of it solid stone. Instead each column carries a `long` **presence mask** of
which Y-layers have any component, and a cell's slot index is

```
slot = columnBase + popcount(presenceMask & ((1L << layer) - 1))
```

One popcount, no index structure — the same trick as `sectionMask` in §3.2 and in Anvil itself. With
4–8 interesting layers per column rather than 24, and ~12 bytes per component record (9 for
coverage, 3 for volumes and flags), L0 lands around **150–250 MB for a fully explored 10k×10k
world**. On disk, not heap.

**Residency.** L1 and above load fully into RAM at startup (a few MB) — so *all long-range
reasoning is in-memory*. L0 is `mmap`'d, so the OS page cache is the cache: warm reads are
memcpy-speed, eviction is the kernel's problem, and it survives restarts. A small on-heap LRU holds
decoded components for the cells the current searches are touching.

⚠️ **Concurrency.** A background profiler writes while searches read from worker threads. Use a
**seqlock per cell**: even version means stable, odd means being written; a reader reads version,
record, version again, and retries on mismatch. Lock-free on the read side and it makes torn reads
impossible. Do not skip it — a torn profile is a wrong heuristic that yields a plausible-looking bad
path, the hardest kind of bug to notice.

⚠️ **Disk budget is operator-visible.** Cap it in config, evict coldest L0 region files when over,
and never let the pyramid grow unbounded on a server with a 200 GB world.

### 10.10 The search: top-down, every level, each one cheap

**Yes — every level, in order, no skipping.** You do not jump from L2 to L0. Each pass is cheap
precisely because *the level above it is its heuristic*:

1. **Pick the entry level** so the route spans roughly `TARGET_SPAN` ≈ 32 cells:
   `L = clamp(0, floor(log4(distance / 16 / TARGET_SPAN)), MAX_LEVEL)`.
   A 12 000-block route is 750 L0 cells, 187 L1, 47 L2 → enter at **L2**.
2. **Backward A\*** at level L from the destination, over resident data, 26-connected. Its `g`
   values are cost-to-goal for every level-L component it settles.
3. **Descend.** Backward A* at level L−1, seeded from the destination, using the settled level-L
   values as its heuristic and biased toward the children of the level-L route. Because that
   heuristic is near-exact at this scale, the pass expands close to a tube rather than a disc.
4. Repeat to **L0**. Its `g` values are what `h3` reads.
5. Tier 3 runs with `h3`, resuming the L0 pass on demand (§10.12) — and that resumption is itself
   guided by L1, so a miss costs a handful of expansions, not a new search.

Short routes skip levels naturally: a 200-block route enters at L0 and the pyramid never engages,
which is correct — overhead should scale with the problem.

### 10.11 Optimism, partial cells, and the cold cache

An unbuilt or partial cell is **optimistic**: traversable at the best plausible cost for its level.
This is the freespace assumption, and it is what lets step 2 above run *before* L0 exists beneath it.

Without it the pyramid would be useless on a cold cache, because an L2 value cannot exist until its
L0 descendants are built. With it, the L2 search runs immediately over mostly-unbuilt cells, picks a
plausible route, and **refinement builds L0 only in a tube along that route** rather than across
everything the search considered. Cold cost is proportional to route length × tube width.

So the pyramid's real contribution is that it **converts a per-search cost into a one-time-per-area
cost**, and confines the cold-cache cost to a tube. The amortization is what makes it cheap, which
makes persistence and the prewarm command load-bearing parts of the design rather than conveniences.

The consequence is a plan–refine–replan loop: refinement discovers the truth, and if it is
materially worse than assumed, the cells are marked and the level above re-plans. That is the same
anytime-recalc shape `SearchImpl` already runs at Tier 1.

⚠️ **Bound the re-planning.** A long peninsula that looks open at L2 and dead-ends at L0 can
oscillate. Cap re-plans per level (say 3), then widen the refinement tube instead of re-planning
again, then fall back to the euclidean backstop. Track re-plans per solve; a high count is the
signal that the profile model is too optimistic. Scenario A20 exists to exercise exactly this.

Partial cells must be **recomputed, not patched**, when their last child is built: a partial
aggregate was optimistic, and incremental repair would leave the optimism baked in.

### 10.12 When Tier 3 asks about a component Tier 2 never settled

Three causes, which must not be conflated: the backward pass was budget-limited; the component is
coarse-*unreachable* from the goal (possibly a false negative); or the area is not profiled at all.

⚠️ **Never answer with infinity.** An infinite `h` is a fence wearing a heuristic's clothes, and it
reintroduces the completeness hazard §10.2 removed.

**The rule:** an `h3` query on an unsettled component **resumes the backward search** until that
component settles or a small budget expires, then caches the answer for the solve. The backward
search is not a preprocessing pass; it is a lazily-expanded search doing exactly as much work as the
fine search turns out to need.

**The backstop**, when the budget expires or the component really is unreachable:

```
h3(cell) = max(bestSettledCoarseValue, euclidean(cell, goal) × cheapestCostPerBlock)
```

Optimistic, never pessimistic. The fine search will still go there if it must — just last.

⚠️ **Refined `h` values rise**, and changing `h` mid-search breaks the ordering A* assumes. A
component given the backstop that later settles higher has had its `f` understated. **Decision:
freeze a component's `h` for the rest of the solve once handed out.** Slightly stale, but simple and
decoupled from the repair machinery. Revisit once the harness can measure the accuracy cost; the
alternative — letting it change and leaning on the existing restriction-repair path — is more
accurate and considerably more entangled.

### 10.13 Building and invalidating

**Lazy by default, eager on request.** Profile on demand during searches and write through; the
cache fills where players actually go, which on most servers is a small fraction of generated
chunks, and there is no first-run tax. Offer `/cobblestone lod prewarm <radius|world>` for operators
who want predictable performance — throttled, resumable, with progress and an ETA.

**Free profiling at generation.** Hook chunk generation and profile the column while its blocks are
already in memory. New terrain then costs zero extra IO, forever.

**Invalidation.** A block edit dirties one L0 cell → mark it and its ancestor chain → a background
job re-aggregates dirty ancestors on a timer, coalescing edits. The pyramid shrinks 64× per level,
so a single block change touches 1 + 1 + 1 + 1 cells. Bulk edits (WorldEdit) dirty a region and
rebuild once.

**Degrade, never block.** While the pyramid is cold the plugin must still navigate — fall back to
the euclidean heuristic and let searches warm the cache as a side effect.

### 10.14 Tier 3 runs at low weight

Yes — the final fine-grained pass is a low-weight A*, and probably a much lower weight than today's
1.5.

`heuristicWeight = 1.5` exists because the current heuristic is uninformative, so speed is bought
with greed. A coarse `h` removes that reason. And since `h3` is *already* inadmissible, stacking an
explicit 1.5 on top double-counts the same trade with only half of it visible as a knob.

Expect the optimum near **1.0–1.15**, with better paths at equal speed. `ENDGAME_RADIUS` may become
unnecessary too, since the pocket problem it patches is caused by a weighted search refusing to
retreat. Both are one-line sweeps; run them as soon as Tier 2 produces an `h`.

### 10.15 What the harness must measure here

Beyond §8.2: chunk columns profiled per solve, cold vs. warm · L0 slots written · pyramid bytes on
disk · profile cache hit rate per level · nodes expanded per level · re-plans per level · seqlock
read retries · `OPTIMISTIC` vs. `BLENDED` as an A/B axis.

And above all, **`h3` accuracy**: the coarse estimate against the cost Tier 3 ultimately realized,
per component, per level. Build this first. If the correlation is poor, nothing below it matters,
and you will know within a day rather than a month. It is also the visualizer's best view — color
cells by h-error and the failure mode is visible rather than inferred.

---

## 11. Tier 1, once the pyramid exists

Two changes that only become possible with a resident coarse model, both of which you raised.

### 11.1 Estimate virtual paths from the pyramid

`Tier1Estimator` currently prices an unsolved leg as `distance × cheapestCostPerBlock ×
tier1UnsolvedPessimism` and corrects itself from legs it has actually solved. That is a very loose
bound on real terrain, which is why the pessimism factor exists at all.

With L1–L3 resident in memory, Tier 1 can instead query the coarse cost between two positions
directly — no chunk IO, no leg solve, microseconds. That turns the transition graph's edge weights
from a guess into a measurement, and it is exactly what decides:

- **is the portal worth it?** (D5 vs. D6 — a 5 000-block overworld leg against a 625-block nether
  leg plus two portal traversals);
- **which of a town's regions should we aim at?** (H3/H4 — 200 goal nodes whose relative
  attractiveness is currently guessed from straight-line distance).

`tier1UnsolvedPessimism` should shrink or disappear once estimates are this good — another
constant the harness should pick rather than a human.

### 11.2 Re-plan Tier 1 when a leg overruns its estimate

`SearchImpl` re-plans after every *completed* leg. The gap is mid-leg: a leg that is going to cost
three times its estimate will still be solved to completion before anything reconsiders the route,
and on the scenarios that matter that is most of the budget spent before the first decision point.

Add a mid-leg check: when a running Tier 3 solve's `g` at the frontier exceeds its Tier 1 estimate
by a factor, park the solve, feed the realized cost back into the Tier 1 graph, and re-plan. If the
route still goes this way, resume the parked solve rather than restarting it.

This is what makes "the actual path was expensive, so go a different way" possible at all, and you
are right that it was previously untestable — a single Tier 3 solve was slow enough that nobody
could afford to experiment with interrupting it.

⚠️ **Hysteresis or it thrashes.** Two legs that alternately look better as each is partly explored
will ping-pong. Require a decisive overrun (say 2×), cap re-plans per search, and **keep partial leg
results** so a resumed leg does not redo its work. Track leg switches per search as a metric.

Both of these belong to the Tier-1 work batch, after Tier 2 lands.

---

## 12. Phasing

**Nothing in §10 or §11 gets built until phase 8 is done and baselines are committed.** Every
algorithm decision in this document is a bet; the harness is what settles them.

### Part 1 — the harness

1. `stonebrick-format` — the `.sbc` codec, round-trip tests against synthetic columns. No server.
2. `stonebrickcopier` with the `PaperBlockBridge` shim, `/copier estimate` before `/copier copy`.
3. Build the **fixtures** world; capture it. Capture A1 and A2.
4. `stonebrick-platform`, `ZERO` IO only, `onMissingCapture: ERROR`. One scenario end to end.
5. Core prerequisites: `TimeSource`, the insertion-sequence tiebreak, `Tier2Observer`.
6. `SimulatedChunkIo` (all three modes) + `DeterministicScheduler`.
7. `stonebrick-bench`: runner, metrics, result JSON, `compare`.
8. **Capture the rest of the starter corpus and commit baselines.** Wire CI (§8.4).
9. `stonebrick-visualizer`. Before any algorithm work — you will want to *see* the river.

### Part 2 — Tier 2

10. L0 profiling: components, per-medium `reach`, measured in isolation — profile cost per column,
    component counts, bytes on disk.
11. **`h3` accuracy harness** (§10.15). The go/no-go measurement, before any of the pyramid.
12. Backward L0 search + `h3` into Tier 3, with lazy resume, optimistic backstop, frozen `h`.
    Re-measure. Sweep `heuristicWeight` down; A/B `OPTIMISTIC` vs `BLENDED`.
13. The pyramid: L1–L3 aggregation, mmap'd storage, seqlocks, 26-connected coarse search, top-down
    refinement.
14. Optimistic partial aggregates + bounded re-plan loop; lazy build, prewarm, invalidation,
    profile-on-generate.

### Part 3 — Tier 1

15. Pyramid-backed `Tier1Estimator` (§11.1); retune or retire `tier1UnsolvedPessimism`.
16. Mid-leg overrun re-planning with hysteresis (§11.2).
17. Auto-replan when a trip's own movement generates chunks (§13, production behavior).

### Part 4 — only if measurement still asks for it

18. Bounded open set with beacons, if memory is still a problem after Tier 2 (Appendix A).
19. Medium-scheduled frontier (Appendix A), which is most likely to earn its keep under
    `LOADED_ONLY`, where Tier 2 is blind.

---

## 13. Production behavior this design implies

Two things that are not about benchmarking but fall out of the work:

- **Re-plan when the agent's own movement generates chunks.** On a server where Cobblestone is
  configured not to generate terrain, a player walking a trip generates chunks as they go, so
  information the route was planned without now exists. Hook chunk generation near an active trip,
  invalidate the affected L0 cells, and re-plan if the remaining route crosses them or is currently
  blocked. ⚠️ Debounce it — a walking player generates chunks constantly, and re-planning on each
  one would be worse than not re-planning at all.
- **`ChunkLoadPolicy.LOADED_ONLY` leaves Tier 2 blind**, since the pyramid cannot be filled from
  chunks the policy will not read. Under that policy the fine search is on its own, which is where
  the medium-scheduled frontier in Appendix A still earns its keep.

---

## 14. Decisions taken

- **Captures**: one file per chunk column, cube-level `sectionMask`, column-local palette, **raw
  payloads** for byte-stability across JDKs.
- **Captures are not version-controlled.** They are generated from the seed in `corpus.yml` by
  `captureCorpus`, which fixes the landforms but not every block (§6.3). Baselines are local too,
  stamped with the capture and configuration they measured. Benchmark gating is a local discipline
  rather than a CI one.
- **Scale the budget, not the distance**: cell-limit behavior is tested with a small capture and a
  small `maxCellsVisited`, not with a 3 500-block route.
- **No manifest** — every column file carries its own chunk coordinates and section mask, so the
  directory listing is the manifest.
- **Sidecars are sorted TSV, not JSON** (`traits.tsv`, `capture.meta`): line-diffable on
  regeneration, and it keeps `stonebrick-format` dependency-free.
- **Traits**: copier uses the real `PaperBlock` via a split-package shim (`PaperBlockBridge`,
  declared in `org.cobblestonemc.paper` inside the copier module). No `paper-core` widening,
  no second trait table, and no other stonebrick module may depend on `paper-core`.
- **Copier**: `stonebrickcopier`, `/copier <cmd>`, with `estimate` as a first-class command.
- **No golden solves.** Every result carries solve time *and* path cost; regressions are caught
  against committed baselines, gated on deterministic counters, with wall time advisory only.
- **Missing capture data is a loud error by default**, `WALL` only where the scenario is about
  ungenerated terrain.
- **Tiers**: Tier 1 transitions · Tier 2 coarse LOD search · Tier 3 fine A*. Four LOD levels inside
  Tier 2, not four tiers.
- **Tier 2 produces a heuristic, not a corridor**, running backward from the destination.
- **Profiles store per-medium, per-axis `coverage`** — the fraction of 16 slices a medium can
  occupy, counted in one pass with no per-medium search. Agent-independent; cost is blended at query
  time cheapest-medium-first, with uncovered span priced at a finite fallback rather than infinity.
- **Components at L0 only**; L1+ is a plain mipmap of coverage vectors, aggregated by averaging.
- **Optimism compounds upward**, corrected by a per-level `levelBias` fitted from h3-accuracy data.
- **Diagonals in Tier 2 only** (26-connected), derived from axis coverage rather than stored.
- **LOD storage**: mmap'd fixed-stride region files with per-column presence masks, L1+ resident.
  Not a database. Unrelated to the capture format.
- **Unsettled components**: lazily resume the backward search; optimistic euclidean backstop; never
  infinity. Refined `h` frozen per component per solve.
- **Tier 3 weight expected to fall to ~1.0–1.15**; `ENDGAME_RADIUS` may become unnecessary.
- **Medium scheduling and memory bounding are demoted to Appendix A** — reconsidered only if Tier 2
  underdelivers.
- **Towny regions**: [issue #23](https://github.com/CobblestoneMC/cobblestone/issues/23).

---

## 15. Open questions

- Is `:playground` safe to delete, or does something outside this tree depend on it?
- Does the visualizer need Tier-1 graph state, or are Tier 2 and Tier 3 enough?
- Should §10–§11 eventually move to their own `designs/tiered-search.md`? They are production
  architecture living in a test-harness document, and they will keep growing.

---

## Appendix A — medium-scheduled frontier and memory bounds

Kept because these are real, demoted because Tier 2 is expected to remove the need for them. This
absorbs the substance of the retired `design/13-medium-scheduled-search.md`, which reached the same
shape independently — the `OpenNodeExpansionPolicy` seam, the watermark, and the stall counter are
all from there.

**The idea.** Partition the Tier 3 frontier into one priority queue per medium behind an
`OpenNodeExpansionPolicy` seam. Track per medium a *watermark* — the smallest straight-line
distance-to-goal ever reached in it — and expansions since the watermark improved. A medium that
stalls is demoted; one that beats its watermark by a margin is restored.

Measuring progress as **distance-to-goal shrinking**, not blocks traversed, is what makes a fixed
stall limit robust to medium size, and it is the load-bearing detail.

**Prefer credits to a binary lock.** Rather than locking a stalled medium outright, give each a
credit multiplier in `[1/64, 1]` that halves on a stall window and snaps back to 1.0 on genuine
progress, with per-round expansion quotas proportional to credit. The stalled medium is still being
sipped, so there is no need for an unlock rule or an all-locked fallback — both fall out. Decay
gradual, recovery instant: being wrong about abandoning a medium costs far more than being wrong
about keeping one.

Concerns if it is ever built:
- Watermarks never worsen, so late in a long search every medium stalls together and the scheduler
  degenerates to round-robin. Benign, but measure "fraction of expansions with all mediums floored".
- Long routes have phases (land → ocean → land). Reset all credits when the *global* watermark
  improves by a large margin, rather than relying on floor-rate luck.
- Keep quota allocation deterministic — largest-remainder over the weights, never sampling — or the
  A/B ability the harness exists for is lost.

**Memory bounding, if still needed.** Not SMA*: it requires reopening closed nodes (which the pump
does not do), it conflicts with the multiple-candidate-parents design that makes restriction
rollback safe, and regeneration here costs disk IO rather than CPU, so thrashing at the bound is
catastrophic. Prefer, in order:

1. **Fewer nodes** — Tier 2. An informed `h` cuts node count by orders of magnitude rather than by a
   constant factor.
2. **Bounded open set with beacons** — batch-drop the worst `f` over a cap, keeping the best-`h`
   nodes immune. ~20 lines, no reopening, no thrash. Turns failure at the cap into degradation at
   the cap, which is what was actually wanted.
3. **Compress the closed set** — exact open-addressed `long` set for duplicate detection (~20×
   cheaper than retaining `Node`s), anchors every k-th parent for reconstruction. Never requires
   reopening.
4. **Spill cold closed nodes off-heap.** Targets OOM specifically, no algorithmic change.
