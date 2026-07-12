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
  first-class `Domain` (no `DomainRegistry` — removed), value-type equals/hashCode.
- **Verify:** unit tests for `Cell`/`Position` math & equality, `Domain` value-equality as a map key,
  `FutureOr` immediate/pending semantics and `whenReady`.

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

## Phase 5 — one platform impl: `paper` (`paper-api` + `paper-core`)  (`05`)  ← done
- Fill `PlatformApi`/`Scheduler`, wrap chunk snapshots → `MinecraftBlock`/`MinecraftChunk`, wrap
  `Player` → `PaperPlayer` and `World` → `PaperWorld`, material→predicate table; the native
  `PaperOdysseyApi` façade (native-`Location` steps, `Position↔Location` adapters, injected
  `TransitionRegistry`).
- **Verify:** `LOADED_ONLY`/`LOAD_FROM_DISK`/`GENERATE` behavior; a search that only reads loaded
  chunks; correctness of a few block predicates. (Full end-to-end verification needs Phase 6a's
  plugin bootstrap.)

## Phase 6 — `minecraft-plugin` + `paper-plugin`  (`06`, `07`)
The largest phase, split into **three orderable sub-phases** (each independently implementable; do
them in order since each builds on the last). The API-layering breaking changes below assume the
Phase-5 refactor (`Step<P,T,I>`, `Path<S>`, native-`Location` façade, `TransitionRegistry`).

### Phase 6a — Foundation (API layering + plugin bootstrap + config + i18n)
- **API layering:** `PlatformOdysseyPluginApi<P,L>` (`minecraft-plugin-api`) with a `platform()`
  accessor + `registerDestinationProvider`/`registerNavigatorFactory`; the published
  `paper-plugin-api` module binds `PaperOdysseyPluginApi extends …<Player,Location>`.
- **Paper plugin bootstrap** (`paper-plugin` uberjar): the `JavaPlugin`; on enable, create the
  plugin-owned `TransitionRegistry`, construct `PaperOdysseyApiImpl(plugin, registry)` and the
  plugin-API impl, and register the **single** `PaperOdysseyPluginApi` service in `ServicesManager`;
  on disable, cancel searches / stop trips / shut the scheduler down.
- **Config:** `ConfigManager` (typed keys, `config.yml`, mutable/immutable reload).
- **i18n:** `Messages` (Adventure `Component`s) + `messages_<locale>.properties`.
- **Verify (real server):** plugin enables; the service resolves and `.platform().navigatePlayer(...)`
  returns a handle; `/odyssey reload` re-reads mutable keys and WARNs on immutable changes.

### Phase 6b — State (data layer + waypoints + destinations)
- `DataStore` + `AbstractJdbcDataStore` (**SQLite + H2** first) + DAOs (portals, segments, waypoints,
  prefs) + migration runner.
- Destinations: `MinecraftDestination`, `DestinationTree`, `DestinationProvider`.
- Waypoints as their own `DestinationProvider` (tree key `waypoint`); `/odyssey waypoint set/unset`.
- **Verify:** `DataStore` contract test on SQLite + H2; set a waypoint, restart, resolve it as a
  destination.

### Phase 6c — Experience (navigators + trips + portals + commands + metrics)
- `Navigator`/`NavigatorFactory`, default `TrailNavigator` (follow / return-to-trail / label / live),
  `TripManager` (concurrency knobs).
- Vanilla portal transition discovery (listeners → persisted `Transition`s surfaced via an internal
  provider).
- Command trees (`/navigate`, `/odyssey`) + shared helpers (destination resolution + name promotion,
  flag parsing, mode/transition assembly); bStats metrics.
- **Verify (real server):** `/nav` to a waypoint and walk the particle trail; follow logic,
  return-to-trail, live re-search hot-swap, multi-trip limits, cancel on logout; portal discovery
  creates transitions; tune `read_ahead_margin` and cache size against real search latency.

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
