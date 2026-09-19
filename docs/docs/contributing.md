---
title: Contributing
description: Building Cobblestone and submitting changes.
---

# Contributing

Cobblestone is MIT licensed. The full guide is
[CONTRIBUTING.md](https://github.com/CobblestoneMC/cobblestone/blob/main/CONTRIBUTING.md).

## Building

```bash
git clone https://github.com/CobblestoneMC/cobblestone.git
cd cobblestone/project
./gradlew build
```

Requires JDK 21 and JDK 25. Core and Sponge modules target 21; Paper modules target 25.

Before opening a pull request:

```bash
./gradlew spotlessApply     # format
./gradlew build             # compile + test
```

## Layout

| Path | What |
| --- | --- |
| `project/api`, `project/core` | Navigation API and A* engine |
| `project/core-test` | Tests against synthetic worlds |
| `project/minecraft/plugin` | Platform-independent plugin code |
| `project/minecraft/platform/paper`, `…/sponge-12` | Platform implementations |
| `project/minecraft/integrations` | Third-party integrations |
| `project/examples/paper-warps` | Example integration |
| `docs/` | This site |

## Bug reports

Open an [issue](https://github.com/CobblestoneMC/cobblestone/issues) with the server platform and
version, the Cobblestone version, and relevant console output. For routing issues, include the
search log line printed at `/cobblestone loglevel debug`.

## Documentation

```bash
cd docs
pip install -r requirements.txt
mkdocs serve
```

Pushes to `main` deploy the site. Changes to commands, permissions, configuration, or the API should
update the corresponding page in the same pull request.
