---
title: Benchmarking
description: Measuring the search against real Minecraft terrain with Stonebrick.
---

# Benchmarking

Stonebrick runs the search against terrain captured from a real Minecraft world, without a server,
and reports what each route cost. Runs are deterministic, so a number that moves means the
algorithm changed. How it is built is in
[`project/stonebrick/README.md`](https://github.com/CobblestoneMC/cobblestone/blob/main/project/stonebrick/README.md).

| File in `project/stonebrick/data/` | What | Committed |
| --- | --- | --- |
| `scenarios.yml` | routes: a start and a destination | yes |
| `agents.yml` | loadouts: what the player can do (walk, fly, boat, no pickaxe) | yes |
| `corpus.yml` | the world seed and the pinned Paper build | yes |
| `needed-chunks.yml` | how much terrain each route needs | yes |
| `captures/` | the captured terrain | no, generate it |

Every scenario runs under every loadout that applies to it.

## Setup

You need the JDKs from [Contributing](contributing.md), including **JDK 25**, which runs the Paper
server. Gradle finds the JDKs installed on your machine.

Generate the terrain once:

```bash
cd project
./gradlew captureCorpus -PacceptMinecraftEula=true
```

This downloads the Paper build pinned in `corpus.yml`, checks it against the checksum pinned
there, generates the seeded world, and captures what each scenario needs. It only captures what is
missing, so it is safe to re-run. The flag records that you accept the
[Minecraft EULA](https://www.minecraft.net/en-us/eula); nothing starts without it.

Captures of the same seed differ in small decorations, so your numbers will not match a teammate's
exactly. Captures and baselines therefore stay on your machine.

## Running

```bash
./gradlew :stonebrick:stonebrick-bench:run --args="<command> [options]"
```

| Command | What it does |
| --- | --- |
| `list` | Show the scenarios and loadouts |
| `run` | Run everything and compare against your baselines; fails if a gated metric moved |
| `accept` | Run everything and record the results as your baselines |
| `sweep` | Run at several heuristic weights and print nodes against path cost |
| `check` | Say whether a scenario can be benchmarked: each heuristic's result, and Dijkstra's optimum |
| `path` | Print the cells of the route each loadout's search found |
| `verify-regions` | Compare the region estimate with the true cost along each route |

A first `run` has no baselines, and prints each result's outcome, expansions and path cost. That is
the current state of the search.

To check the region estimate itself, run its suite. It takes a minute or two and uses at most half
your memory and processors:

```bash
./gradlew verifyRegions
```

To measure a change, record a baseline before it and compare after:

```bash
git checkout main
./gradlew :stonebrick:stonebrick-bench:run --args="accept"
git checkout my-change
./gradlew :stonebrick:stonebrick-bench:run --args="run"
```

| Status | Meaning |
| --- | --- |
| `MATCH` | within tolerance |
| `CHANGED` | a gated metric moved; review it, then `accept` if intended |
| `CONFIGURATION` | the baseline measured different settings or a different capture; re-`accept` |
| `DEGENERATE` | the search left the captured terrain; see below |

Gated metrics are path cost, time and steps, nodes opened and expanded, chunk reads, and simulated
time. Wall time and heap are reported but never fail a run.

| Option | Effect |
| --- | --- |
| `--scenario overworld/ocean` | one route |
| `--tag surface` | routes with a tag |
| `--agent flyer` | one loadout |
| `--heuristic zero` | Dijkstra: slow, but finds the optimal path |
| `--weight 2.0`, `--weights 1,1.5,2` | heuristic weight(s) |
| `--io spinning` | simulated disk: `zero`, `nvme`, `sata`, `spinning`, `busy` |
| `--baselines <dir>` | keep baselines elsewhere |

## Adding a scenario

The utility plugin (`project/stonebrick/utility`, `/stonebrick` or `/sb`) picks and checks
scenarios on a server running the seeded world.

1. Find the ends. `/sb probe <x> <z>` says where a body stands in a column and which structures are
   there; `/sb pieces <x> <z>` lists a structure's pieces with a place to stand in each; `/sb find
   <block> <x> <z>` finds blocks such as `_bed` or `bookshelf`; `/sb span <biome> <x> <y> <z>`
   finds a straight line that stays inside a biome.
2. Mark them: `/sb mark <name> origin` and `/sb mark <name> dest` where you stand, or give
   coordinates (`surface` for a Y stands on the ground there). The scenario is tagged by where its
   ends are; change that with `/sb tag <name> <tags>`. For a destination that is an area, add
   `box: { from: {...}, to: {...} }` to its `destination` by hand.
3. `/sb check <name>` (add `--generate` on a world that may generate chunks). It captures the
   terrain, widening the capture until every route that arrives stays inside it, runs each
   heuristic under each loadout and Dijkstra, and reports how each did: the cost, how close a
   failed run came, and how far each is from the optimum. A scenario is unusable only when the
   terrain its routes need cannot be captured; a heuristic failing is a result, not a reason.
4. Copy the scenario's entries from the plugin's `out/scenarios.yml` and `out/needed-chunks.yml` into
   `scenarios.yml` and `needed-chunks.yml`, run `captureCorpus`, and commit both files.

## Adding a heuristic

Add a value to `SearchSettings.Heuristic`, build it in `MinecraftHeuristics.forPlayer`, and add the
matching value to the bench's `Scenario.Heuristic`. Paper, Sponge and the bench all build heuristics
there. Compare with `sweep --heuristic <name>`: a curve across weights, not a single point.
