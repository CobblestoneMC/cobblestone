# Odyssey — Platform APIs & Implementations

Two layers per platform:
- **`*-api`** (`folia-api`, `sponge-16-api`): thin, publishable, developer-facing façade in
  *platform terms* (`Player`, `Location`, `World`). Depends on `minecraft-api`.
- **`*` impl** (`folia`, `sponge-16`): fills the `PlatformApi`/`Scheduler` seam and wraps native
  objects into the `minecraft` world model. Depends on `minecraft` + its `*-api`.

Fabric is deferred (no module yet). Sponge is targeted at **SpongeAPI 16** first; the module is
named `sponge-16` so a future `sponge-<n>` can coexist for divergent API versions (loaded per the
version discovered at startup).

## Platform API façade (developer-facing)
Each platform re-exposes the generic operations with native types so plugin devs never see core
abstractions. Example (Paper/Folia):
```java
public interface PaperOdysseyApi {
  CompletableFuture<NavigationResult<MinecraftModeType>> navigatePlayer(Player player, Location destination);
  CompletableFuture<NavigationResult<MinecraftModeType>> navigatePlayer(Player player, Destination destination);

  void registerTunnelProvider(PaperTunnelProvider provider);         // Function<Player, Future<List<Tunnel>>>
  void registerNavigatorFactory(String id, PaperNavigatorFactory f); // id lowercased; see 06
  void registerDestinationProvider(PaperDestinationProvider p);      // see 06

  OdysseyApi core();                 // escape hatch to the generic API
}
```
Sponge mirrors this as `SpongeOdysseyApi` with `ServerPlayer`/`ServerLocation`. The `*-api` modules
declare these interfaces; the impl modules provide them and register the instance in the platform's
service manager (Bukkit `ServicesManager`, Sponge service registry) so other plugins can fetch it.

Rationale for platform-specific façades: taking `OdysseyPlayer` as an input is too restrictive for
third-party devs, and lets us change `OdysseyPlayer` freely without breaking their code.

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
| façade impl (`PaperOdysseyApiImpl`) | builds mode lists, gathers tunnels, calls `core().navigate`, registers services |

## Threading contract (restated for platform authors)
1. Never call a native world/entity method off its owning thread. Route through `runAtPosition`.
2. Search math runs on `runAsync`; block data arrives via `ChunkProvider`+`FutureOr`.
3. Chunk snapshots are immutable and thread-safe once captured, so modes read them on workers freely.
4. `GENERATE` policy is off by default (`LOAD_FROM_DISK` default) to avoid terrain-gen lag.
