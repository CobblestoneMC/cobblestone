---
title: Configuration
description: Every setting in config.yml — defaults, accepted values, and what to change when searches fail or the server is busy.
---

# Configuration

`config.yml` is generated on first run and carries a comment above every setting, so the file is
its own reference. This page covers the same ground with the reasoning attached.

Where it lives:

=== "Paper"

    `plugins/cobblestone/config.yml`

=== "Sponge"

    `config/cobblestone/config.yml`

**Reloading:** `/cobblestone reload` re-reads the file. Settings below marked _live_ apply
immediately. Settings marked _restart_ are read once at startup; changing one and reloading prints
a warning and keeps the running value until the server restarts.

The file is generated for the platform it runs on. A config copied from the other platform may name
settings this one doesn't have — Cobblestone warns about those on startup and keeps its own
defaults.

---

## `locale`

| Setting | Default | |
| --- | --- | --- |
| `locale.default` | `en` | restart |

Language for console and system messages, as a BCP-47 tag (`en`, `en-US`, `fr`). Messages sent to a
player prefer that player's own client language when a translation exists and fall back to this.

## `messages`

| Setting | Default | |
| --- | --- | --- |
| `messages.show_prefix` | `true` | live |

Whether to prepend the `[✦]` badge to every message Cobblestone sends a player.

## `data`

| Setting | Default | Values | |
| --- | --- | --- | --- |
| `data.backend` | `H2` | `H2` | restart |
| `data.file` | `cobblestone` | any name | restart |

Where locations, death locations and discovered portal links are stored. H2 is embedded and needs
no setup; `data.file` is the database file name inside Cobblestone's own folder, without an
extension. Networked backends are not available yet.

## `trips`

A **trip** is a journey in progress: a solved path plus the navigator drawing it.

| Setting | Default | |
| --- | --- | --- |
| `trips.max_active_per_player` | `3` | live |
| `trips.live_interval_ticks` | `100` | live |
| `trips.recalculate_distance` | `32` | live |

- **`max_active_per_player`** — how many trips one player may run at once, so "route home" and
  "route to the caves" can both be on screen. Set it to `1` to allow only one.
- **`live_interval_ticks`** — how often a `-live` trip re-runs its search (100 ticks = 5 seconds).
  Live re-searches are the main recurring cost Cobblestone imposes; raise this before you lower the
  search limits.
- **`recalculate_distance`** — how far a player may stray from the trail in blocks before the trip
  quietly recalculates from where they now are. Below that, they get a short guide path back to the
  trail instead. `0` disables stray recalculation.

## `search`

| Setting | Default | |
| --- | --- | --- |
| `search.max_concurrent_per_player` | `1` | restart |

The most searches — manual and live re-searches together — one player may have running at once.
Live re-searches yield to this budget; a manual `/navigate` always runs. This is the first dial to
turn if a handful of players with live trips are keeping the server busy.

### `search.algorithm`

A* tuning. Raise the limits if searches to distant destinations give up ("ran out of resources" /
"took too long"), at the cost of more CPU and memory per search. Lower them for weak hardware.

| Setting | Default | |
| --- | --- | --- |
| `search.algorithm.max_cells_visited` | `200000` | live |
| `search.algorithm.max_wall_clock_seconds` | `60` | live |
| `search.algorithm.heuristic_weight` | `1.5` | live |
| `search.algorithm.tier1_unsolved_pessimism` | `1.5` | live |
| `search.algorithm.running_average_width` | `5` | live |

- **`max_cells_visited`** — the memory guard. A solve holds a few hundred bytes per cell it
  reaches, so 200 000 cells is on the order of tens of megabytes at peak. Lower it on a small heap;
  raising it is what lets very long routes solve.
- **`max_wall_clock_seconds`** — the whole search's time budget. A search that hits it reports
  "took too long".
- **`heuristic_weight`** — `1.0` finds optimal routes but explores a great deal; higher is much
  faster and slightly suboptimal, bounded by this factor. The default trades a little optimality
  for a large speed-up, which players don't notice and servers do.
- **`tier1_unsolved_pessimism`** — extra margin on a route leg Cobblestone has not solved yet.
  It already corrects its estimate from legs it *has* solved in that world, so this only covers
  what that correction hasn't seen; `1.0` leaves it uncorrected.
- **`running_average_width`** — window width for the running-average heuristic. Leave it alone
  unless you are profiling.

### `search.chunks`

Where the terrain a search walks through comes from — the settings that decide how much work
Cobblestone may ask the server to do on its behalf.

| Setting | Default | Values | |
| --- | --- | --- | --- |
| `search.chunks.policy` | `allow_load` | `loaded_only`, `allow_load`, `allow_load_and_generate` | restart |

- **`loaded_only`** — only chunks already in memory. Searches never load anything and stop at the
  edge of what players are keeping loaded. Cheap, but searches will often fail to find a route that
  plainly exists.
- **`allow_load`** — also read chunks that have already been generated. No new terrain is created.
  The default, and the right answer for nearly every server.
- **`allow_load_and_generate`** — also generate terrain that doesn't exist yet. A long search into
  unexplored territory can generate a great deal of world, **permanently**: your world files grow
  to match. Turn this on only if you want searches to path through land nobody has visited.

=== "Paper"

    Paper can read a generated chunk without keeping it loaded, so `allow_load` is inexpensive.

=== "Sponge"

    Sponge cannot read a chunk that isn't loaded, so anything beyond `loaded_only` means
    Cobblestone force-loads the chunk with a ticket, copies it, and releases the ticket straight
    away. One extra Sponge-only setting bounds that:

    | Setting | Default | |
    | --- | --- | --- |
    | `search.chunks.max_load_requests` | `256` | live |

    The most chunks Cobblestone may hold tickets for at once. Searches read ahead around the
    frontier, so a single step can want a couple of dozen chunks; values below about 64 mostly
    disable that read-ahead and make searches *slower* rather than lighter. Lower it if chunk
    loading is visibly costing you tick time.

## `portals`

No server API reveals where a portal leads — the game decides when a player travels, sometimes
generating the far portal on the spot. So Cobblestone learns links by watching players use them.

| Setting | Default | |
| --- | --- | --- |
| `portals.discovery` | `true` | live |
| `portals.cost_seconds` | `5.0` | live |
| `portals.normalize_entry` | `true` | live |
| `portals.normalize_exit` | `true` | live |

- **`discovery`** — whether to learn links from player teleports. With it off, Cobblestone keeps
  using what it already learned but adds nothing new.
- **`cost_seconds`** — the time a portal traversal is assumed to cost, which is what makes the
  search weigh "through the nether" against "the long way round".
- **`normalize_entry`** — snaps a nether portal *entry* to the source portal's centre, so one
  portal always reaches the same destination. Vanilla picks the destination from the exact sub-block
  you step through, which Cobblestone cannot route to precisely; its single link per portal relies
  on this, and with the usual one-portal-per-side setup it lands where vanilla would anyway.
- **`normalize_exit`** — snaps the *exit* to the destination portal's centre at ground level. Only
  changes where inside the same portal you land.

Learned links are wiped with `/cobblestone portals clear`.

## `deaths`

| Setting | Default | |
| --- | --- | --- |
| `deaths.track` | `true` | live |

Whether to record where each player last died and offer it as the `death` destination. Turning it
off stops recording and hides the destination; what is already stored is kept and reappears if you
turn it back on. To deny it per rank instead, use
`cobblestone.navigate.cobblestone.death`.

## `metrics`

| Setting | Default | |
| --- | --- | --- |
| `metrics.enabled` | `true` | restart |

Anonymous, aggregate usage counts via [bStats](https://bstats.org). You can also opt out globally
in `plugins/bStats/config.yml`.

## `navigators.trail`

The built-in `trail` navigator: a flowing ribbon of particles along the path.

| Setting | Default | |
| --- | --- | --- |
| `navigators.trail.buffer_cells` | `256` | live |
| `navigators.trail.particles` | `[SCRAPE, WAX_OFF]` | live |
| `navigators.trail.highlight_particles` | `[END_ROD]` | live |
| `navigators.trail.colors` | `[55FFFF, FFAA00, FFFFFF]` | live |
| `navigators.trail.density` | `0.2` | live |

- **`buffer_cells`** — how much of the path ahead of the player is rendered at a time.
- **`particles`** — particle types the trail is drawn with; one is picked at random per particle.
  `DUST` is coloured by the palette below, other types are drawn as-is. Particles that need extra
  data beyond a colour are not supported.
- **`highlight_particles`** — drawn at highlighted points along the trail.
- **`colors`** — dust colours as `RRGGBB`; one is picked at random per `DUST` particle, with no
  blending.
- **`density`** — average particles per cell per tick, scattered with a Gaussian falloff for column
  width. Fractional values are the way to thin the trail without making it stutter: `0.7` means each
  block has a 70% chance of one particle per tick.

Names come from the server's own particle registry, so use the spellings your platform uses.

---

## Recipes

**"Searches keep failing on my 30k-block map."**
Raise `search.algorithm.max_cells_visited` (say 500 000) and `max_wall_clock_seconds`, and make
sure `search.chunks.policy` is at least `allow_load`. Watch heap use afterwards.

**"The server is chugging and I think it's Cobblestone."**
Run `/cobblestone loglevel debug` and look at the per-search timings. Then, in order: raise
`trips.live_interval_ticks`, keep `search.max_concurrent_per_player` at `1`, lower
`search.algorithm.max_cells_visited`, and on Sponge lower `search.chunks.max_load_requests`.

**"I don't want searches touching my world files."**
`search.chunks.policy: loaded_only`. Expect more "no route" answers.

**"The trail is too busy."**
Lower `navigators.trail.density` to about `0.1`, or drop to a single particle type.
