# Odyssey — Glossary

Canonical vocabulary for the entire project. Every design document uses these terms exactly.
If a term here conflicts with older notes in `odyssey/design.md`, this document wins.

## Naming conventions
- **Project / plugin name:** **Odyssey**
- **User command root:** `/navigate` (alias `/nav`).
- **Admin + player-utility command root:** `/odyssey` (reload, portal cache, waypoints).
- **Integration plugin names:** `OdysseyCitizens`, `OdysseyEssentials`, `OdysseyTowny`, etc.
- **Java package root:** `net.whimxiqal.odyssey`. Every subproject uses a unique subpackage
  (see `01-modules-and-build.md`) so the shaded uberjars merge cleanly.
- **Config parameter keys:** period-delimited, snake_case leaves (e.g. `navigators.trail.particle_type`).

## Generic type parameters
Four type parameters flow through the search so downstream code stays cast-free. Each is **singular
over a whole search** — one agent, one step-type enum, one instruction type, one domain type:
- **`A extends Agent`** — the agent type (e.g. `OdysseyPlayer`).
- **`T extends Enum<T>`** — the **`StepType`** enum (e.g. `MinecraftStepType`).
- **`I`** — the **`Instruction`** payload type (e.g. `MinecraftInstruction`); may be `Void` when unused.
- **`D extends Domain`** — the domain type (e.g. `OdysseyWorld`, `TestWorld`). Different worlds
  (Overworld, Nether) are different *instances* of the same `D`. **Contract:** an embedder uses
  exactly one concrete `Domain` type and distinguishes dimensions with a field (`environment()`),
  never with subtypes.

## Core spatial types
| Term | Meaning |
|------|---------|
| **Cell** | Immutable `(x, y, z)` integer triple. One 1×1×1 unit of space. No domain attached. Atomic unit of all algorithms. |
| **Domain** | A contiguous coordinate space (≈ a Minecraft world), used **as a first-class object** — no id, no registry. Exposes `minY`/`maxY`/`contains` and **must** implement value-based `equals`/`hashCode` (Minecraft delegates to the world's `NamespacedKey`). Cells in different domains are never adjacent. |
| **Position** | Immutable `(Cell, D domain)` pair, `Position<D>`. Used everywhere a located point is needed; hands back the concrete domain object (no lookup). |
| **DomainRegion** | `DomainRegion<D>` — a region of cells within one domain: `contains(Cell)` + `nearestBoundaryCell(Cell)` (+ geometry accessors heuristics may need). A single cell, a 2×3 portal plane, or a whole town are all `DomainRegion`s. Replaces the old "DomainDestination". |

## Movement & search types
| Term | Meaning |
|------|---------|
| **Agent** | Generic marker for "the thing that is navigating." Supplies the set of `Mode`s and `Transition`s available to it. Minimal at `core-api`; extended downstream (`MinecraftAgent`, `OdysseyPlayer`). |
| **Mode** | A method of transportation (walk, swim, fly, mine, fall, boat, horse …). Given a cell + `D domain` + agent context + `TraversalState`, produces reachable neighbor `Movement`s. `Mode<A, T, I, D>`. |
| **StepType** | The enum tagging every `Step` (formerly "ModeType"). Both `Movement`s and `Transition`s declare one. Drives result interpretation + particle choice. Minecraft values include `WALK`/`FLY`/… and action types like `COMMAND`/`MOUNT_HORSE`/`PLACE_BOAT`. |
| **Movement** | The output unit of `Mode.step`: a reachable neighbor `Cell`, the cost, the resulting `TraversalState`, a `StepType`, and a nullable `Instruction`. |
| **Instruction** | Optional, caller-supplied payload attached to a step that requires the player to *do* something (chiefly `CommandInstruction` carrying a command string). Generic `I` on `Mode`/`Transition`/`Movement`/`Step`. |
| **TraversalState** | Immutable, sparse, hashable **typed key→value map** (`TraversalKey<V> → V`) of accumulated agent condition (e.g. `VEHICLE → HORSE`). `DEFAULT` = the empty map. Internal to the search — **not** exposed on the result `Step`. |
| **Step** | One entry in the result `Path`: a `P position`, cumulative cost, `StepType`, and a nullable `Instruction`. `Step<P, T, I>` — generic in the **position type** (`Position<D>` in core; a native located type like `org.bukkit.Location` in a platform façade), not in the domain. No `TraversalState`. |
| **Search** | A single asynchronous path-solving session. Resumable object driven by the `Scheduler`. |
| **SearchHandle** | `SearchHandle<S>` — what `navigate(...)` returns: holds the `CompletableFuture<NavigationResult<S>>` (`future()`) and `cancel()`. `S` is the whole step type, e.g. `Step<Position<D>, T, I>`. |
| **NavigationResult** | Sealed `Success(Path<S>)` \| `Failure(FailureReason)`, plus `success()`; `NavigationResult<S>`. |

## Path types
| Term | Meaning |
|------|---------|
| **Path** | `Path<S>` — the flattened end-to-end result: an ordered `List<S>` (`S` a `Step<…>`) from origin to destination. Each `Step`'s `position` carries its domain (all the same *type*, possibly different *instances*); a domain change / an `Instruction` marks a `Transition` point. Exposes `steps()` + `cost()` (no `first()`/`last()`). |
| **Transition** | `Transition<T, I, D>` — one-directional single-step jump between a `DomainRegion<D>` **origin** and a `Position<D>` **destination** (same domain *type*, usually different world *instances*) that may transform `TraversalState`, optionally carrying an `Instruction`. Has a cost and a `StepType`. Examples: nether portal, `/home` teleport, horse mount. (Renamed from "Tunnel".) |
| **VirtualPath** | An *unsolved* (or partially/fully solved) edge in the Tier-1 graph between two `Transition` endpoints in the same domain. Holds an optimistic cost estimate until Tier-2 A* solves it into concrete `Step`s. Mutable, memoized across recalculations. |

## Destinations
| Term | Meaning |
|------|---------|
| **Destination** | `Destination<D>` with `Collection<DomainRegion<D>> regions()` — one logical destination, possibly spanning domain instances / many endpoints (e.g. "the closest Towny town"). Modeled in Tier-1 as a virtual super-sink. |
| **DestinationTree** | (Plugin layer) A lazily-evaluated tree of named sub-trees and named `MinecraftDestination`s, provided per-agent by a `DestinationProvider`. Drives command tab-completion and resolution. |
| **MinecraftDestination** | (Plugin layer) A `Destination` plus an Adventure display-name `Component` and required permission strings. |

## Display / following
| Term | Meaning |
|------|---------|
| **Navigator** | A pluggable *display strategy* that shows a solved `Path` to a player (e.g. `TrailNavigator` renders particles; `OdysseyCitizens` provides a `guide` animal), prompting the player when it reaches a `Step` bearing an `Instruction`. Built by a `NavigatorFactory`. |
| **Trip** | An *active, per-player* guided session: a `Navigator` bound to a player + current `Path`. May be **live** (periodically re-searches and hot-swaps its `Path`). A player may have several `Trip`s at once. |

## Infrastructure
| Term | Meaning |
|------|---------|
| **FutureOr\<T\>** | Sealed sum type — *either* an immediate `T` *or* a pending `CompletableFuture<T>`. Lets the search consume cache hits synchronously and park only on cache misses. |
| **Scheduler** | Platform abstraction: `runAsync` (worker thread), `runAtPosition(Position, task)` (thread owning that location — Folia region / Paper main / Sponge server), `runGlobal`. |
| **ChunkProvider** | Thread-safe LRU cache of `OdysseyChunk` snapshots, backed by `PlatformApi`, with staleness eviction and read-ahead prefetch. Returns blocks as `FutureOr`. |
| **PlatformApi** | The seam a platform implementation fills: fetch chunk snapshots, display particles, spawn entities, etc. |

## Cost
Cost is measured in **seconds of real traversal/heal time** throughout. All heuristics and mode
costs reduce to time. Damage-inducing actions are costed as a configurable multiplier × the time
it would take to heal that damage. Costs are `double`.
