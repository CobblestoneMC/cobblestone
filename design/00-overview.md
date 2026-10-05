# Odyssey — Overview

## What Odyssey is
Odyssey is a Minecraft Java-Edition navigation plugin. A player asks to travel from where they
are to some destination; Odyssey computes a low-cost, followable route asynchronously and then
displays it in-game (by default, a trail of particles floating above the ground that the player
walks along). It is a ground-up rewrite of the author's plugin **Journey**, keeping the proven
ideas and fixing the architecture with hindsight.

Odyssey deliberately favors *guiding players along real, walkable routes* over *teleportation*.
On a survival server this keeps the game "unbroken" while still removing the tedium of memorizing
how to get from a deep mineshaft back to base.

## Design pillars
1. **The pathfinding core knows nothing about Minecraft.** All algorithms operate on abstract
   `Cell`/`Domain`/`Mode`/`Transition`/`Destination` types (`core-api` + `core`). Minecraft is one
   *implementation* of those abstractions, and each server platform (Paper/Folia, Sponge) is one
   *implementation* of the Minecraft seam.
2. **Abstract up, never downcast.** Anything that can live in a more abstract module does. Types
   are strongly generic so platform code uses concrete types (`Player`, `Block`) without casts.
   (Enforced by checkstyle + code review; see `10-metrics-and-ops.md`.)
3. **Asynchronous and cooperative.** Searches run on worker threads and never block the server.
   Block/chunk access is modeled as `FutureOr` so a search consumes cached data synchronously and
   only *parks* (yielding its worker to other searches) on a cache miss. This works even on a
   server that gives Odyssey a single worker thread.
4. **Heuristic, not omniscient.** Optimal paths are impossible in principle (undiscovered
   shortcuts, terrain-dependent transport). Odyssey returns a *good* route quickly. Heuristics
   are pluggable and every cost knob is configurable.
5. **Minimal dependencies, Java 21, MIT-licensed.** Kotlin-based Gradle build (Kotlin DSL). No
   Bukkit/Spigot. Fabric deferred.

## End-to-end flow
```
player runs /nav <destination>
        │
        ▼
platform plugin (paper-plugin / sponge-16-plugin)
  • parses command via native command API
  • resolves destination via DestinationTree
  • builds the agent's Mode list (canFly() → FlyMode, boat in inv → BoatMode, …)
  • gathers Transitions from registered TransitionProviders (+ vanilla portal transitions, rail/highway segments)
        │  OdysseyApi.navigate(Scheduler, origin Position, Destination, Modes, Transitions, Heuristic, Settings) → SearchHandle
        ▼
core Search (async, on a Scheduler worker)
  Tier 1 — graph/Dijkstra over Transitions(nodes) + VirtualPaths(edges), optimistic costs, lazy edges
  Tier 2 — per VirtualPath, A* within one Domain using the Modes; blocks via ChunkProvider(FutureOr)
  recalc — if a solved edge overshoots its estimate by >threshold, raise its cost & re-run Tier 1
        │  SearchHandle.future() → NavigationResult (sealed Success(Path) | Failure)
        ▼
platform plugin creates/updates a Trip
  • Navigator (default TrailNavigator) renders the flat Path, prompting on instruction steps
  • live Trips periodically re-search and hot-swap the Path
```

## Scope of v1
**In:** Walk, Jump, Swim, Fly, Mine, Fall, Boat, Horse modes; nether/end portal transitions; minecart
rail + plugin highway cached segments (lite); multi-domain routing; trail navigator with
follow/return-to-trail logic; live trips; waypoints; destination providers; SQL/Mongo persistence;
i18n; Paper/Folia + Sponge plugins; Citizens/Essentials/Towny/quest integrations; core-test engine;
playground visualizer; bStats + Prometheus metrics.

**Out (deferred):** Elytra mode (kept in enum, unimplemented); Fabric; cached path-result reuse;
region-protection break checks (extension API stub only); powered-rail speed nuance.

## Non-goals
- Odyssey never modifies the world. `Mine` mode only means "the route passes through blocks the
  player is expected to break"; Odyssey does not break them.
- Odyssey does not guarantee globally optimal routes.
- Odyssey does not add teleportation of its own (integrations may expose existing teleports as transitions).

## Reading order
`glossary.md` → this file → `01-modules-and-build.md` → `02-core-api.md` → `03-core-algorithm.md`
→ `04-minecraft-model.md` → `05-platform-apis-and-impls.md` → `06-plugin-layer.md`
→ `07-platform-plugins.md` → `08-integrations.md` → `09-testing-and-playground.md`
→ `10-metrics-and-ops.md` → `11-implementation-plan.md`.
