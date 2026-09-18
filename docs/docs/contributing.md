---
title: Contributing
description: How to build Cobblestone, where things live, and how to get a change merged.
---

# Contributing

Cobblestone is open source under the MIT license, and bug reports, integrations and documentation
fixes are all welcome.

The authoritative guide lives in the repository:
**[CONTRIBUTING.md](https://github.com/CobblestoneMC/cobblestone/blob/main/CONTRIBUTING.md)**.

## The short version

```bash
git clone https://github.com/CobblestoneMC/cobblestone.git
cd cobblestone/project
./gradlew build
```

You need **JDK 21 and JDK 25** — the core and Sponge modules target 21, the Paper modules target
25, and Gradle toolchains pick between them.

Before opening a pull request:

```bash
./gradlew spotlessApply     # format
./gradlew build             # compile + test
```

## Where things live

| Path | What |
| --- | --- |
| `project/api`, `project/core` | the navigation API and the A* engine |
| `project/core-test` | pure-Java tests against synthetic worlds |
| `project/minecraft/plugin` | plugin behavior shared by both platforms |
| `project/minecraft/platform/paper`, `…/sponge-12` | the two ports |
| `project/minecraft/integrations` | one module per third-party plugin |
| `project/examples/paper-warps` | a complete example integration |
| `docs/` | this site |

## Reporting a bug

Open an [issue](https://github.com/CobblestoneMC/cobblestone/issues) with your server platform and
version, your Cobblestone version, and the console output around the failure. For a routing problem
— "it says there's no route but there obviously is" — run `/cobblestone loglevel debug` first and
include the search line it logs.

## Working on the docs

```bash
cd docs
pip install mkdocs mkdocs-material
mkdocs serve
```

Pushing to `main` deploys the site. If you change a command, a permission, a config key or the API,
update the page that documents it in the same pull request.
