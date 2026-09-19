---
title: Configuration
description: Reference for config.yml.
---

# Configuration

`config.yml` is generated on first run, with a comment above each setting.

=== "Paper"

    `plugins/cobblestone/config.yml`

=== "Sponge"

    `config/cobblestone/config.yml`

`/cobblestone reload` reloads the file. Settings marked **live** apply immediately; settings marked
**restart** apply on the next server start.

Each platform generates its own settings. Unrecognized settings are reported on startup and
ignored.

## `locale`

| Setting | Default | |
| --- | --- | --- |
| `locale.default` | `en` | restart |

Language for console and system messages, as a BCP 47 tag (`en`, `en-US`, `fr`). Player messages
use the client's language when a translation is available.

## `messages`

| Setting | Default | |
| --- | --- | --- |
| `messages.show_prefix` | `true` | live |

Prepends the `[✦]` prefix to player messages.

## `data`

| Setting | Default | Values | |
| --- | --- | --- | --- |
| `data.backend` | `H2` | `H2` | restart |
| `data.file` | `cobblestone` | any name | restart |

Storage for saved locations, death locations, and portal links. `data.file` is the database file
name, without extension, in the plugin's data folder.

## `trips`

| Setting | Default | |
| --- | --- | --- |
| `trips.max_active_per_player` | `3` | live |
| `trips.live_interval_ticks` | `100` | live |
| `trips.recalculate_distance` | `32` | live |

- **`max_active_per_player`**: Maximum concurrent trips per player.
- **`live_interval_ticks`**: Interval between re-searches for live trips. Live re-searches are the
  main recurring cost; increase this before reducing search limits.
- **`recalculate_distance`**: Distance in blocks a player may stray before the route is
  recomputed. Below this distance, a guide path back to the trail is drawn instead. `0` disables
  recomputation.

## `search`

| Setting | Default | |
| --- | --- | --- |
| `search.max_concurrent_per_player` | `1` | restart |

Maximum concurrent searches per player, including live re-searches. Live re-searches yield to this
limit; a manual `/navigate` always runs.

### `search.algorithm`

| Setting | Default | |
| --- | --- | --- |
| `search.algorithm.max_cells_visited` | `200000` | live |
| `search.algorithm.max_wall_clock_seconds` | `60` | live |
| `search.algorithm.heuristic_weight` | `1.5` | live |
| `search.algorithm.tier1_unsolved_pessimism` | `1.5` | live |
| `search.algorithm.running_average_width` | `5` | live |

- **`max_cells_visited`**: Cell limit per search. Memory use is a few hundred bytes per cell, so the
  default peaks in the tens of megabytes. Increase for longer routes; decrease on small heaps.
- **`max_wall_clock_seconds`**: Time limit per search.
- **`heuristic_weight`**: A* heuristic weight. `1.0` yields optimal routes; higher values search
  faster with routes at most this factor longer than optimal.
- **`tier1_unsolved_pessimism`**: Cost multiplier for route legs that have not yet been solved.
  `1.0` disables it.
- **`running_average_width`**: Window size of the running-average heuristic.

### `search.chunks`

| Setting | Default | Values | |
| --- | --- | --- | --- |
| `search.chunks.policy` | `allow_load` | `loaded_only`, `allow_load`, `allow_load_and_generate` | restart |

- **`loaded_only`**: Read only chunks already in memory. Lowest cost; searches frequently fail
  where terrain is unloaded.
- **`allow_load`**: Also read generated chunks from disk. Recommended.
- **`allow_load_and_generate`**: Also generate new terrain. World files grow permanently.

=== "Paper"

    Paper can read an unloaded chunk without loading it, so `allow_load` is inexpensive.

=== "Sponge"

    Sponge must load a chunk to read it. Cobblestone acquires a chunk ticket, copies the chunk, and
    releases the ticket immediately.

    | Setting | Default | |
    | --- | --- | --- |
    | `search.chunks.max_load_requests` | `256` | live |

    Maximum chunk tickets held at once. Values below about 64 disable read-ahead and slow searches.

### Tuning

| Symptom | Adjustment |
| --- | --- |
| Long routes fail with "ran out of resources" or "took too long" | Increase `max_cells_visited` and `max_wall_clock_seconds`. Ensure `search.chunks.policy` is at least `allow_load`. |
| High CPU usage | Increase `trips.live_interval_ticks`, keep `search.max_concurrent_per_player` at `1`, and decrease `max_cells_visited`. On Sponge, decrease `search.chunks.max_load_requests`. |
| High memory usage | Decrease `max_cells_visited`. |
| Searches must not load chunks | Set `search.chunks.policy` to `loaded_only`. |

Use `/cobblestone loglevel debug` to log the timing of each search.

## `portals`

Portal destinations are not exposed by the server API, so Cobblestone learns portal links by
observing players.

| Setting | Default | |
| --- | --- | --- |
| `portals.discovery` | `true` | live |
| `portals.cost_seconds` | `5.0` | live |
| `portals.normalize_entry` | `true` | live |
| `portals.normalize_exit` | `true` | live |

- **`discovery`**: Learn new portal links. Existing links remain in use when disabled.
- **`cost_seconds`**: Assumed cost of a portal traversal.
- **`normalize_entry`**: Treat any entry into a nether portal as entering at its center, so each
  portal has a single destination. Required for reliable portal routing.
- **`normalize_exit`**: Place the player at the center of the destination portal, at ground level.

Clear learned links with `/cobblestone portals clear`.

## `deaths`

| Setting | Default | |
| --- | --- | --- |
| `deaths.track` | `true` | live |

Records each player's most recent death location and offers the `death` destination. When
disabled, recording stops and the destination is hidden; stored data is retained. To restrict it by
group instead, use `cobblestone.navigate.cobblestone.death`.

## `metrics`

| Setting | Default | |
| --- | --- | --- |
| `metrics.enabled` | `true` | restart |

Anonymous usage data via [bStats](https://bstats.org). bStats can also be disabled globally in its
own configuration.

## `navigators.trail`

| Setting | Default | |
| --- | --- | --- |
| `navigators.trail.buffer_cells` | `256` | live |
| `navigators.trail.particles` | `[SCRAPE, WAX_OFF]` | live |
| `navigators.trail.highlight_particles` | `[END_ROD]` | live |
| `navigators.trail.colors` | `[55FFFF, FFAA00, FFFFFF]` | live |
| `navigators.trail.density` | `0.2` | live |

- **`buffer_cells`**: Length of the rendered path segment ahead of the player.
- **`particles`**: Particle types, chosen at random per particle. `DUST` uses `colors`; other types
  render as-is. Particles requiring data other than a color are not supported.
- **`highlight_particles`**: Particles at highlighted points, such as action steps.
- **`colors`**: `DUST` colors as `RRGGBB`, chosen at random per particle.
- **`density`**: Mean particles per cell per tick. Fractional values are probabilities: `0.7` means
  a 70% chance of one particle per cell per tick.

Particle names follow the server's particle registry.
