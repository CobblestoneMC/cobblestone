---
title: Server admins
description: Install Cobblestone and review its features, permissions, and resource usage.
---

<div class="cs-illustration">
<img src="../assets/illustrations/admin-console.svg" alt="">
</div>

# Server admins

Cobblestone adds two commands, a set of permission nodes, and one configuration file.

## Installation

=== "Paper"

    1. Download the Paper build from [Modrinth](https://modrinth.com/plugin/cobblestoneplugin).
    2. Place it in `plugins/`.
    3. Start the server.

    Requires Paper 26.1+ on Java 25+. Folia is supported.

    ```
    plugins/cobblestone/
      config.yml          # generated on first run
      cobblestone.mv.db   # H2 database
    ```

=== "Sponge"

    1. Download the Sponge build from [Modrinth](https://modrinth.com/plugin/cobblestoneplugin).
    2. Place it in `mods/`.
    3. Start the server.

    Requires Sponge 1.21.1 on Java 21+, or Sponge 26.1+ on Java 25+.

    ```
    config/cobblestone/
      config.yml          # generated on first run
      cobblestone.mv.db   # H2 database
    ```

No further setup is required. For large servers, review [Configuration](config.md).

## Features

### Core

- Personal and server-wide saved locations.
- Navigation to a player's death location, to other players, and to other worlds.
- Nether and End portal links, learned by observing players use them.

```
/stone location set home
/stone location set spawn -global
/nav death
```

### Integrations

Companion plugins add destinations, register teleport commands as route steps, and apply
third-party build protection to routes.

| Integration | Adds |
| --- | --- |
| [Towny](integrations.md#towny) | Towns, outposts, and plots; `/town spawn` and `/nation spawn` as route steps; build protection. |
| [EssentialsX](integrations.md#essentialsx) | Homes and spawn; `/home` and `/spawn` as route steps. |
| [Citizens](integrations.md#citizens) | NPCs. |
| [BetonQuest](integrations.md#betonquest), [BeautyQuests](integrations.md#beautyquests), [Quests](integrations.md#quests-pikamug) | Quest objectives, with optional automatic trips. |
| [Typewriter](integrations.md#typewriter) | A *Navigate Player* action. |

## Permissions

Player nodes default to allow; admin nodes default to op.

| Node | Default | Grants |
| --- | --- | --- |
| `cobblestone.navigate` | allow | `/navigate`, `/cobblestone trips`, `/cobblestone cancel` |
| `cobblestone.location` | allow | Personal locations |
| `cobblestone.navigator` | allow | Non-default navigators |
| `cobblestone.admin.location.global` | op | Global locations |
| `cobblestone.admin.reload` | op | `/cobblestone reload` |
| `cobblestone.admin.portals` | op | `/cobblestone portals clear` |
| `cobblestone.admin.loglevel` | op | `/cobblestone loglevel` |

Each destination also has its own node, derived from its address. For example, to prevent a group
from navigating to other players:

```
/lp group default permission set cobblestone.navigate.cobblestone.player.* false
```

See [Permissions](permissions.md).

## Resource usage

### Threading

Pathfinding runs asynchronously. World reads and calls into other plugins, such as Towny build
checks, run on the main thread. See `search.max_concurrent_per_player` and `search.algorithm.*` in
[Configuration](config.md#search).

### Chunk loading

By default, searches may load chunks that have already been generated. Terrain generation is
disabled unless `search.chunks.policy` is set to `allow_load_and_generate`, which permanently
enlarges world files. See [Configuration](config.md#searchchunks).

### Storage

Saved locations, the most recent death location of each player, and learned portal links are
stored in an embedded H2 database. Learned portal links can be cleared with
`/cobblestone portals clear`.
