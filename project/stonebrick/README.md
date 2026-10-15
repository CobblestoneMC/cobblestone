# Stonebrick

Stonebrick benchmarks Cobblestone's search against terrain captured from a real Minecraft world,
with no server running. Runs are deterministic: the same code on the same capture expands the same
nodes and finds the same path, so a number that moves means the algorithm changed.

| Module | What it is |
| --- | --- |
| [`format`](format) | The `.sbc` capture format: chunk columns and the block traits that describe them. |
| [`platform`](platform) | Cobblestone's platform API over a capture, with a deterministic scheduler and a simulated disk. |
| [`bench`](bench) | Runs scenarios, compares them against baselines, sweeps heuristic weights. See its [README](bench/README.md). |
| [`utility`](utility) | A Paper plugin that captures terrain and picks and checks scenarios. See its [README](utility/README.md). |

## How it fits together

1. `data/corpus.yml` names a world seed and a pinned Paper build. `./gradlew captureCorpus` runs that
   server with the utility plugin and captures what each scenario needs. Captures are not
   committed: they are regenerated from the seed.
2. `data/scenarios.yml` lists routes; `data/agents.yml` lists loadouts (walker, flyer, boat, no
   pickaxe). Every scenario runs under every loadout its tags allow.
3. The bench reads the captures through `platform` and runs the real search on them.

## Verifying a performance claim

Measure before and after on the same captures:

```bash
git checkout main
./gradlew :stonebrick:stonebrick-bench:run --args="accept"   # record baselines
git checkout my-change
./gradlew :stonebrick:stonebrick-bench:run --args="run"      # compare; fails if a gated metric moved
```

For a claim about a heuristic, `sweep` gives the trade-off between nodes expanded and path cost
across weights, and `--heuristic zero` runs Dijkstra for the true optimum. A claim about disk cost
should be measured under each `--io` model. Wall time and heap are reported but never gated: they
depend on the machine.

User-facing documentation: [Benchmarking](../../docs/docs/benchmarking.md).
