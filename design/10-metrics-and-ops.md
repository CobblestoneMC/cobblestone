# Odyssey — Metrics, Logging & Ops

## Metrics
Two sinks; both read the same internal counters/gauges so they never drift.

### bStats (always on, opt-out via bStats' own config)
Shaded + relocated. Charts:
- current navigations being searched (gauge),
- current active navigators / Trips (players being guided) (gauge),
- blocks traversed with navigators per hour (counter incremented per block a guided player walks,
  reset hourly),
- backend type in use, platform, enabled integrations (categorical),
- anything else useful (avg search duration, search success rate).

### Prometheus (opt-in; admin provides the HTTP endpoint + port in config)
Shaded + relocated client. Exposes everything bStats has, **plus** algorithm internals:
- cells currently in the A* `visited` set (gauge, per active search / aggregate),
- cells currently in the `candidate` frontier (gauge),
- Tier-1 recalcs per search, parks per search, chunk cache hit ratio, chunk fetch latency.
The plugin registers a collector; the admin's endpoint scrapes it. Odyssey does not open a port
unless configured to.

### Internal metrics facade
```java
public interface Metrics {
  Counter counter(String name);
  Gauge gauge(String name);
  Timer timer(String name);
}
```
`core`/`minecraft` publish to this facade; the plugin wires it to bStats + Prometheus. Keeps metric
instrumentation out of the algorithm's platform concerns.

## Logging
- The `OdysseyLogger` seam (`02`) is backed by the platform logger (SLF4J/JUL/Log4j depending on
  platform). Not internationalized.
- Trace-level: candidate pops, parks/resumes, Tier-1 edge selection, recalcs, chunk hits/misses.
- Debug: per-search summary (nodes visited, duration, result). Info: lifecycle. Warn: immutable
  config changed on reload; missing optional dependency. Error: exceptions with stack traces.

## Repo hygiene
- `README.md` (root): what Odyssey is, install, quick start, module map, build instructions.
- `CONTRIBUTING.md`: build (`./gradlew build`), checkstyle, testing, module layout, PR conventions,
  commit/license-header expectations.
- MIT `LICENSE`; per-file license headers applied by Gradle.
- checkstyle config (seeded from `odyssey/checkstyle.xml`) enforced in CI; the no-needless-cast rule
  guards the no-downcast pillar.

## Release / versioning process
- Single repo-wide semver (see `01`). Tag → build all shippable plugin jars + publish thin library
  jars for changed published modules only.
- Minor bump when any published API module's surface changes; patch otherwise.
- CI matrix: build + `core-test` on JDK 21; (later) smoke-test plugin jars against a headless
  Paper/Folia and Sponge server.
