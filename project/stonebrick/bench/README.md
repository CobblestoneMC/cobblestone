# Stonebrick bench

Runs Cobblestone's search over captured terrain and reports what each route cost. Run it from
`project/`:

```bash
./gradlew :stonebrick:stonebrick-bench:run --args="<command> [options]"
```

The terrain has to be captured first: `./gradlew captureCorpus -PacceptMinecraftEula=true` runs the
seeded Paper server and captures what `data/needed-chunks.yml` asks for.

## Commands

| Command | What it does |
| --- | --- |
| `list` | The scenarios and loadouts, and how many runs they make. |
| `accept` | Runs everything and records the results as baselines (`stonebrick/baselines/`). |
| `run` | Runs everything and compares against the baselines; exits non-zero if a gated metric moved. |
| `sweep` | Runs at several heuristic weights and prints nodes expanded against path cost. |
| `check` | Says whether a scenario can be benchmarked: each heuristic's result and Dijkstra's optimum. |
| `path` | Prints the cells of the route each loadout's search found. |
| `verify-regions` | Samples cells along each route's cheapest path and compares the region estimate there with the true cost. With no selection, it runs the region verify suite: the loadouts each scenario lists under `verifyRegions`. |

| Option | Effect |
| --- | --- |
| `--scenario overworld/ocean` | one route (`<world>/<name>`) |
| `--tag surface`, `--tier local` | routes with a tag, or of a tier |
| `--agent flyer` | one loadout |
| `--heuristic zero` | Dijkstra, for the true optimum; or any production heuristic by name |
| `--weight 2.0`, `--weights 1,1.5,2` | heuristic weight(s) |
| `--io sata` | simulated disk: `zero`, `nvme`, `sata`, `spinning`, `busy` |
| `--warm` | (`sweep`) run each route twice, the second over region sections read back from disk, as after a restart |
| `--corpus <dir>`, `--scenarios <file>`, `--baselines <dir>` | read from or write to elsewhere |

`-Dregions.detour=<ratio>` sets the region fill's detour ratio, for measuring it. A long Dijkstra
needs a large heap: `JAVA_OPTS=-Xmx8G`.

## The region verify suite

```bash
./gradlew verifyRegions
```

Checks the region layer's estimate against the true cost on a handful of routes per dimension, in
a minute or two. Each route runs in a JVM of its own, with 1 GB and 3 minutes, as many at once as
fit in half the free memory and half the processors; a route that runs out of either fails the
suite. It needs the corpus captured first, and says so if it is not.

The truth is one Dijkstra per route: every stretch of a cheapest path is itself cheapest, so what
remains from each cell on it is the total less what came before.

## Reading results

`run` compares each result with its baseline:

| Status | Meaning |
| --- | --- |
| `MATCH` | within tolerance |
| `CHANGED` | a gated metric moved; review it, then `accept` if intended |
| `CONFIGURATION` | the baseline measured different settings or a different capture; re-`accept` |
| `DEGENERATE` | the search read terrain the capture lacks, so the result is not meaningful |

Gated: path cost, time and steps, nodes opened and expanded, chunk reads, simulated time. Reported
only: wall time and heap.

`sweep` prints one line per run as it lands, then tables per scenario and a summary:

```
point overworld/ocean@player RUNNING_AVERAGE w=2.00 success cost=195.020 expanded=26461 closest=0 legs=1
```

Every point also prints what it cost: wall time (`ms`), time parked on the modelled disk (`ioMs`),
chunk reads (`reads`, `coldReads` for first reads) and cells opened (`opened`). Under `REGIONS` it
adds how many chunk columns the region layer read (`columns`, `regionReads`) and where it spent its
time and memory: reading chunks and building sections from them (`readMs`, `buildMs`), joining the
sections (`placeMs`, `seamMs`), its own search (`searchMs`, `queries`, `settled`), and its size
(`built` sections, `regions`, `portals`, `graphKB`, `storeKB`).

`closest` is how near the search came, in blocks from the nearest cell it settled to the
destination: 0 when it arrived. When every heuristic fails a route, the one that came closer did
better.

`legs` is how many legs the fine search solved. It is 1 unless the scenario has warps, when Tier 1
chooses between routes and may solve legs it then does not take.

## Adding a scenario

Pick and check it with the [utility plugin](../utility/README.md), copy its entries into
`data/scenarios.yml` and `data/needed-chunks.yml`, and run `captureCorpus`. A scenario's
destination is a point, or an area with `box: { from: {x, y, z}, to: {x, y, z} }`.

`verifyRegions: [player, ...]` puts the scenario in the region verify suite for those loadouts.
List a loadout only if `verify-regions --scenario <id> --agent <loadout>` finishes within 1 GB of
heap and 3 minutes: Dijkstra on a long or open route, and any flyer's, takes far more. Keep the
suite to a few routes per dimension that between them cover its kinds of terrain.

`warps: [{x, y, z}, ...]` gives a scenario teleports usable from anywhere in its world, as
Essentials' `/warp` is, at 3 seconds each. Tier 1 then picks between walking and each warp. Keep
the landings inside the capture.

Loadouts in `data/agents.yml` describe the player: `canFly`, `canGlide`, `hasBoat`, `inBoat`,
`soulSpeed` (the level on the boots), `permissions`, `excludedModes`, and `only` to limit a loadout
to scenarios with one of those tags.

## Adding a heuristic

Add a value to `SearchSettings.Heuristic`, build it in `MinecraftHeuristics.forPlayer`, and add the
matching value to `Scenario.Heuristic`. `sweep` and `check` then run it beside the others.
