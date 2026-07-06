# Odyssey — Platform Plugins

The most downstream shippable artifacts. Each inherits its platform implementation (`folia` /
`sponge-16`) **and** `minecraft-plugin`, then supplies the boilerplate to become a real plugin:
bootstrap, service registration, config load, data-store init, listeners, and the command tree.

- **`folia-plugin`** — the Paper/Folia plugin (`plugin.yml`/`paper-plugin.yml`, `JavaPlugin`).
- **`sponge-plugin`** — the SpongeAPI 16 plugin (`@Plugin`, dependency injection entry point).

Both are shaded uberjars (GradleUp shadow), with Adventure & platform API *provided*, and
bStats/config/JDBC/Prometheus *shaded + relocated* (see `01`).

## Bootstrap sequence (both platforms)
1. Construct `PlatformApi` + `Scheduler` for the platform.
2. Load `config.yml` via `ConfigManager` (register all typed keys).
3. Init `DataStore` from config (create/migrate schema); load persisted portal tunnels & segments.
4. Build the `OdysseyApi` core (with `DomainRegistry` seeded from the server's worlds) and the
   platform façade (`PaperOdysseyApiImpl` / `SpongeOdysseyApiImpl`); register it in the service
   manager.
5. Register internal providers: waypoints `DestinationProvider`, portal `TunnelProvider`, horse
   mount tunnel source, default `trail` `NavigatorFactory`.
6. Register listeners: portal-teleport discovery, last-ridden-horse tracking, minecart-ride segment
   discovery, player-logout → `TripManager.stopAll` + `OdysseyApi.cancel`.
7. Register commands.
8. Start metrics (bStats always; Prometheus if configured).
On disable: cancel searches, stop trips, `DataStore.close()`.

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
- Behavior: assemble modes + tunnels for the player, honor exclusions/limits, call
  `OdysseyApi.navigate`, message the player ("searching…"), then on completion start a `Trip` (or
  report the failure reason, localized).

### `/odyssey` — admin + player utility
```
/odyssey reload                      # ConfigManager.reload()  (perm: odyssey.admin.reload)
/odyssey portals clear               # wipe vanilla tunnel cache (perm: odyssey.admin.portals)
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
Module `sponge-plugin` targets SpongeAPI 16. If future Sponge API versions diverge, add sibling
modules (`sponge-<n>` impl + `sponge-<n>-plugin`) or a version-detecting loader that picks the right
implementation at startup; most code stays shared via `minecraft-plugin`.
