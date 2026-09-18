# Contributing to Cobblestone

Thanks for taking an interest. Bug reports, integrations and documentation fixes are all welcome.

## Before you start

- **Bugs:** open an issue with the [bug report template](.github/ISSUE_TEMPLATE/bug_report.md).
  Server platform and version, Cobblestone version, and the console output around the failure make
  a report actionable.
- **Features:** open an issue first. Cobblestone is opinionated about what belongs in the core
  plugin versus an integration, and it is worth agreeing on that before you write code.
- **Questions about using it:** the documentation at [cobblestonemc.org](https://cobblestonemc.org)
  covers commands, permissions, configuration and the API.

## Building

Everything lives under `project/`, which is the Gradle root.

```bash
cd project
./gradlew build
```

You need **JDK 21 and JDK 25** available: the core and Sponge modules target 21, the Paper modules
target 25, and Gradle toolchains pick between them. CI provisions both.

Useful tasks:

| Task | What it does |
| --- | --- |
| `./gradlew build` | Compile, test, and assemble every module |
| `./gradlew test` | Tests only |
| `./gradlew spotlessApply` | Format the code |
| `./gradlew spotlessCheck` | Verify formatting — CI runs this and fails on drift |

## Repository layout

```
project/
  api/                     core navigation API (Path, Step, SearchHandle, …)
  core/                    the A* search engine
  core-test/               pure-Java test engine: fake worlds and modes
  playground/              debug visualizer, not shipped
  minecraft/
    api/  core/            Minecraft model shared by both platforms
    plugin/api/  core/     plugin-layer API and shared plugin behavior
    platform/paper/…       Paper: api, core, plugin-api, plugin
    platform/sponge-12/…   Sponge: api, core, plugin-api, plugin
    integrations/          one module per third-party plugin
  examples/paper-warps/    example integration, compiles against the published API only
docs/                      the MkDocs site published to cobblestonemc.org
```

The published artifacts are `org.cobblestonemc:paper-api`, `paper-plugin-api`, `sponge-12-api` and
`sponge-12-plugin-api` (plus the internal modules they depend on).

## Code style

- Formatting is enforced by Spotless (google-java-format). Run `./gradlew spotlessApply` before
  committing; don't hand-format around it.
- Every file carries a license header. Spotless adds it. Modules that link a GPL plugin carry their
  own header and LICENSE — see `minecraft/integrations/betonquest`.
- Public API gets Javadoc, including `@param` and `@return`. Explain *why* in comments; the code
  already says what.
- Prefer platform-neutral code in the shared modules, so Paper and Sponge cannot drift apart. A
  command, a permission or a config key that exists on one platform and not the other needs a
  reason.

## Tests

- Algorithm work belongs in `core-test`, which runs the search against synthetic worlds with no
  server involved. A pathfinding change without a test there is hard to review.
- Platform modules are tested where they hold logic worth testing; server-dependent behavior is
  generally verified by hand on a test server.
- Say in the PR how you verified a change that tests can't cover.

## Adding an integration

Integrations live in `minecraft/integrations/<plugin>` and depend only on the published Cobblestone
API plus the target plugin's API. The pattern is in
[`examples/paper-warps`](project/examples/paper-warps) and documented at
[cobblestonemc.org/developers](https://cobblestonemc.org/developers/):

1. Register a `DestinationService` for places players should be able to navigate to.
2. Register a `SearchModificationService` if the plugin adds travel routes (warps, teleports) or
   restricts where players may build or walk.
3. Register the destinations against the **target plugin** as owner, so the command tree reads
   `/nav town riverwood home` rather than `/nav cobblestonetowny …`.
4. Add the module to `project/settings.gradle.kts`.

Check whether the target plugin's license requires yours to match. If it does, give your module its
own `license-header.txt` and `LICENSE`.

## Documentation

The site is MkDocs Material, under `docs/`:

```bash
cd docs
pip install mkdocs mkdocs-material
mkdocs serve      # http://127.0.0.1:8000
```

Pushing to `main` deploys it. Keep code examples compiling against the current API — the developer
pages are the first thing an integrator reads.

## Pull requests

- Branch off `main`; keep one logical change per PR.
- Make sure `./gradlew build` and `./gradlew spotlessCheck` pass.
- Update the docs in the same PR when you change a command, a permission, a config key, or the API.
- Describe what changed and how you tested it.

## License

Cobblestone is MIT licensed. By contributing, you agree your contribution is released under the
same license as the module it lands in.
