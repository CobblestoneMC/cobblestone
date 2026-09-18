---
title: Integrations
description: The companion plugins that connect Cobblestone to Towny, EssentialsX, Citizens, quest plugins and Typewriter.
---

# Integrations

Each integration is a small separate plugin. Install only the ones you use; each needs both
Cobblestone and its target plugin present, and disables itself with a log line if the target is
missing.

They are downloaded from the same [Modrinth page](https://modrinth.com/plugin/cobblestoneplugin) as
Cobblestone itself.

!!! note "Paper builds"

    The integrations published from this repository are built against Paper. Cobblestone's
    integration API exists on both platforms — nothing about it is Paper-specific — but the
    companion plugins here target the Paper versions of Towny, EssentialsX, Citizens and the quest
    plugins.

An integration adds destinations to `/navigate` under its **target plugin's** name, so the
permission nodes read `cobblestone.navigate.towny.…`, not `…cobblestonetowny…`. See
[Permissions](permissions.md#the-per-destination-gate).

---

## Towny

**CobblestoneTowny** · requires Towny

Destinations:

```
/nav town <town> home            # the town's spawn
/nav town <town> outpost <name>
/nav town <town> plot <name>     # named plots
/nav town <town> type <name>     # plots by type (shop, farm, …)
/nav resident                    # your own town
```

Town names are a strict level, so they can't be skipped — `/nav town riverwood home`, never just
`/nav riverwood home`.

It also teaches the search two things:

- **Teleports as routes.** `/town spawn`, `/nation spawn` and `/town outpost` become edges the
  search may take, offered only when that player could actually run the command (Towny's own
  permission, plus the public/own/nation/ally, outlaw and enemy rules). The route then prompts the
  player to run it.
- **Build protection.** Towny decides whether a player may break a given block, so routes that
  involve mining avoid land they can't build on.

## EssentialsX

**CobblestoneEssentials** · requires EssentialsX (EssentialsSpawn optional)

```
/nav home <name>     # one of the player's own Essentials homes
/nav spawn           # server spawn, if EssentialsSpawn is installed
```

`/home` and `/spawn` also become route steps where the player is allowed to use them, so a long
journey may start with "run `/home cabin`" and continue on foot from there.

## Citizens

**CobblestoneCitizens** · requires Citizens

```
/nav npc <name>
```

Every NPC on the server, resolved live so a moved NPC is routed to where it now stands. The `npc`
level is strict — with hundreds of NPCs, name-promotion would be unusable.

## BetonQuest

**CobblestoneBetonQuest** · requires BetonQuest · **GPL-3.0**

```
/nav compass <name>
```

Each of the player's active quest compasses is a destination. When a player sets their quest
compass, a guided trip can start automatically.

Config (`plugins/CobblestoneBetonQuest/config.yml`):

```yaml
auto-navigate: true      # start a trip when a compass target is set
navigator: trail         # display style for those trips
particles: [DUST, GLOW]
colors: [FFD54F, 4FC3F7]
compasses:               # per-compass overrides, keyed by compass id
  dungeon_entrance:
    enabled: false
```

!!! warning "License"

    This module links against BetonQuest and is therefore distributed under the **GPL-3.0**, unlike
    the rest of Cobblestone (MIT). It ships its own LICENSE file.

## BeautyQuests

**CobblestoneBeautyQuests** · requires BeautyQuests

```
/nav quest <name>
```

Each started quest's current locatable stage is a destination, and advancing to a new locatable
stage can start a trip automatically.

Config (`plugins/CobblestoneBeautyQuests/config.yml`) mirrors the BetonQuest one, with per-quest
overrides keyed by the quest's numeric id:

```yaml
auto-navigate: true
navigator: trail
quests:
  '3':
    enabled: false
```

## Quests (PikaMug)

**CobblestonePikamugQuests** · requires Quests

```
/nav quest <name>
```

The player's current objectives, with the same auto-navigate behaviour. Per-quest overrides are
keyed by the quest id (its file name):

```yaml
quests:
  slay-the-dragon:
    enabled: false
```

Because PikaMug's plugin is named `Quests`, its destinations live under `quests` and its permission
nodes read `cobblestone.navigate.quests.quest.<name>`.

## Typewriter

**cobblestone-typewriter** · a Typewriter extension, not a Bukkit plugin

Adds a **Navigate Player** action entry. Drop it into a sequence and the triggering player is sent
on a Cobblestone trip to the location you configure — for pointing a player at the objective right
after they accept a quest.

Install it the way you install any Typewriter extension, not by dropping it into `plugins/`.

---

## Two integrations, one destination name

Nothing stops EssentialsX and Towny both offering a `home`. Cobblestone keeps them apart by
address (`essentials home cabin` versus `towny town riverwood home`) and only complains when a
player's shorthand is genuinely ambiguous — at which point it lists the candidates and asks them to
be more specific.

## Writing your own

Everything these integrations do is public API: register destinations, add route edges, constrain
where a player may dig, or supply your own display. See the
[developer documentation](../developers/index.md) — the Towny integration is about 200 lines.
