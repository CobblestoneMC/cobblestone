# Odyssey — `minecraft-api` & `minecraft`

Two modules turn the abstract core into a Minecraft world model, still platform-agnostic (no
Paper/Sponge types). `minecraft-api` is the thin, publishable, developer-facing surface;
`minecraft` holds the world abstractions and the concrete `Mode`s.

## Module split (authoritative placement)
**`minecraft-api`** (`net.whimxiqal.odyssey.minecraft.api`) — interfaces external devs touch:
`MinecraftStepType`, `MinecraftInstruction` (sealed), `MinecraftMode`, `MinecraftAgent`,
`OdysseyPlayer`, `Direction`, the Minecraft-flavored `MinecraftOdysseyApi` façade, and
`TransitionProvider` (the per-agent async supplier).

**`minecraft`** (`net.whimxiqal.odyssey.minecraft`) — the world model + implementations:
`OdysseyBlock`, `OdysseyChunk`, `OdysseyWorld`, `ChunkProvider`, `PlatformApi`, `Scheduler` (impl
of the core `Scheduler` seam plus MC additions), the concrete `TraversalKey`s, and all `Mode`s
(`WalkMode`, `JumpMode`, `SwimMode`, `FlyMode`, `MineMode`, `FallMode`, `BoatMode`, `HorseMode`),
plus rail/highway segment support.

## `minecraft-api`

### `MinecraftStepType`
The `StepType` enum for Minecraft — both movement types and discrete-action types.
```java
public enum MinecraftStepType {
  // movement (produced by Modes)
  WALK, JUMP, SWIM, FLY, MINE, FALL, BOAT, HORSE,
  // discrete actions (produced by Transitions, or by the first movement of a vehicle mode)
  PORTAL,          // walk-through vanilla portal (no player action)
  COMMAND,         // player must run a command (payload in CommandInstruction)
  MOUNT_HORSE,     // player mounts their horse
  PLACE_BOAT,      // player places & enters a boat
  // reserved
  ELYTRA,          // unimplemented in v1
  RIDE_MINECART;   // supplied as cached segments, not a live step-mode in v1
}
```

### `MinecraftInstruction` (sealed)
The concrete `I` for Minecraft. Navigators exhaustively `switch` on it to decide how to prompt.
Parameterless actions are conveyed by `StepType` alone, so most steps have a `null` instruction; the
essential payload-bearing case is `COMMAND`.
```java
public sealed interface MinecraftInstruction
    permits CommandInstruction /* , future: PlaceBoatInstruction, MountInstruction, … */ {
}
public record CommandInstruction(String command) implements MinecraftInstruction {}
```

### `MinecraftMode`
```java
public interface MinecraftMode<A extends MinecraftAgent>
        extends Mode<A, MinecraftStepType, MinecraftInstruction> {
  @Override MinecraftStepType stepType();
}
```

### `MinecraftAgent` and `OdysseyPlayer`
```java
public interface MinecraftAgent extends Agent {
  boolean canBreak(Cell cell);          // region-protection hook; v1 default = true
  double perCellCostFloor();            // cheapest single-cell cost among this agent's modes
  // (mode list is assembled outside the agent; see plugin layer)
}

public interface OdysseyPlayer extends MinecraftAgent {
  UUID uuid();
  boolean hasPermission(String node);
  boolean canFly();                     // creative/spectator/allow-flight
  boolean hasBoatInInventory();
  Optional<Position> lastRiddenHorse(); // for the horse mount transition
  Locale locale();                      // for i18n message lookup
}
```
`OdysseyPlayer` is an interface; each platform implements it as a thin wrapper around its native
player (`PaperOdysseyPlayer`, `SpongeOdysseyPlayer`) — no downcasting, strong types.

### `Direction`
```java
public enum Direction {
  UP, DOWN, NORTH, SOUTH, EAST, WEST;
  public int dx(); public int dy(); public int dz();
  public Direction opposite();
}
```

### `TransitionProvider`
The lazy, per-agent, async supplier of `Transition`s (lives here, not in `core-api`, since `navigate`
takes a pre-assembled list). Developers register these to expose custom teleports/portals.
```java
@FunctionalInterface
public interface TransitionProvider {
  CompletableFuture<List<? extends Transition<MinecraftStepType, MinecraftInstruction>>>
      compute(OdysseyPlayer player);
}
```

## `minecraft` — world model

### `OdysseyBlock`
Immutable snapshot facts a mode needs about one block. Implemented per platform.
```java
public interface OdysseyBlock {
  boolean isPassable();                 // air/tall-grass/etc: body can occupy
  boolean isSolidFooting();             // can stand on top (full or partial top face)
  boolean isHalfBlock();                // slab/stair-like → step-up target
  boolean isWater();
  boolean isLava();
  boolean isDangerous();                // damages on contact (lava, fire, cactus, magma, …)
  double damagePerSecond();             // for cost of traversing dangerous but passable blocks
  boolean isClimbable();                // ladder/vine (needs adjacent wall) / scaffold (free-climb)
  boolean isEnterable(Direction from);  // can a body enter from this side (carpets, trapdoors…)
  boolean isExitable(Direction to);
  double breakTimeSeconds();            // stone-tool break time; +∞ if unbreakable (bedrock)
  boolean supportsBoat();               // top surface a boat rides (water, and ice for speed later)
}
```
Complex interactables (iron doors requiring a button, connecting fences) are handled coarsely in v1:
- **Fences/walls:** treated as full-height impassable when solid; the diagonal-gap subtlety between
  non-connecting fences is **not** modeled in v1 (documented simplification — conservative: never
  route through fence diagonals).
- **Doors/trapdoors:** a block that is `isEnterable/isExitable` per its open state as reported by the
  platform snapshot; iron-door button logic is out of scope for v1.

### `OdysseyChunk`
Immutable 16×16×height snapshot (wraps the platform's chunk-snapshot type).
```java
public interface OdysseyChunk {
  int chunkX(); int chunkZ(); int domainId();
  int minY(); int maxY();
  OdysseyBlock block(int localX, int y, int localZ);  // localX/localZ 0..15
  long capturedAtMillis();                            // for staleness
}
```

### `OdysseyWorld`
```java
public interface OdysseyWorld extends Domain {
  String key();                          // "minecraft:overworld"
}
```

### `Scheduler` (MC-complete contract)
Extends the core seam with location-aware scheduling for Folia/Sponge.
```java
public interface Scheduler extends net.whimxiqal.odyssey.api.Scheduler {
  void runAtPosition(Position pos, Runnable task);   // thread owning that location
  void runAtPositionLater(Position pos, Runnable task, long delayMillis);
  void runGlobal(Runnable task);                      // global/main-thread work
}
```
"Async" everywhere means "on a worker thread." Folia has region threads + a global thread instead of
one main thread; `runAtPosition` hides that difference (Paper: main thread; Folia: owning region;
Sponge: server thread).

### `PlatformApi`
The seam each platform implementation fills.
```java
public interface PlatformApi {
  Scheduler scheduler();
  /** Snapshot the chunk containing this cell. Completes on any thread. Honors the load policy. */
  CompletableFuture<Optional<OdysseyChunk>> fetchChunk(Cell cell, int domainId, ChunkLoadPolicy policy);
  void displayParticle(OdysseyPlayer player, Position pos, String particleType, int quantity);
  void showTrailText(OdysseyPlayer player, Position pos, Component label);  // hover/holograph text
  // entity spawning etc. added by integration needs (Citizens)
}

public enum ChunkLoadPolicy { LOADED_ONLY, LOAD_FROM_DISK, GENERATE }
```
`fetchChunk` returns `Optional.empty()` when the policy forbids materializing that chunk (e.g.
`LOADED_ONLY` and the chunk isn't loaded) — the search treats absent chunks as impassable.

### `ChunkProvider`
Thread-safe LRU cache of `OdysseyChunk`, the sole block source for modes, exposing `FutureOr`.
```java
public final class ChunkProvider {
  public ChunkProvider(PlatformApi platform, ChunkProviderSettings settings);
  /** Immediate on cache hit; pending (and triggers fetch) on miss. */
  public FutureOr<Optional<OdysseyBlock>> block(Cell cell, int domainId);
  public FutureOr<Optional<OdysseyChunk>> chunk(Cell cell, int domainId);
}

public final class ChunkProviderSettings {
  int maxCachedChunks;          // LRU capacity (config)
  long stalenessMillis;         // default 10_000; evict-on-access if older
  int readAheadMargin;          // default 4: if requested block is within N of a chunk border,
                                //   prefetch the adjacent chunk in that direction (config)
  ChunkLoadPolicy loadPolicy;   // default LOAD_FROM_DISK
}
```
Behavior:
- **Hit:** return immediate; if the snapshot is older than `stalenessMillis`, evict and treat as miss.
- **Miss:** start `platform.fetchChunk(...)`, return a pending `FutureOr`; on completion, insert into
  the LRU and complete.
- **Read-ahead:** when a served block is within `readAheadMargin` of a chunk edge, fire a
  best-effort prefetch of the neighboring chunk(s) so the next linear step is likely a hit. Margin is
  configurable (tune on a real server; a large margin trades memory for fewer parks).
- Thread-safety via a concurrent LRU (e.g. size-bounded `ConcurrentHashMap` + access-order tracking)
  and in-flight-request de-duplication (one fetch per chunk key).

## `minecraft` — modes

All modes are `MinecraftMode<OdysseyPlayer>` (or a narrower agent). They read blocks through the
`ChunkProvider` (injected), so `step` gathers the block cells it needs, returns `FutureOr`, and the
search parks as needed. Geometry uses the coarse 1×1×1 model.

### Shared geometry helpers
- **Standable(cell):** `block(cell).isPassable()` AND `block(cell+UP).isPassable()` (2-tall
  clearance) AND `block(cell+DOWN).isSolidFooting()`.
- **Bodyfits(cell):** `block(cell).isPassable()` AND `block(cell+UP).isPassable()`.
- **No corner-cut(from, to)** for a diagonal move: both orthogonal "corner" cells between `from` and
  `to` must satisfy `Bodyfits` (can't slip through a solid diagonal).

### `WalkMode`
Cardinal + horizontal-diagonal moves to a `Standable` neighbor, plus step-up onto an adjacent
`isHalfBlock`/one-block rise if headroom allows. Diagonals require no corner-cut. Cost = time to walk
one (or √2) blocks at walking speed; sprint speed if a straight run is long enough (v1 may use a flat
walk speed and refine later).

### `JumpMode`
Vertical +1 step-up where `WalkMode` can't (e.g. onto a full block) given 2-tall clearance at the
target and a valid takeoff. Cost = jump time. (Kept separate so cost/particles differ from walking.)

### `SwimMode`
Movement through `isWater` cells. Two sub-behaviors: submerged horizontal swim (body fits through
1-tall gaps when fully underwater) and surface swim. Cost = swim speed. Requires water at the
destination; transitions to/from `WalkMode` at the shoreline.

### `FlyMode`
Full 3D movement (cardinal + all diagonals, incl. vertical) to any `Bodyfits` cell; only in the mode
list when `agent.canFly()`. Cost = fly speed. No footing requirement.

### `MineMode`
Move into an otherwise-blocked neighbor by breaking it, provided `agent.canBreak(cell)` and
`block.breakTimeSeconds()` is finite and the resulting cell is `Standable`/`Bodyfits`. Cost =
`breakTimeSeconds` (stone tools) + the move time. Unbreakable blocks (bedrock, +∞) yield nothing.
This is what lets A* tunnel through a thin wall when that's cheaper than walking around; the
`RunningAverageHeuristic` keeps a 1-block wall from looking infinitely thick.

### `FallMode`
Downward movement into open space below (gravity). Free (≤ safe distance). Beyond the safe fall
distance (default 3 blocks), cost = `damageMultiplier(default 2.0) × timeToHeal(fallDamage)`; a fall
into water/hay/slime/powder-snow at the bottom is free regardless of distance. Falls that would kill
(or exceed `maxFallDamage`) yield nothing (impassable). Master toggle `allow_damaging_falls` (default
true) can forbid any damaging fall. All numbers config-driven.

### `BoatMode` (vehicle → uses `TraversalState`)
In the mode list only when `agent.hasBoatInInventory()` or state already holds `VEHICLE == BOAT`.
- Entering a `supportsBoat` (water) cell from land emits a movement that sets `VEHICLE = BOAT` (and
  `BOAT_CONSUMED = true`); this first movement carries `stepType = PLACE_BOAT` so the navigator
  prompts the player to place & enter a boat.
- While `VEHICLE == BOAT`, movements are `stepType = BOAT`: fast horizontal travel across water
  surface; the wider footprint is approximated by also requiring direction-adjacent water cells clear.
- Leaving water to land clears `VEHICLE` (boat left behind). Cost includes small enter/exit overhead.

### `HorseMode` (vehicle → via mount transition, see below)
In the mode list only when state holds `VEHICLE == HORSE`. Fast ground movement (like `WALK`/`JUMP`
but lower per-cell cost, higher jump), `stepType = HORSE`. The player *enters* horse state by
traversing the **horse mount transition**, not by a step-mode change — this localizes "you must
first reach your horse" to the Tier-1 graph.

## `TraversalKey`s defined by the Minecraft layer
There is no MC-specific `TraversalState` subtype — state is the generic KV map from `core-api`, and
`minecraft` just defines the keys:
```java
public final class MinecraftKeys {
  public enum Vehicle { BOAT, HORSE }
  public static final TraversalKey<Vehicle> VEHICLE = new TraversalKey<>("vehicle");
  public static final TraversalKey<Boolean>  BOAT_CONSUMED = new TraversalKey<>("boat_consumed");
}
```
No key present ⇒ on foot (the `DEFAULT` empty map). Visited-set key = `(cell, TraversalState)`; in a
no-vehicle search every node is `DEFAULT`, so it's effectively cell-only. (A config flag
`collapse_visited_by_cell` can force cell-only keying to save memory, at the documented cost of
possibly discarding a better-state branch.)

## Transitions supplied by the Minecraft layer
- **Mount transition:** for a player with `lastRiddenHorse()`, a `Transition` whose origin region is
  that cell and destination is that `Position`, `cost ≈ 0`, `stepType = MOUNT_HORSE`, and
  `apply(state) → state.with(VEHICLE, HORSE)`. From its destination only `HorseMode` applies, so
  Tier-1's state-aware estimate sees a cheaper edge out of the horse's location.
- **Vanilla portal transitions** (`stepType = PORTAL`) **& rail/highway segments:** discovered/managed
  at the plugin layer (`06`), consumed here as ordinary `Transition`s / pre-solved edges.

## Rail & highway cached segments (v1-lite)
A `CachedSegment` is a stored, directional line-string with a known traversal cost, injected into
Tier 1 as a **pre-solved `VirtualPath`** between its endpoints (so A* never has to "find the rail").
- **Discovery:** passive — when a player rides a minecart (or crosses a plugin-declared highway
  region), record the ridden cells; compress to a line-string; a rail touching >2 other rails marks a
  segment boundary (a player-operated junction), splitting segments there.
- **Cost:** segment length ÷ vehicle speed (powered-rail nuance deferred).
- **Persistence & the same mechanism for plugin "highways"** live in `06`/`08`.
