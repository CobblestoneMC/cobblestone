# Odyssey — Platform Plugins

The most downstream shippable artifacts. Each inherits its platform implementation (`folia` /
`sponge-16`) **and** `minecraft-plugin`, then supplies the boilerplate to become a real plugin:
bootstrap, service registration, config load, data-store init, listeners, and the command tree.

- **`paper-plugin`** — the Paper/Folia plugin (`plugin.yml`/`paper-plugin.yml`, `JavaPlugin`).
- **`sponge-16-plugin`** — the SpongeAPI 16 plugin (`@Plugin`, dependency injection entry point).

Both are shaded uberjars (GradleUp shadow), with Adventure & platform API *provided*, and
bStats/config/JDBC/Prometheus *shaded + relocated* (see `01`).

## Bootstrap sequence (both platforms)
1. Construct `PlatformApi` + `Scheduler` for the platform.
2. Load `config.yml` via `ConfigManager` (register all typed keys).
3. Init `DataStore` from config (create/migrate schema); load persisted portal transitions & segments.
4. Create the plugin-owned `TransitionRegistry`, construct the platform impl
   (`new PaperOdysseyApiImpl(plugin, registry)` / Sponge equivalent) — the stateless core is loaded
   internally via `OdysseyApi.load()` — then construct the plugin-API impl over it and register the
   **single** `PaperOdysseyPluginApi` / `SpongeOdysseyPluginApi` service in the platform's service
   manager. (The platform API is reachable via `.platform()`; it is not itself registered.)
5. Register internal providers: waypoints `DestinationProvider` and default `trail` `NavigatorFactory`
   on the plugin API; portal `TransitionProvider` and horse-mount transition source on the platform
   API's `TransitionRegistry`.
6. Register listeners: portal-teleport discovery, last-ridden-horse tracking, minecart-ride segment
   discovery, player-logout → `TripManager.stopAll` + `SearchHandle.cancel()` for that player's searches.
7. Register commands.
8. Start metrics (bStats always; Prometheus if configured).
On disable: cancel active searches, stop trips, `DataStore.close()`.

## Command trees
Defined natively per platform (Paper Brigadier / Bukkit command; Sponge command API) — **no** Cloud
or Aikar dependency. All non-trivial logic delegates to `minecraft-plugin` helpers (`06`), so the two
trees are structurally identical with each platform's syntax flavor.

### `/navigate` (alias `/nav`) — user command
```
/navigate <destination...> [flags]
```
- `<destination...>` — one or more argument nodes resolved through the destination-tree traversal +
  name-promotion helper; tab-completion driven by the same helper.
- **Flags** (via the shared flag parser):
  - `-navigator <id>` — choose the display strategy (default `trail`).
  - `-no-world <world>` / `-no-dimension <dim>` — exclude domains from routing.
  - `-no-mode <mode>` — exclude a mode; aliases like `-no-fly` ⇒ `-no-mode fly`.
  - `-live` — make the resulting Trip live (auto-recalculating).
- Behavior: assemble modes + transitions for the player, honor exclusions/limits, call
  `OdysseyApi.navigate` (keep the returned `SearchHandle` to cancel on logout), message the player
  ("searching…"), then on the handle's future completing start a `Trip` (or report the failure
  reason, localized).

### `/odyssey` — admin + player utility
```
/odyssey reload                      # ConfigManager.reload()  (perm: odyssey.admin.reload)
/odyssey portals clear               # wipe vanilla portal-transition cache (perm: odyssey.admin.portals)
/odyssey waypoint set <name> [-global]   # -global requires odyssey.waypoint.global
/odyssey waypoint unset <name>
/odyssey cancel [all]                # cancel current search(es)/trip(s)
/odyssey trips                       # list the player's active trips
```

## Permission nodes (scheme)
- `odyssey.navigate` — use `/navigate`.
- `odyssey.navigator.<id>` — use a specific navigator.
- `odyssey.waypoint.set` / `odyssey.waypoint.global`.
- `odyssey.admin.*` — reload, portals, etc.
- Destination-specific permissions come from each `MinecraftDestination.permissions()`.

## Sponge version note
Module `sponge-16-plugin` targets SpongeAPI 16. If future Sponge API versions diverge, add sibling
modules (`sponge-<n>` impl + `sponge-<n>-plugin`) or a version-detecting loader that picks the right
implementation at startup; most code stays shared via `minecraft-plugin`.
