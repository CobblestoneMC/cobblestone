# Odyssey — Modules & Build

## Subproject graph
Arrows = "depends on". All modules are Gradle subprojects of one root build. Single repo-wide
version to start (see Versioning).

```
core-api  ─────────────┐
   ▲                   │
   │                   ▼
  core            minecraft-api ──► (Adventure NOT here; see plugin-api)
   ▲                   ▲
   │                   │
core-test          minecraft ──────────────────────────┐
   ▲                   ▲                                │
   │            ┌──────┴───────┐                        │
playground   folia-api      sponge-16-api               │
                 ▲              ▲                        │
                 │              │                        │
               folia        sponge-16                    │
                 ▲              ▲                         │
                 │              │              minecraft-plugin-api (+ Adventure)
                 │              │                         ▲
                 │              │              minecraft-plugin (+ config lib, data layer)
                 │              │                    ▲          ▲
                 └────────┐     └───────┐            │          │
                   folia-plugin     sponge-plugin ───┘          │
                        ▲                 ▲                      │
                        └── integration plugins (OdysseyCitizens, …) ──┘
```

Textual dependency list (authoritative):
- **core-api** → (none). Pure Java, no third-party deps.
- **core** → core-api.
- **core-test** → core (test-support + a tiny mode/world engine; its own tests live here).
- **playground** → core-test (+ JavaFX/OpenJFX). Never published, never shipped.
- **minecraft-api** → core-api.
- **minecraft** → core, minecraft-api.
- **folia-api** → minecraft-api.  **sponge-16-api** → minecraft-api.
- **folia** → minecraft, folia-api.  **sponge-16** → minecraft, sponge-16-api.
- **minecraft-plugin-api** → minecraft-api (+ Kyori Adventure, `compileOnly`/provided).
- **minecraft-plugin** → minecraft, minecraft-plugin-api (+ config lib + data-layer drivers).
- **folia-plugin** → folia, minecraft-plugin.  **sponge-plugin** → sponge-16, minecraft-plugin.
- **integration plugins** → the relevant platform plugin/api + the third-party plugin API.

The graph is acyclic. `core-api` is the universal root; `minecraft-api` is the Minecraft root;
Adventure is confined to the plugin-api layer and downstream (never in core or the movement model).

## Package layout (unique subpackage per module)
Root `net.whimxiqal.odyssey`. Each module owns a distinct subpackage so shaded jars merge without
collision:

| Module | Package |
|--------|---------|
| core-api | `net.whimxiqal.odyssey.api` |
| core | `net.whimxiqal.odyssey.core` |
| core-test | `net.whimxiqal.odyssey.core.test` |
| playground | `net.whimxiqal.odyssey.playground` |
| minecraft-api | `net.whimxiqal.odyssey.minecraft.api` |
| minecraft | `net.whimxiqal.odyssey.minecraft` |
| folia-api | `net.whimxiqal.odyssey.folia.api` |
| folia | `net.whimxiqal.odyssey.folia` |
| sponge-16-api | `net.whimxiqal.odyssey.sponge16.api` |
| sponge-16 | `net.whimxiqal.odyssey.sponge16` |
| minecraft-plugin-api | `net.whimxiqal.odyssey.plugin.api` |
| minecraft-plugin | `net.whimxiqal.odyssey.plugin` |
| folia-plugin | `net.whimxiqal.odyssey.folia.plugin` |
| sponge-plugin | `net.whimxiqal.odyssey.sponge16.plugin` |
| integrations | `net.whimxiqal.odyssey.integration.<name>` |

## Gradle structure
- **Kotlin DSL** (`build.gradle.kts`, `settings.gradle.kts`). Root `settings.gradle.kts` includes
  every subproject; `plugins/` and `integrations/` are directory groupings.
- A `buildSrc/` (or version catalog `gradle/libs.versions.toml`) centralizes dependency versions
  and shared conventions (Java 21 toolchain, checkstyle, license header, `-Xlint`).
- **Shadowing:** use the **GradleUp shadow** plugin on the *plugin* and *integration* subprojects
  only. Library modules publish thin jars.

### Provided vs shaded vs relocated (per platform)
| Dependency | Paper/Folia plugin | Sponge plugin | Notes |
|-----------|--------------------|---------------|-------|
| Kyori Adventure | **provided** (bundled by Paper) | **provided** (bundled by Sponge) | never shade — would conflict |
| Platform API (paper-api / SpongeAPI) | provided | provided | compile-only |
| bStats | **shade + relocate** | shade + relocate | relocate to `net.whimxiqal.odyssey.libs.bstats` |
| config lib (YAML) | shade + relocate | shade + relocate | relocate under `…odyssey.libs.<lib>` |
| JDBC drivers (SQLite/H2/MySQL/PG) | shade + relocate, load on demand | same | only the configured backend need be present at runtime |
| MongoDB driver | shade + relocate | same | optional; large — see data layer |
| Prometheus client | shade + relocate | same | admin provides the HTTP endpoint |

Our own `net.whimxiqal.odyssey.*` packages are **never relocated**; the unique-subpackage rule
already prevents collisions when everything is merged into one plugin jar.

## Publishing (Maven)
Published (thin) for downstream developers:
`core-api`, `core`, `minecraft-api`, `minecraft`, `folia-api`, `sponge-16-api`, `folia`,
`sponge-16`, `minecraft-plugin-api`.

Not published: `minecraft-plugin` (internal glue), the `*-plugin` shaded artifacts (released as
plugin jars, not libraries), `core-test`, `playground`.

## Versioning
Single repo-wide semantic version to start. Bump **minor** when any published API module changes
its surface, **patch** otherwise. Only publish a module version when that module actually changed.
Revisit per-module versioning only if release cadence diverges painfully.

## Licensing & lint
- **MIT** license; a license header is applied to every source file by a Gradle plugin.
- **checkstyle** governs style (seed from the existing `odyssey/checkstyle.xml`; adjust as the code
  takes shape). A key custom rule: forbid unchecked/needless casts to enforce the no-downcast pillar.
- `CONTRIBUTING.md` and `README.md` at the repo root (tracked in `10-metrics-and-ops.md`).

## External references (in `resources/`)
Full clones of Paper, SpongeAPI, Folia, and the integration-target plugins live under `resources/`
for API reference while implementing.
