---
title: Benchmarking
description: Measuring the search against real Minecraft terrain with Stonebrick.
---

# Benchmarking

Stonebrick runs the search against terrain captured from a real Minecraft world, without a server,
and reports what each route cost. Runs are deterministic, so a number that moves means the
algorithm changed. The design is in
[`designs/stonebrick.md`](https://github.com/CobblestoneMC/cobblestone/blob/main/designs/stonebrick.md).

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
server. Gradle uses the JDKs already installed on your machine and never downloads one.

Generate the terrain once:

```bash
cd project
./gradlew captureCorpus -PacceptMinecraftEula=true
```

This downloads the Paper build pinned in `corpus.yml`, verifies its checksum, generates the seeded
world, and captures what each scenario needs. It only captures what is missing, so it is safe to
re-run. The flag records that you accept the
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

A first `run` has no baselines, and prints each result's outcome, expansions and path cost. That is
the current state of the search.

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

1. Add an entry to `scenarios.yml`, by hand or with `/copier mark <name> origin|dest` while standing
   in the seeded world.
2. `run --scenario <id>`. If the search reached past the captured terrain, `needed-chunks.yml` grows
   to cover it.
3. Re-run `captureCorpus`, then `run` again, until nothing is missing.
4. Commit `scenarios.yml` and `needed-chunks.yml`.

## Adding a heuristic

Add a value to `SearchSettings.Heuristic`, build it in `MinecraftHeuristics.forPlayer`, and add the
matching value to the bench's `Scenario.Heuristic`. Paper, Sponge and the bench all build heuristics
there. Compare with `sweep --heuristic <name>`: a curve across weights, not a single point.
