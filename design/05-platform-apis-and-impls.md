# Odyssey — Platform APIs & Implementations

Two layers per platform, and — new since Phase 5 — a clean split between the **platform layer** (a
navigation *library*) and the **plugin layer** (Odyssey's opinionated plugin, `06`):
- **`*-api`** (`paper-api`, `sponge-16-api`): thin, publishable, developer-facing façade in
  *platform terms* (`Player`, `Location`, `World`). Depends on `minecraft-api`.
- **`*` impl** (`paper-core`, `sponge-16-core`): fills the `PlatformApi`/`Scheduler` seam, wraps
  native objects into the `minecraft` world model, and implements the façade. Depends on
  `minecraft-core` + its `*-api`. **Published** (see `01`) so a developer can shade the platform
  library into their own Odyssey-based plugin.

Fabric is deferred (no module yet). Sponge is targeted at **SpongeAPI 16** first; the module is
named `sponge-16` so a future `sponge-<n>` can coexist for divergent API versions (loaded per the
version discovered at startup).

## Platform API façade (developer-facing, fully native)
The façade is one generic interface parameterized by a platform's native **player `P`** and
**location `L`** types, so all platforms share one shape and one set of default methods. Neither the
inputs nor the results mention core abstractions — even a result `Step` is located by the native
type `L` (not `Position`).
```java
// minecraft-api — platform-agnostic; P and L are unbound so this module never sees Bukkit/Sponge
public interface PlatformOdysseyApi<P, L> {
  SearchHandle<Step<L, MinecraftStepPayload>> navigatePlayer(P player, L destination, SearchSettings s);
  default SearchHandle<Step<L, MinecraftStepPayload>> navigatePlayer(P player, L destination) { … }

  SearchHandle<Step<L, MinecraftStepPayload>> navigatePlayerToRegion(P player, L a, L b, SearchSettings s);
  default SearchHandle<Step<L, MinecraftStepPayload>> navigatePlayerToRegion(P player, L a, L b) { … }

  void registerTransitionProvider(PlatformSingleCellTransitionProvider<P, L> provider);
}

// paper-api — binds the generics to Bukkit types
public interface PaperOdysseyApi extends PlatformOdysseyApi<Player, Location> {}
```
Sponge mirrors this as `SpongeOdysseyApi extends PlatformOdysseyApi<ServerPlayer, ServerLocation>`.
Developer-supplied transitions are also native: `PlatformSingleCellTransition<L>` (a single origin
`L`, a destination `L`, cost, step type, instruction) and its async
`PlatformSingleCellTransitionProvider<P, L>`; the impl adapts each into a core `Transition`
(origin `L` → one-cell `DomainRegion`, destination `L` → `Position`).

### The platform impl is a *library you instantiate*, not a service
`PaperOdysseyApiImpl` takes the owning plugin and a **plugin-owned** `TransitionRegistry<P, L>`:
```java
public PaperOdysseyApiImpl(Plugin plugin, TransitionRegistry<Player, Location> transitions) { … }
```
It builds the `PaperScheduler` + `ChunkProvider`, loads the stateless core via `OdysseyApi.load()`,
and reads/writes the registry it was given (it never owns it — see `06`). It is **not** registered
in any service manager: a developer writing their own Odyssey plugin simply constructs it. Odyssey's
own plugin registers the *plugin-layer* API instead (below and `06`), which exposes this platform
API through an accessor — so exactly one service is registered.

Rationale for platform-specific façades: taking `OdysseyPlayer`/`Position` as inputs or returning
them is too restrictive for third-party devs; native `P`/`L` in and native `Step<L, …>` out lets us
evolve the internal types freely without breaking their code.

## `PlatformApi` / `Scheduler` implementation

### Chunk snapshots
- **Paper/Folia:** `World#getChunkAtAsync(x, z, gen)` returns a `CompletableFuture<Chunk>` off the
  main thread; take a `ChunkSnapshot` and wrap it as `OdysseyChunk`. Honor `ChunkLoadPolicy`:
  `LOADED_ONLY` → only if `world.isChunkLoaded(x,z)` (else empty); `LOAD_FROM_DISK` → `gen=false`;
  `GENERATE` → `gen=true`. On **Folia**, schedule the snapshot capture on the region owning that
  chunk via the region scheduler, then complete the future.
- **Sponge:** use the async world/chunk access API for API 16; wrap the volume/snapshot into
  `OdysseyChunk`. Same policy mapping.

`OdysseyBlock` is implemented by reading block state/material from the snapshot and mapping to the
predicate set (`isPassable`, `isWater`, `breakTimeSeconds`, …) via a per-platform material table
(built once, cached). `breakTimeSeconds` uses vanilla hardness with the stone-tool assumption.

### Scheduling
```
runAsync            → Bukkit async scheduler / Folia async scheduler / Sponge async executor
runAtPosition(pos)  → Paper: main thread; Folia: region scheduler for that location; Sponge: server thread
runGlobal           → Paper: main thread; Folia: global region scheduler; Sponge: server thread
```
Odyssey runs searches on `runAsync` workers; only chunk-snapshot capture and particle/entity output
hop to `runAtPosition`/`runGlobal`.

### Player wrapping
`PaperOdysseyPlayer implements OdysseyPlayer` wraps `org.bukkit.entity.Player`:
- `canFly()` ← `player.getAllowFlight()`/gamemode; `hasBoatInInventory()` ← inventory scan;
  `lastRiddenHorse()` ← tracked by a listener (last horse the player dismounted) persisted per player;
  `hasPermission(node)` ← `player.hasPermission(node)`; `locale()` ← `player.locale()`;
  `canBreak(cell)` ← v1 returns true (region-plugin extension hook wired later);
  `perCellCostFloor()` ← min per-cell cost across the assembled mode list.

### Particles & trail text
`displayParticle` → `player.spawnParticle(...)` (Paper) / particle effect (Sponge), on the player's
region/server thread. `showTrailText` → a text display entity / hologram or action-bar fallback for
the destination label hovering over the trail.

## What lives where (per platform)
| Concern | `folia`/`sponge-16` impl |
|---------|--------------------------|
| `PlatformApi.fetchChunk` | native async chunk + snapshot → `OdysseyChunk` |
| `OdysseyBlock` | wraps snapshot block state; material→predicate table |
| `OdysseyPlayer` | wraps native player |
| `Scheduler` | native scheduler mapping (region-aware on Folia) |
| façade impl (`PaperOdysseyApiImpl`) | builds mode lists, gathers transitions from the injected `TransitionRegistry`, calls `OdysseyApi.load().navigate(scheduler, …, heuristic, settings)`, maps `Position`→`Location` on the returned handle. **Does not** register services (the plugin layer does). |

## Threading contract (restated for platform authors)
1. Never call a native world/entity method off its owning thread. Route through `runAtPosition`.
2. Search math runs on `runAsync`; block data arrives via `ChunkProvider`+`FutureOr`.
3. Chunk snapshots are immutable and thread-safe once captured, so modes read them on workers freely.
4. `GENERATE` policy is off by default (`LOAD_FROM_DISK` default) to avoid terrain-gen lag.
