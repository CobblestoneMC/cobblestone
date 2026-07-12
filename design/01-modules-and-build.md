# Odyssey — Modules & Build

> **Status: implemented (Phase 0 done), doc not reconciled.** The build skeleton exists and builds
> green under `project/`. Since then the layout was reorganized into nested folders and several
> modules were renamed (`core-api`→`api`, `minecraft`→`minecraft-core`, `minecraft-plugin-api`→
> `plugin-api`, `minecraft-plugin`→`plugin-core`, `folia*`→`paper*`, `sponge-16`→`sponge-16-core`).
> **The authoritative module list, names, and dependency edges now live in
> `project/settings.gradle.kts` and each module's `build.gradle.kts`** — trust those over the names
> written below in this document.

## Subproject graph
`X → Y` reads "**X depends on Y**". All modules are Gradle subprojects of one root build.
Single repo-wide version to start (see Versioning).

```
core-api                       (root; no deps)
  ▲
  ├── core ──────────────► core-api
  │     ▲
  │     ├── core-test ───► core
  │     │     ▲
  │     │     └── playground ───► core-test
  │     │
  │     └── minecraft ───► core, minecraft-api
  │            ▲
  └── minecraft-api ────► core-api
         ▲
         ├── folia-api ──────► minecraft-api
         │      ▲
         │      └── folia ───► minecraft, folia-api
         │             ▲
         ├── sponge-16-api ──► minecraft-api
         │      ▲
         │      └── sponge-16 ─► minecraft, sponge-16-api
         │
         └── minecraft-plugin-api ─► minecraft-api  (+ Adventure, provided)
                ▲
                └── minecraft-plugin ─► minecraft, minecraft-plugin-api  (+ config lib, data layer)
                       ▲
   folia-plugin ─► folia, minecraft-plugin
   sponge-16-plugin ─► sponge-16, minecraft-plugin
   integration plugins (OdysseyCitizens, …) ─► a platform plugin/api + the 3rd-party plugin API
```
No module depends on anything "above" it here — in particular **`core-api` depends on nothing**,
and `minecraft-api` depends only on `core-api`. Adventure enters only at `minecraft-plugin-api`
and downstream.

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
- **folia-plugin** → folia, minecraft-plugin.  **sponge-16-plugin** → sponge-16, minecraft-plugin.
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
| sponge-16-plugin | `net.whimxiqal.odyssey.sponge16.plugin` |
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
> **Two developer entry points** (see `05`/`06`): the **platform** layer (`*-api` + `*-core`) is a
> navigation *library* you instantiate; the **plugin** layer (`*-plugin-api`) is the surface for
> extending Odyssey's running plugin. Both are published; the shipped `*-plugin` uberjars are not.

**Supported / documented** artifacts (thin jars) for downstream developers:
- `core-api`, `core` — for developers using Odyssey with something *other* than Minecraft.
- `paper-api`, `sponge-16-api` — the native platform navigation façade (`PlatformOdysseyApi<P,L>`
  bindings), for developers building their own Odyssey-based plugin.
- `paper-core`, `sponge-16-core` — the platform **impl** (`PaperOdysseyApiImpl`, wrappers, adapters),
  for developers who want to shade the algorithm implementation into their own platform project.
- `paper-plugin-api`, `sponge-16-plugin-api` — the plugin-extension surface
  (`PlatformOdysseyPluginApi<P,L>` bindings: register destinations/navigators, reach navigation via
  `.platform()`), for plugins integrating with the *installed* Odyssey plugin.

**Published but internal/unsupported:** `minecraft-api`, `minecraft-core`, `minecraft-plugin-api`. We
don't *want* to advertise these as stable, **but Maven publishing must be dependency-closed** — the
supported `paper-*`/`sponge-*` artifacts compile against them (e.g. `paper-plugin-api` extends
`PlatformOdysseyPluginApi` from `minecraft-plugin-api`), so consumers must resolve them transitively.
Published with a docs note that their surface may change without a minor bump.

**Not published:** `minecraft-plugin` (internal glue), the `*-plugin` shaded artifacts (released as
*plugin jars*, not libraries), `core-test`, `playground`.

## Versioning
Single repo-wide semantic version to start. Bump **minor** when any published API module changes
its surface, **patch** otherwise. Only publish a module version when that module actually changed.
Revisit per-module versioning only if release cadence diverges painfully.

## Licensing & lint
- **MIT** license; a license header is applied to every source file by a Gradle plugin.
- **checkstyle** governs style (seed from the existing `odyssey/checkstyle.xml`; adjust as the code
  takes shape). A key custom rule: forbid unchecked/needless casts to enforce the no-downcast pillar.
- `CONTRIBUTING.md` and `README.md` at the repo root (tracked in `10-metrics-and-ops.md`).

## External references (in `local/resources/`)
Full clones of Paper, SpongeAPI, Folia, and the integration-target plugins live under `../resources/`
for API reference while implementing.
