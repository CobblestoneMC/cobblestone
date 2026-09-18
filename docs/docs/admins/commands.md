---
title: Commands
description: Every Cobblestone command, its arguments, its permission, and what it prints.
---

# Commands

Cobblestone registers two command trees. Both behave identically on Paper and Sponge.

| Command | Aliases |
| --- | --- |
| `/navigate` | `/nav` |
| `/cobblestone` | `/stone` (and `/cobblestone loc` for `location`) |

---

## `/navigate <destination…> [flags]`

**Permission:** `cobblestone.navigate` (default allow) · **Players only**

Resolves the destination, runs a search, and — if a route is found — starts a trip that draws it.
Called with no arguments it prints its own usage.

### Destination

The destination is one or more words naming a place in the destination tree. Every provider
contributes a branch, keyed by the plugin that registered it:

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

Players may omit leading words as long as what remains is unambiguous (`/nav home`), except for the
final word and any level the provider marked strict. Ambiguity is reported with the candidate
addresses rather than guessed at. Matching is case-insensitive.

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

Liveness defaults per destination: a destination that can move (another player) is live; everything
else is not. A live trip re-searches every `trips.live_interval_ticks` and yields to
`search.max_concurrent_per_player` — a manual `/navigate` always wins over a live re-search.

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

Prints the help listing. Every subcommand below is also reachable through `/stone`.

### `/cobblestone location set <name> [-global]`

**Permission:** `cobblestone.location`; `-global` additionally needs
`cobblestone.admin.location.global` · **Players only**

Saves the caller's current block position under `name`. Personal by default; `-global` stores it
server-wide, where every player can navigate to it.

```
/stone location set home
/stone location set spawn -global
```

Names are single words. Personal names are scoped to the player, so two players may both have
`home`; a personal and a global location may share a name too, and are told apart by address
(`location private home` versus `location global home`).

### `/cobblestone location unset <name> [-global]`

**Permission:** `cobblestone.location`; `-global` needs `cobblestone.admin.location.global`

Removes one of the caller's locations, or — with `-global` — the server-wide one. Tab-completion
offers the caller's names, plus the global ones if they hold the global permission.

### `/cobblestone location list`

**Permission:** `cobblestone.location` · **Players only**

Lists the caller's locations and all global ones, with coordinates. Global entries are marked.

```
Locations (3):
home - minecraft:overworld 128, 71, -344
mine - minecraft:overworld 210, 12, -400
spawn - minecraft:overworld 0, 64, 0 (global)
```

### `/cobblestone trips`

**Permission:** `cobblestone.navigate` · **Players only**

Lists the caller's active trips with their id, destination, and live remaining-time estimate.

```
Active trips (2):
[1] location private home - 2 minutes and 10 seconds
[2] towny town riverwood home - 48 seconds
```

### `/cobblestone cancel [<id>|all]`

**Permission:** `cobblestone.navigate` · **Players only**

Cancels one trip by id, or — with `all`, or with no argument — every trip **and** every search the
player still has running.

### `/cobblestone reload`

**Permission:** `cobblestone.admin.reload`

Re-reads `config.yml`. Settings marked mutable take effect at once. Settings that require a restart
are reported back and the old values are kept:

```
Cobblestone configuration reloaded.
Some settings changed but need a server restart to apply: data.backend, search.chunks.policy
```

### `/cobblestone portals clear`

**Permission:** `cobblestone.admin.portals`

Deletes every portal link Cobblestone has learned. Use this after rebuilding portals, or if a link
was learned while the world was in an odd state. Links are relearned as players travel.

```
Cleared 42 discovered portal transitions.
```

### `/cobblestone loglevel <level>`

**Permission:** `cobblestone.admin.loglevel`

Sets console verbosity for Cobblestone until the next restart: `trace`, `debug`, `info`, `warn`,
`error`. Deliberately not a config setting — it's a debugging dial, and it resets on restart so a
server is never left running at `trace` forever.

`debug` logs one line per search with its outcome, step count and timing, which is the fastest way
to find out why a player's `/navigate` is failing.
