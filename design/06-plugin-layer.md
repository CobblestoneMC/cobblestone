# Odyssey — `minecraft-plugin-api` & `minecraft-plugin`

`minecraft-plugin-api` is the **developer integration surface** (the plugin API object,
destinations, navigators). It is the first module to depend on **Kyori Adventure** (provided), so
destination names and messages are rich `Component`s. `minecraft-plugin` holds the **shared plugin
behavior** every platform plugin reuses: config, data layer, waypoints, vanilla portal-transition
discovery, Trip management, command support, and i18n.

> **Two API objects, one registered service.** The *platform* API (`PlatformOdysseyApi<P,L>`, `05`)
> is a navigation library you instantiate yourself. The *plugin* API defined here
> (`PlatformOdysseyPluginApi<P,L>`) is the opinionated surface — register destinations and
> navigators — and it is the single thing Odyssey's plugin registers in the server's service
> manager. It **extends** the platform API (rather than exposing it through an accessor), so one
> lookup yields navigation *and* registration with no intermediate hop.

---

## `minecraft-plugin-api` (`net.whimxiqal.odyssey.plugin.api`)

### The plugin API object
**Extends** `PlatformOdysseyApi<P,L>` (it *is* the navigation library) and adds the register methods,
generic over the same native `P`/`L`. The **entire developer-facing surface is native-typed** — no
`OdysseyPlayer`, no `Position` — so a platform developer touches only their own types:
```java
// minecraft-plugin-api
public interface PlatformOdysseyPluginApi<P, L> extends PlatformOdysseyApi<P, L> {
  void registerDestinationProvider(DestinationProvider<P> provider);        // see below
  void registerNavigatorFactory(String id, NavigatorFactory<P, L> factory); // id lower-cased
  // waypoints, trips, config/i18n access surface added as the subsystems land (Phase 6b/6c)
}
```
Each platform binds it (in a small published `*-plugin-api` module, e.g. `paper-plugin-api`) by
extending **both** its native platform API and this surface — a clean same-type interface diamond:
```java
public interface PaperOdysseyPluginApi extends PaperOdysseyApi, PlatformOdysseyPluginApi<Player, Location> {}
```
Odyssey's plugin registers **one** instance of `PaperOdysseyPluginApi` in Bukkit's `ServicesManager`
(Sponge: the service registry); other plugins fetch it and both navigate (`odyssey.navigatePlayer(…)`)
and register (`odyssey.registerDestinationProvider(…)`) on the one object. `registerTransitionProvider`
is inherited from the *platform* API (transitions are an algorithm-graph concern, not a plugin opinion);
destinations and navigators are plugin opinions and are added here.

**Impl by delegation, not inheritance.** The shared `OdysseyPluginApiImpl<P,L>` (in `minecraft-plugin`)
*composes* a `PlatformOdysseyApi<P,L>` and forwards navigation to it while owning the register state;
each platform's impl (`PaperOdysseyPluginApiImpl extends OdysseyPluginApiImpl<Player,Location>`) reuses
its unchanged platform-API impl. Odyssey's own internal providers adapt `P → OdysseyPlayer` at the
platform layer (as `PaperPlayer` already wraps `Player`).

### Destinations

#### `MinecraftDestination`
```java
public interface MinecraftDestination {
  Destination<MinecraftWorld> destination();   // core Destination (a Collection<DomainRegion>)
  Component displayName();                      // Adventure rich text
  List<String> permissions();                  // ALL must be held for a player to use it
}
```

#### `DestinationTree`
A lazily-evaluated tree. Sub-trees and leaves are keyed by unique strings (upper-case allowed, no
special chars; spaces allowed but discouraged). Children are **suppliers** so huge sets aren't
materialized until their node is actually visited. A convenience constructor accepts a fixed map and
wraps it as suppliers.
```java
public interface DestinationTree {
  String key();
  boolean strict();   // if true, this level may never be omitted in commands (no name-promotion)
  Map<String, Supplier<DestinationTree>> subTrees();
  Map<String, Supplier<MinecraftDestination>> destinations();
}
```

#### `DestinationProvider`
Root functional interface, evaluated per player in **native** terms (so results can depend on the
player, and integrators never touch `OdysseyPlayer`):
```java
@FunctionalInterface
public interface DestinationProvider<P> {
  DestinationTree provide(P player);
}
```
Registered via the plugin API's `registerDestinationProvider` (`PlatformOdysseyPluginApi`, above).
Integration plugins (Essentials, Towny, quests) register providers here; so does Odyssey itself for
**waypoints**.

### Navigators

#### `Navigator`
A display strategy bound to a player + `Path`. Ticked by the Trip manager. Like the rest of the
plugin surface it is **native-typed**: the path is located by the platform's native `L`, so a
navigator renders directly in `org.bukkit.Location` (etc.). For brevity call it
`MinecraftPath<L> = Path<Step<L, MinecraftStepType, MinecraftInstruction>>` — the same located-step
shape a platform-API search returns; the Trip layer adapts a core result into it.
```java
public interface Navigator<L> {
  void start();
  void tick();                          // called on a schedule; render/advance
  void update(MinecraftPath<L> newPath); // hot-swap for live trips
  void stop();
  boolean isComplete();                 // destination reached
}

@FunctionalInterface
public interface NavigatorFactory<P, L> {
  Navigator<L> create(P player, MinecraftPath<L> path, NavigatorContext<P> ctx);
}
```
`NavigatorContext<P>` exposes the native `player()` and its Adventure `Audience`; output helpers,
config, and i18n accessors land with the Trip subsystem (Phase 6c). Factories are registered by id
(lower-cased) via the plugin API's `registerNavigatorFactory`; developers can add their own (e.g.
Citizens' `guide`). Odyssey ships the default `trail` factory.

**Prompting on instruction steps.** When a `Navigator` reaches a `Step` whose `stepType` is an action
(`COMMAND`/`MOUNT_HORSE`/`PLACE_BOAT`/…) or that carries a non-null `MinecraftInstruction`, it prompts
the player — e.g. a `CommandInstruction` shows a clickable "run `/home`" message; `PLACE_BOAT` shows
"place a boat here." The navigator exhaustively `switch`es on the sealed `MinecraftInstruction`.

---

## `minecraft-plugin` (`net.whimxiqal.odyssey.plugin`)

### Configuration

#### `config.yml`
Well-documented resource shipped in this module. Every section/parameter carries comments. Sections
(non-exhaustive): `search.*` (limits, heuristic, recalc threshold), `chunks.*` (cache size,
staleness, read-ahead, load policy), `navigators.trail.*` (particle type, buffer length, tick rate,
return-to-trail behavior, label), `trips.*` (max active per player, live re-search interval),
`search_limits.*` (max concurrent searches per player), `data.*` (backend + credentials),
`falls.*` (allow_damaging_falls, damage multiplier, heal rate, max fall damage), `locale.*`
(default locale), `metrics.*` (bStats, prometheus endpoint).

#### `ConfigManager` (registered typed parameters)
```java
public final class ConfigManager {
  <V> ConfigKey<V> register(String key, V def, Codec<V> codec, boolean mutable);
  <V> V get(ConfigKey<V> key);
  void reload();   // re-reads YAML: mutable keys updated; immutable keys that changed → WARN log
}
```
- Keys are period-delimited, snake_case (`navigators.trail.particle_type`).
- **Mutable** params update live on `reload()`. **Immutable** params that were changed in the file
  emit a `WARN` ("change requires restart") and keep the old value.

### i18n
- User-facing strings live in message resource bundles (`messages_<locale>.properties` or YAML) in
  this module's resources, keyed (e.g. `command.navigate.searching`, `error.no_route`).
- `Messages.get(key, locale, args...)` returns an Adventure `Component`. Player messages use
  `player.locale()`; console/system messages use `locale.default` from config.
- **Logger** messages are *not* localized (English, developer-facing).
- A `MiniMessage`-style format is used so translations can carry color/formatting.

### Data layer
Abstract persistence with pluggable backends; admin selects backend + credentials in config.
```java
public interface DataStore {
  void init();                      // create/migrate schema
  PortalTransitionDao portalTransitions();
  SegmentDao railHighwaySegments();
  WaypointDao waypoints();
  PlayerPrefsDao playerPrefs();
  void close();
}
```
- **Backends:** SQLite, H2, MySQL, PostgreSQL, MongoDB. SQL backends share a JDBC base
  (`AbstractJdbcDataStore` with a small migration runner + prepared-statement helpers); Mongo is a
  separate `MongoDataStore`. Only the configured backend's driver need be present at runtime.
- **Placement:** the `DataStore` abstraction and DAOs live **here** (`minecraft-plugin`) because
  persistence is only relevant when Odyssey runs as a plugin — the core library is standalone. Base
  JDBC helpers usable across backends may sit in `minecraft` if reused by non-plugin embedders;
  otherwise they stay here.
- **What's stored:** vanilla portal transitions; rail/highway `CachedSegment`s; player waypoints; player
  preferences. (No path-result caching — dropped by design.)

### Waypoints
Odyssey-owned destinations, exposed as their own `DestinationProvider` (tree key `waypoint`).
- `/odyssey waypoint set <name> [-global]` — store the player's current `Position` under `name`.
  `-global` (admin permission) makes it a server-wide waypoint visible to everyone.
- `/odyssey waypoint unset <name>`.
- Persisted via `WaypointDao` (per-player + global scopes). The provider yields a `waypoint` sub-tree
  of the player's personal + global waypoints as `MinecraftDestination`s.

### Vanilla portal transition discovery
No platform API reveals where a portal leads (the game decides, possibly generating the far portal).
So Odyssey discovers empirically:
- Listen for player portal teleports. On teleport, capture the portal-block plane at the entry as a
  `DomainRegion` origin and the arrival `Position`; create a one-directional `Transition`
  (`stepType = PORTAL`, entry plane → arrival) and persist it.
- The reverse direction is **not** assumed (nether linking is asymmetric); it's created only when a
  player travels back the other way.
- Same process for End portals (and the End exit).
- Admin `/odyssey portals clear` wipes the cache (for buggy/oversized linking).
- These persisted transitions are surfaced to searches via an internal `TransitionProvider`.

### Trip management (following the path)
```java
public final class TripManager {
  Trip startTrip(OdysseyPlayer player, MinecraftPath path, String navigatorId, boolean live);
  List<Trip> trips(UUID player);
  void stopTrip(Trip trip);
  void stopAll(UUID player);   // on logout
}
```
- A **`Trip`** owns a `Navigator` and (if live) a re-search loop. Ticked on a schedule.
- **Follow logic (default `TrailNavigator`):** consecutive movement `Step`s form vectors; project the
  player's current position onto the foremost step's vector; if the projection passes the step's end,
  mark it completed and advance. This tolerates small deviations (a player who cuts a corner still
  "completes" steps in the right direction). The particle buffer always covers the next ~100 cells
  (configurable). When the foremost step is an **action step** (an `Instruction`/action `stepType`),
  the navigator pauses trail advancement and prompts the player instead (see Prompting above),
  resuming once the player is past it (e.g. after the command teleport lands them in the next domain).
- **Return-to-trail:** additionally render a direct particle line from the player to the foremost
  *untraversed* step's origin, so a player who wanders off is guided back cheaply (no re-search).
- **Label:** hovering destination-name text (`showTrailText`) over the trail.
- **Live trips:** periodically re-run the full search and `update()` the navigator with the new
  `Path` (hot-swap) — no world-change listening, no movement tracking required. Interval is
  configurable.
- **Concurrency reconciliation (two knobs):**
  - `search_limits.max_concurrent_searches_per_player` (default **1**) — CPU protection; manual
    searches and live re-searches all draw from this budget; live re-searches for a player are
    serialized round-robin behind it.
  - `trips.max_active_per_player` (default **3**) — how many Trips (live or static) may render at
    once; preserves the "path home + path to the caves simultaneously" use case. Set to 1 to forbid
    multiple live trips.

### Command support (shared helpers)
The actual command *trees* are defined per platform (`07`), but the reusable logic lives here:
- **Destination resolution + name promotion.** Given the registered `DestinationProvider`s and the
  arguments typed so far, compute which destinations are reachable at the current node. If a
  destination's key is unambiguous across all non-`strict` trees, it may be **promoted** toward the
  root so `/nav home` works when only Essentials provides `home`; ambiguity forces the fuller path
  (`/nav essentials home`). `strict` levels can never be omitted.
- **Tab-completion** feeds off the same traversal.
- **Flag parsing helper** for `-navigator <id>`, `-no-world <world>`, `-no-dimension <dim>`,
  `-no-mode <mode>` and aliases (`-no-fly` ⇒ `-no-mode fly`). Produces the mode-exclusion set and
  navigator choice the platform command passes into the search request.
- **Mode-list assembly** for a player: start from all `MinecraftMode`s, drop those gated out
  (`FlyMode` unless `canFly()`, `BoatMode` unless boat in inventory) and those excluded by flags.
- **Transition gathering:** union of registered `TransitionProvider`s + vanilla portal transitions +
  horse mount transition + rail/highway segments, filtered by relevance.
