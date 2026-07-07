# Odyssey — `core-test` & `playground`

## `core-test` (`net.whimxiqal.odyssey.core.test`)
A test-support engine that stands in for Minecraft so the algorithms can be exercised in pure Java,
plus the actual algorithm tests. Depends on `core` (and hence `core-api`).

### Test modes
Simple `Mode<TestAgent, TestStepType, Void>` implementations (instruction type `I = Void`) over a
synthetic world:
```java
enum TestStepType { FLY, WALK, DIG }
```
- **`FlyMode`** — moves to any neighbor (cardinal, 2D-diagonal, 3D-diagonal) that is `AIR`. Uniform
  per-cell cost.
- **`WalkMode`** — moves to a neighbor that is `AIR` with a `SOLID` block directly below; diagonals
  require both orthogonal corners to be `AIR` (no corner-cut). Higher cost than fly (or vice-versa,
  configurable per test).
- **`DigMode`** — moves into a `SOLID` neighbor (with valid footing/clearance after), at a high
  fixed cost — the analogue of `MINE`, used to test wall-tunnelling vs going-around.
All test modes return **immediate** `FutureOr`s (no IO), which also exercises the search's cache-hit
fast path.

### Block & world model
- Block types (v1): `SOLID`, `AIR`. Chunks are made of these.
- **World JSON format:** a list of prismatic regions, each two 3D corners + a block type; a default
  fill (AIR) and later-region-wins overlap rule. Multiple domains per file (each with min/max Y and a
  string key mapped through the `DomainRegistry`). `Transition`s may be declared between positions
  (origin as a single-cell region for tests).
```json
{
  "domains": [
    { "key": "test:alpha", "minY": 0, "maxY": 64, "fill": "AIR",
      "regions": [ { "from": [0,0,0], "to": [10,0,10], "type": "SOLID" } ] }
  ],
  "transitions": [ { "from": {"domain":"test:alpha","cell":[5,1,5]},
                     "to":   {"domain":"test:beta","cell":[0,1,0]}, "cost": 2.0 } ]
}
```
- **`WorldManager`** loads these into `TestWorld`s exposing `getBlockType(Cell)` and metadata; also
  serves as the `PlatformApi`-equivalent block source for tests and the playground.

### Test matrix (JUnit)
Assertions use the **`AdmissibleHeuristic`** so exact optimal paths can be checked; a few tests also
run `RunningAverageHeuristic` asserting "valid & within X% of optimal."
- **Graph unit tests** (Tier 1 in isolation): hand-built node/edge fixtures; verify Dijkstra picks
  the cheapest alternating path; verify `+∞` edges are avoided; verify recalc re-plans when an edge
  cost is raised.
- **Straight shot:** all-air world, `FLY` only → straight diagonal line, expected exact cost.
- **Wall avoidance:** wall between origin/destination, `WALK` only (no `DIG`) → path routes around.
- **Wall tunnelling:** same wall, `WALK`+`DIG`, dig cost < detour cost → path digs through; make the
  wall thick enough and it should route around instead (tests the running-average heuristic's
  wall-thickness behavior).
- **Multi-domain:** destination only reachable via a `Transition` to another domain → the flat `Path`
  contains a transition `Step` (domain changes across it); verify order and total cost.
- **Multi-endpoint destination:** a `Destination` with several `DomainRegion`s → cheapest reached
  (super-sink).
- **Vehicle/state:** a state-transforming `Transition` (mount) yields a lower-cost onward path;
  verify the `(cell, state)` visited keying keeps the vehicle branch alive where a cell-collapse
  would drop it.
- **Failure cases:** destination walled off with no `DIG` → `DESTINATION_UNREACHABLE`; no transition
  to the destination's domain → `NO_ROUTE`; over `maxCellsVisited`/`maxWallClock` → the right failure.
- **Cancellation:** cancel mid-search → future completes `CANCELLED`, no further work scheduled.

### Test scheduler
A deterministic single-thread `Scheduler` (runs tasks inline / on a controllable executor) so tests
are reproducible and can also simulate parked/immediate futures.

## `playground` (`net.whimxiqal.odyssey.playground`)
A 3D visualizer for developing/debugging the algorithms. Depends on `core-test` (+ `core`,
`core-api`) and a 3D library (**JavaFX 3D / OpenJFX** — add the OpenJFX dependency + platform
natives; never shipped, never published).

Features:
- Load a `core-test` world JSON; render `SOLID` blocks as cubes, with a toggle to reduce solid-block
  opacity (so paths that pass *through* solids — `DIG`/`MINE` — are visible).
- WASD + mouse camera movement around the space.
- Run a navigation and draw the resulting `Path` as red line segments through the cells.
- Step/animate the **algorithm itself**: visualize the A* `visited` set growing and the `candidate`
  frontier, and Tier-1 edge selection/recalc, with playback controls.
- Multiple domains rendered side-by-side with an air gap between them; `Transition`s drawn as ring/
  cylinder shapes to illustrate warping.
