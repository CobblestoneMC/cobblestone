---
title: Integrations
description: Companion plugins for Towny, EssentialsX, Citizens, quest plugins, and Typewriter.
---

# Integrations

Each integration is a separate plugin, available on
[Modrinth](https://modrinth.com/plugin/cobblestoneplugin). An integration requires both Cobblestone
and its target plugin, and disables itself if the target is absent.

The integrations listed here are Paper builds.

Integration destinations are registered under the target plugin's name. Permission nodes therefore
take the form `cobblestone.navigate.towny.…`, not `cobblestone.navigate.cobblestonetowny.…`. See
[Permissions](permissions.md#destination-nodes).

## Towny

**CobblestoneTowny** · requires Towny

```
/nav town <town> home            # town spawn
/nav town <town> outpost <name>
/nav town <town> plot <name>     # named plot
/nav town <town> type <name>     # plots by type (shop, farm, …)
/nav resident                    # the player's own town
```

The `town` level is strict: `/nav town riverwood home` is valid; `/nav riverwood home` is not.

- **Teleport route steps.** `/town spawn`, `/nation spawn`, and `/town outpost` are offered as route
  steps when the player is permitted to use them under Towny's rules.
- **Build protection.** Routes do not mine through blocks the player may not break.

## EssentialsX

**CobblestoneEssentials** · requires EssentialsX; EssentialsSpawn optional

```
/nav home <name>     # an Essentials home
/nav spawn           # server spawn (requires EssentialsSpawn)
```

`/home` and `/spawn` are offered as route steps when the player is permitted to use them.

## Citizens

**CobblestoneCitizens** · requires Citizens

```
/nav npc <name>
```

NPC positions are resolved at search time. The `npc` level is strict.

## BetonQuest

**CobblestoneBetonQuest** · requires BetonQuest · GPL-3.0

```
/nav compass <name>
```

Each active quest compass is a destination. Setting a compass target can start a trip
automatically.

`plugins/CobblestoneBetonQuest/config.yml`:

```yaml
auto-navigate: true      # start a trip when a compass target is set
navigator: trail
particles: [DUST, GLOW]
colors: [FFD54F, 4FC3F7]
compasses:               # per-compass overrides, keyed by compass id
  dungeon_entrance:
    enabled: false
```

This module links against BetonQuest and is licensed under GPL-3.0.

## BeautyQuests

**CobblestoneBeautyQuests** · requires BeautyQuests

```
/nav quest <name>
```

The current locatable stage of each started quest is a destination. Advancing to a locatable stage
can start a trip automatically.

`plugins/CobblestoneBeautyQuests/config.yml` (per-quest overrides keyed by numeric quest id):

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

Current objectives are destinations, with the same automatic trip behavior. Per-quest overrides are
keyed by quest id:

```yaml
quests:
  slay-the-dragon:
    enabled: false
```

The plugin is named `Quests`, so its nodes take the form `cobblestone.navigate.quests.quest.<name>`.

## Typewriter

**cobblestone-typewriter** · Typewriter extension

Adds a **Navigate Player** action entry, which starts a trip for the triggering player to a
configured location. Install it as a Typewriter extension, not in `plugins/`.

## Name collisions

Multiple integrations may offer the same name, such as `home`. Destinations remain distinct by full
address (`essentials home cabin`, `towny town riverwood home`). Ambiguous player input is rejected
with a list of candidates.

## Custom integrations

All integration functionality is available through the public API. See the
[developer documentation](../developers/index.md).
