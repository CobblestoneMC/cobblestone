# Stonebrick utility

A Paper plugin for building the benchmark corpus: it captures terrain into the `.sbc` format, and
it is where scenarios are picked and checked. Development tooling; never shipped.

```bash
./gradlew :stonebrick:stonebrick-utility:shadowJar
# project/stonebrick/utility/build/libs/StonebrickUtility-<version>.jar
```

`captureCorpus` installs it on its own server and drives it over the console. To pick scenarios,
drop the jar into a Paper server running the corpus seed (`data/corpus.yml`). Everything it writes
goes to `plugins/StonebrickUtility/out/`, laid out like `data/`.

All commands are under `/stonebrick`, or `/sb`, and need `stonebrick.utility` (op by default).

## Picking a scenario

| Command | What it does |
| --- | --- |
| `probe <x> <z> [world]` | Every level a body can stand at in the column, and the structures there. |
| `pieces <x> <z> [world]` | A structure's pieces, each with a place to stand inside. |
| `find <block> <x> <z> [radius] [world]` | The nearest blocks named like `<block>` (`_bed`, `bookshelf`, `sculk`), each with a place beside it. |
| `span <biome> <x> <y> <z> [length] [world]` | A straight line that stays inside a biome, from the biome noise, without generating chunks. |
| `mark <name> origin\|dest [<x> <y\|surface> <z> [world]]` | Marks an end where you stand, or at coordinates; `surface` stands on the ground there. Says what is at that end, and tags the scenario once both are marked. |
| `tag <name> <tags...>` | Sets the tags, which decide the loadouts (see `data/agents.yml`). |
| `check <name> [--generate]` | Captures the scenario and runs it; see below. |

For a destination that is an area, add `box: { from: {...}, to: {...} }` to its `destination` in
`out/scenarios.yml` by hand.

### `check`

Captures the scenario's terrain, runs every production heuristic under every loadout, and runs
Dijkstra for the true optimum. It reports each heuristic's cost and nodes, how close a failed run
came, and how far each is from the optimum.

A scenario is unusable only when the terrain its routes need cannot be captured. A heuristic
failing is a result, not a reason to drop the route. The capture grows until every run that arrives
stays inside it; a failed run's wandering does not grow it. `--generate` generates chunks that do
not exist yet, which is only right on a corpus world.

When it says *Usable*, copy the scenario's entries from `out/scenarios.yml` and
`out/needed-chunks.yml` into `stonebrick/data/`.

## Capturing terrain directly

| Command | What it does |
| --- | --- |
| `copy <x1> <z1> <x2> <z2> [options]` | Captures a rectangle of chunks (`--blocks` for block coordinates). |
| `copy here <radius> [options]` | Captures around you. |
| `copy capsule <x1> <z1> <x2> <z2> <radius> [options]` | Captures a corridor around a line. |
| `estimate ...` | The same arguments; reports the size without capturing. |
| `traits [capture]` | Rewrites a capture's trait table. |
| `status`, `cancel` | The running capture. |

Options: `--name <capture>`, `--world <key>`, `--surface <below> <above>` \| `--y <min> <max>` \|
`--full`, `--overwrite`, `--force`, `--generate`.

Block traits are computed by the real Paper block implementation (`PaperBlockBridge`), so a capture
describes blocks exactly as the server does.
