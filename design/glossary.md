# Odyssey — Glossary

Canonical vocabulary for the entire project. Every design document uses these terms exactly.
If a term here conflicts with older notes in `odyssey/design.md`, this document wins.

## Naming conventions
- **Project / plugin name:** **Odyssey** (never "Odysseus" — that was a typo in the original draft).
- **User command root:** `/navigate` (alias `/nav`).
- **Admin + player-utility command root:** `/odyssey` (reload, portal cache, waypoints).
- **Integration plugin names:** `OdysseyCitizens`, `OdysseyEssentials`, `OdysseyTowny`, etc.
- **Java package root:** `net.whimxiqal.odyssey`. Every subproject uses a unique subpackage
  (see `01-modules-and-build.md`) so the shaded uberjars merge cleanly.
- **Config parameter keys:** period-delimited, snake_case leaves (e.g. `navigators.trail.particle_type`).

## Core spatial types
| Term | Meaning |
|------|---------|
| **Cell** | Immutable `(x, y, z)` integer triple. One 1×1×1 unit of space. No domain attached. Atomic unit of all algorithms. |
| **Domain** | A contiguous coordinate space (≈ a Minecraft world). Identified internally by an `int` domain id. Cells in different domains are never adjacent. |
| **Position** | Immutable `(Cell, int domainId)` pair. Used everywhere a located point is needed, to avoid threading `(Cell, Domain)` through every signature. |
| **DomainId registry** | Bidirectional mapping between an external string key (e.g. Minecraft `"minecraft:overworld"`) and the internal `int` domain id. Owned by the API service instance — **not** a static global. |

## Movement & search types
| Term | Meaning |
|------|---------|
| **Agent** | Generic marker for "the thing that is navigating." Supplies the set of `Mode`s and `Tunnel`s available to it. Minimal at `core-api`; extended downstream (`MinecraftAgent`, `OdysseyPlayer`). |
| **Mode** | A method of transportation (walk, swim, fly, mine, fall, boat, horse …). Given a `Position` and agent context, it produces the set of reachable neighbor cells and their step costs. Generic on the agent type: `Mode<A extends Agent>`. |
| **ModeType** | An enum value tagging a `Mode` (so callers can, e.g., render a different particle per mode). `Mode` is generic on its mode-type enum. |
| **Movement** | The output unit of a `Mode.step`: a reachable neighbor `Cell` + the cost to reach it + the resulting `TraversalState`. |
| **TraversalState** | Small, immutable, hashable record of mutable agent condition accumulated during a search (e.g. `vehicle = NONE/BOAT/HORSE`, `boatConsumed`). Carried per search node and passed to successors. |
| **Step** | One entry in a solved `Path`: a `Cell`, cumulative cost to reach it, the `ModeType` used to get there, and the `TraversalState` at that point. |
| **Search** | A single asynchronous path-solving session (formerly overloaded as "Navigation"). Resumable object driven by the `Scheduler`; produces a `NavigationResult`. |
| **NavigationResult** | The outcome of a `Search`: either a solved `PathString` or a failure reason. |

## Path types
| Term | Meaning |
|------|---------|
| **Path** | An ordered series of `Step`s within a **single** `Domain`. The solved form of a `VirtualPath`. |
| **Tunnel** | A single-step traversal between two `Position`s that may cross domains, and may optionally apply a `TraversalState` transformation. Has a traversal cost. One-directional. Examples: nether portal, horse mount, teleport command. |
| **VirtualPath** | An *unsolved* (or partially/fully solved) edge in the Tier-1 graph between two tunnel endpoints in the same domain. Holds an optimistic cost estimate until solved by Tier-2 A* into a concrete `Path`. Mutable and memoized across recalculations. |
| **PathString** | The full end-to-end result: an alternating sequence of `Tunnel`s (nodes) and `Path`s (edges) that connects origin to destination, possibly across multiple domains. |

## Destinations
| Term | Meaning |
|------|---------|
| **DomainDestination** | A destination confined to one domain: a completion predicate (`isSatisfiedBy(Cell)`) + an admissible approximate-cost/heuristic function. Usually just "this exact cell." |
| **Destination** | A mapping of `domainId → DomainDestination`. Lets a single logical destination span domains / many endpoints (e.g. "the closest Towny town"). Modeled in Tier-1 as a virtual super-sink. |
| **DestinationTree** | (Plugin layer) A lazily-evaluated tree of named sub-trees and named `MinecraftDestination`s, provided per-agent by a `DestinationProvider`. Drives command tab-completion and resolution. |
| **MinecraftDestination** | (Plugin layer) A `Destination` plus an Adventure display-name `Component` and a list of required permission strings. |

## Display / following
| Term | Meaning |
|------|---------|
| **Navigator** | A pluggable *display strategy* that shows a solved `PathString` to a player (e.g. `TrailNavigator` renders particles; `OdysseyCitizens` provides a `guide` animal). Built by a `NavigatorFactory`. |
| **Trip** | An *active, per-player* guided session: a `Navigator` bound to a player + current `PathString`. May be **live** (periodically re-searches and hot-swaps its `PathString`). A player may have several `Trip`s at once. |

## Infrastructure
| Term | Meaning |
|------|---------|
| **FutureOr\<T\>** | A value that is *either* an immediately-available `T` *or* a pending `CompletableFuture<T>` — never both. Lets the search consume cache hits synchronously and park only on cache misses. |
| **Scheduler** | Platform abstraction for running work: `runAsync` (worker thread), `runAtPosition(Position, task)` (the thread that owns that location — Folia region / Paper main / Sponge server), `runGlobal`. |
| **ChunkProvider** | Thread-safe LRU cache of `OdysseyChunk` snapshots, backed by `PlatformApi`, with staleness eviction and read-ahead prefetch. Returns blocks as `FutureOr`. |
| **PlatformApi** | The seam a platform implementation fills: fetch chunk snapshots, display particles, spawn entities, etc. |

## Cost
Cost is measured in **seconds of real traversal/heal time** throughout. All heuristics and mode
costs reduce to time. Damage-inducing actions are costed as a configurable multiplier × the time
it would take to heal that damage. `int`-free: costs are `double`.
