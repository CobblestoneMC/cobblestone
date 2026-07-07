# Odyssey — Implementation Plan

Build bottom-up so every stage is verifiable before the next depends on it. Each phase lists its
deliverable, the design doc that specifies it, and how to **verify** it (mostly automated; the last
phases need a real server). Do not start a phase until the phase(s) it depends on are green.

## Phase 0 — Repo & build skeleton  (`01`)
- Root Gradle (Kotlin DSL), version catalog, Java 21 toolchain, checkstyle + license-header plugins,
  empty subprojects with correct dependency edges and unique packages.
- **Verify:** `./gradlew build` succeeds on empty modules; checkstyle runs; dependency graph matches
  `01` (a quick Gradle task to print/assert it).

## Phase 1 — `core-api`  (`02`)
- All abstract types/interfaces + `Cell`, `Position`, `Movement`, `FutureOr`, `SearchSettings`,
  `DomainRegistry`, value-type equals/hashCode.
- **Verify:** unit tests for `Cell`/`Position` math & equality, `DomainRegistry` round-trip &
  thread-safety, `FutureOr` immediate/pending semantics and `whenReady`.

## Phase 2 — `core` algorithm  (`03`)  ← the heart
- Generic `Graph` + Dijkstra + `GraphPath`; Tier-2 A* as a resumable `Search`; `HeuristicStrategy`
  (admissible + running-average); recalc loop; cancellation (`SearchHandle`) & limits; flat `Path`
  builder; `OdysseyApi` impl.
- **Verify:** `Graph` unit tests; then Phase 3 supplies end-to-end coverage. Add micro-tests for the
  cooperative `advance()`/park/resume using a deterministic scheduler and hand-made pending futures.

## Phase 3 — `core-test` + algorithm tests  (`09`)
- Test modes (FLY/WALK/DIG), block/world JSON, `WorldManager`, deterministic `Scheduler`, and the
  full test matrix (straight shot, wall avoid, wall tunnel, multi-domain, multi-endpoint,
  vehicle/state, failures, cancellation).
- **Verify:** `./gradlew :core-test:test` green; exact optimal paths asserted with the admissible
  heuristic; running-average tests within tolerance. **This is the correctness gate for the whole
  algorithm** — everything above must be solid here before touching Minecraft.

## Phase 3.5 — `playground` (optional but recommended)  (`09`)
- JavaFX visualizer over `core-test` worlds; path + algorithm animation.
- **Verify:** manually load a few worlds and confirm paths/animation match expectations. Great for
  debugging the heuristics before real-server noise.

## Phase 4 — `minecraft-api` + `minecraft` model  (`04`)
- `MinecraftStepType`, `MinecraftInstruction` (sealed), `MinecraftMode`, `TransitionProvider`,
  `MinecraftAgent`/`OdysseyPlayer`, `Direction`; `OdysseyBlock`/`Chunk`/`World`, `ChunkProvider`
  (LRU + staleness + read-ahead + de-dup), `PlatformApi`/`Scheduler` contracts, the `TraversalKey`s;
  all real modes (Walk/Jump/Swim/Fly/Mine/Fall/Boat/Horse).
- **Verify:** unit-test `ChunkProvider` (hit/miss/staleness/read-ahead/dedup) with a fake
  `PlatformApi`; unit-test each mode's geometry/cost against small hand-built `OdysseyChunk` fixtures
  (a fake block source) — no server needed yet. Confirm mode-list assembly gating.

## Phase 5 — one platform impl: `folia`  (`05`)
- Fill `PlatformApi`/`Scheduler`, wrap chunk snapshots → `OdysseyBlock`/`OdysseyChunk`, wrap
  `Player` → `PaperOdysseyPlayer`, material→predicate table; `PaperOdysseyApi` façade.
- **Verify:** `LOADED_ONLY`/`LOAD_FROM_DISK`/`GENERATE` behavior; a search that only reads loaded
  chunks; correctness of a few block predicates. (Some verification needs Phase 6's plugin harness.)

## Phase 6 — `minecraft-plugin` + `folia-plugin`  (`06`, `07`)
- Config manager, i18n bundles, `DataStore` (start with **SQLite + H2**), waypoints, portal
  discovery, `TripManager` + `TrailNavigator` (follow/return-to-trail/label/live), command helpers;
  then the Folia plugin bootstrap + `/navigate` + `/odyssey` command trees + listeners + metrics.
- **Verify (real server):** boot a Paper/Folia test server; `/nav` to a waypoint and walk the
  particle trail; verify follow logic, return-to-trail, live re-search hot-swap, multi-trip limits,
  cancel on logout; portal discovery creates transitions; `/odyssey reload` respects mutable/immutable;
  tune `read_ahead_margin` and cache size against real search latency.

## Phase 7 — remaining backends + `sponge-16` + `sponge-16-plugin`  (`05`,`06`,`07`)
- Add MySQL, then PostgreSQL, then MongoDB behind `DataStore`. Implement the Sponge impl + plugin
  mirroring Folia (shared logic already in `minecraft-plugin`).
- **Verify:** each backend passes a `DataStore` contract test; Sponge plugin reaches feature parity
  on a Sponge 16 test server.

## Phase 8 — integrations  (`08`)
- `OdysseyEssentials`, `OdysseyTowny`, `OdysseyCitizens` (guide navigator), then quest plugins.
- **Verify:** on a server with each target plugin, confirm destinations/tunnels/navigator appear and
  route correctly (e.g. `/nav home` uses the Essentials teleport transition; `guide` NPC walks the path).

## Phase 9 — hardening
- Rail/highway segment discovery + injection; graph-scale safeguards; Prometheus; perf passes;
  docs (`README`, `CONTRIBUTING`); CI smoke tests against headless servers.

## Cross-cutting "definition of done" per component
- Has a home in exactly one module per the placement rule (no orphans/dupes).
- Public types documented; costs in seconds; no needless casts (checkstyle-clean).
- Unit-tested where logic is non-trivial; behavior verified end-to-end where it has runtime surface.
- Every user-facing string is an i18n key; every tunable is a registered config key.
