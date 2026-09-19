---
title: Commands
description: Command reference.
---

# Commands

Cobblestone registers two commands. Both behave identically on Paper and Sponge.

| Command | Aliases |
| --- | --- |
| `/navigate` | `/nav` |
| `/cobblestone` | `/stone` (and `/cobblestone loc` for `location`) |

---

## `/navigate <destination…> [flags]`

**Permission:** `cobblestone.navigate` (default allow) · **Players only**

Resolves the destination, runs a search, and starts a trip if a route is found. With no arguments,
prints usage.

### Destination

One or more words identifying a node in the destination tree. Each provider contributes a branch
keyed by the name of the plugin that registered it:

```
cobblestone location private home
cobblestone location global spawn
cobblestone death
cobblestone player <name>
cobblestone world <name>
towny town <town> home
citizens npc <name>
essentials home <name>
```

Leading words may be omitted if the remainder is unambiguous (`/nav home`). The final word and any
level marked strict by its provider are always required. Ambiguous input is rejected with a list of
candidates. Matching is case-insensitive.

### Flags

| Flag | Argument | Effect |
| --- | --- | --- |
| `-navigator` | navigator id | Display style. Default `trail`. Gated by `cobblestone.navigator.<id>`. |
| `-no-mode` | mode word | Exclude a travel mode from the search. |
| `-no-<mode>` | — | Shorthand: `-no-fly`, `-no-boat`, `-no-mine`, … |
| `-no-world` | world key | Exclude a world from routing. Repeatable. |
| `-no-dimension` | dimension | Exclude a dimension from routing. Repeatable. |
| `-live` | — | Force the trip to re-search periodically. |
| `-no-live` | — | Force it not to. |

Mode words: `walk`, `swim`, `fly`, `mine`, `fall`, `climb`, `boat`, `horse`, `door`.

Trips to moving destinations (players) are live by default; all others are not. A live trip
re-searches every `trips.live_interval_ticks` and yields to `search.max_concurrent_per_player`. A
manual `/navigate` takes priority over a live re-search.

### Examples

```
/nav home
/nav town riverwood home -no-mine
/nav player Steve -no-live
/nav location global spawn -no-dimension the_nether
```

### Output

| Message | Meaning |
| --- | --- |
| `Searching for a route...` | The search started. |
| `Success!` | A trip started. Hover for search time and estimated travel time. |
| `There is no route to that destination.` | `NO_ROUTE` or `DESTINATION_UNREACHABLE`. |
| `The search ran out of resources before finding a route.` | `search.algorithm.max_cells_visited` was hit. |
| `The search took too long and was stopped.` | `search.algorithm.max_wall_clock_seconds` was hit. |
| `No destination matches {0}.` | Nothing resolved. |
| `That destination is ambiguous; be more specific: {0}` | Several candidates. |
| `You already have too many active trips; cancel one first.` | `trips.max_active_per_player`. |
| `Unknown navigator: {0}` | No navigator registered under that id. |

---

## `/cobblestone`

Prints help. All subcommands are also available under `/stone`.

### `/cobblestone location set <name> [-global]`

**Permission:** `cobblestone.location`; `-global` additionally needs
`cobblestone.admin.location.global` · **Players only**

Saves the caller's current block position under `name`. Personal by default; `-global` makes it
available to all players.

```
/stone location set home
/stone location set spawn -global
```

Names are single words. Personal names are scoped per player. A personal and a global location may
share a name; they are distinguished by address (`location private home`, `location global home`).

### `/cobblestone location unset <name> [-global]`

**Permission:** `cobblestone.location`; `-global` needs `cobblestone.admin.location.global`

Removes a personal location, or a global location with `-global`.

### `/cobblestone location list`

**Permission:** `cobblestone.location` · **Players only**

Lists the caller's personal locations and all global locations.

```
Locations (3):
home - minecraft:overworld 128, 71, -344
mine - minecraft:overworld 210, 12, -400
spawn - minecraft:overworld 0, 64, 0 (global)
```

### `/cobblestone trips`

**Permission:** `cobblestone.navigate` · **Players only**

Lists the caller's active trips with id, destination, and estimated remaining time.

```
Active trips (2):
[1] location private home - 2 minutes and 10 seconds
[2] towny town riverwood home - 48 seconds
```

### `/cobblestone cancel [<id>|all]`

**Permission:** `cobblestone.navigate` · **Players only**

Cancels a trip by id. With `all` or no argument, cancels all trips and pending searches.

### `/cobblestone reload`

**Permission:** `cobblestone.admin.reload`

Reloads `config.yml`. Live settings apply immediately. Changes to restart-only settings are reported
and not applied:

```
Cobblestone configuration reloaded.
Some settings changed but need a server restart to apply: data.backend, search.chunks.policy
```

### `/cobblestone portals clear`

**Permission:** `cobblestone.admin.portals`

Deletes all learned portal links. Links are relearned as players use portals.

```
Cleared 42 discovered portal transitions.
```

### `/cobblestone loglevel <level>`

**Permission:** `cobblestone.admin.loglevel`

Sets Cobblestone's console log level until restart: `trace`, `debug`, `info`, `warn`, or `error`.

At `debug`, each search logs its outcome, step count, and timing.
