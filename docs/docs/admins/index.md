---
title: For admins
description: Install Cobblestone, learn what it adds to your server, and find the settings that matter.
---

<div class="cs-illustration">
<img src="../assets/illustrations/admin-console.svg" alt="">
</div>

# For admins

Cobblestone is a navigation plugin: it answers "how do I get there from here?" with a route your
players walk. It adds two commands, a handful of permission nodes, and one config file.

## Install

=== "Paper"

    1. Download the Paper jar from [Modrinth](https://modrinth.com/plugin/cobblestoneplugin).
    2. Drop it into `plugins/`.
    3. Start the server.

    Requires **Paper 1.21 or newer**, running on **Java 25 or newer** (the Paper build is
    compiled for 25). Folia is supported.

    Cobblestone's files land in `plugins/cobblestone/`:

    ```
    plugins/cobblestone/
      config.yml      # generated on first run, fully commented
      cobblestone.mv.db  # H2 database: locations, deaths, discovered portals
    ```

=== "Sponge"

    1. Download the Sponge jar from [Modrinth](https://modrinth.com/plugin/cobblestoneplugin).
    2. Drop it into your server's plugin folder (`mods/` on SpongeVanilla and SpongeForge).
    3. Start the server.

    Requires **Sponge API 12**, running on **Java 21 or newer**.

    Cobblestone's files land in its own config directory, normally
    `config/cobblestone/`:

    ```
    config/cobblestone/
      config.yml      # generated on first run, fully commented
      cobblestone.mv.db  # H2 database: locations, deaths, discovered portals
    ```

No setup is required. The defaults are meant to be safe on a small server; if yours is large, or
your hardware is thin, read [Configuration](config.md).

## What it gives your server

### A vanilla SMP gets a memory

Players save places by name and walk back to them. They can navigate to where they died, to
another world, or to a friend who is moving around. Cobblestone learns your nether and end portal
links by watching players use them, so routes cross dimensions the way your players actually do.

```
/stone location set home
/stone location set spawn -global     # everyone can navigate to this one
/nav death
```

### Towny towns become navigable

With [CobblestoneTowny](integrations.md#towny) installed, every town, outpost and named plot is a
destination, `/town spawn` and `/nation spawn` become routes the search may take, and Towny's build
protection is respected — a route will not tell a player to dig through land they cannot build on.

```
/nav town riverwood home
/nav town riverwood outpost north
```

### RPG servers get a quest guide

Quest plugins ([BetonQuest](integrations.md#betonquest),
[BeautyQuests](integrations.md#beautyquests), [Quests](integrations.md#quests-pikamug)) and
[Citizens](integrations.md#citizens) surface their objectives and NPCs as destinations, and can
start a guided trip automatically when a player accepts a quest or advances a stage. With
[Typewriter](integrations.md#typewriter), "guide the player there" is an action you drop into a
sequence.

```
/nav npc Blacksmith
/nav quest lostlantern
```

## Set up permissions

The player-facing nodes default to **allow**, so a fresh install works for everyone with no
permission setup at all. The admin nodes default to **op**.

| Node | Default | Grants |
| --- | --- | --- |
| `cobblestone.navigate` | everyone | `/navigate`, `/cobblestone trips`, `/cobblestone cancel` |
| `cobblestone.location` | everyone | `/cobblestone location …` for personal locations |
| `cobblestone.navigator` | everyone | using a non-default navigator |
| `cobblestone.admin.location.global` | op | `-global` locations |
| `cobblestone.admin.reload` | op | `/cobblestone reload` |
| `cobblestone.admin.portals` | op | `/cobblestone portals clear` |
| `cobblestone.admin.loglevel` | op | `/cobblestone loglevel` |

To take something away, deny it. For example, to stop a rank from navigating to other players:

```
/lp group default permission set cobblestone.navigate.cobblestone.player.* false
```

That works because **every destination has its own permission node**, built from its address. The
full story — including how to hide one town, one quest, or a whole integration — is in
[Permissions](permissions.md).

## Where to go next

- **[Commands](commands.md)** — every command, its syntax and its permission.
- **[Permissions](permissions.md)** — the nodes, and the per-destination gate.
- **[Configuration](config.md)** — every setting in `config.yml`, and what to change when searches
  fail or the server is busy.
- **[Integrations](integrations.md)** — the companion plugins and what each one adds.

## Operational notes

!!! note "Searches run off the main thread"

    The pathfinding itself is asynchronous. What it *does* touch the main thread for is reading
    world data and asking other plugins questions (Towny's build checks, for instance). The
    settings that bound this are `search.max_concurrent_per_player`, the
    `search.algorithm.*` limits, and `search.chunks.policy`.

!!! warning "Chunk loading is a real cost"

    By default a search may read chunks that already exist on disk. It will never generate new
    terrain unless you set `search.chunks.policy: allow_load_and_generate` — which permanently
    grows your world files. See [Configuration](config.md#searchchunks).

!!! note "What Cobblestone stores"

    Player locations, each player's most recent death location, and the portal links it has
    observed. Embedded H2 by default, no setup. Clearing learned portals is
    `/cobblestone portals clear`.
