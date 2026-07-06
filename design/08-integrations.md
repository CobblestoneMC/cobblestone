# Odyssey — Integration Plugins

Separate, optional plugins whose only job is, at startup, to connect a third-party plugin to Odyssey
by registering `TunnelProvider`s, `NavigatorFactory`s, and/or `DestinationProvider`s through the
platform façade. Each depends on the relevant platform plugin/api + the third-party plugin's API
(sources under `resources/`). Unless noted, **Paper only** (Folia support depends on the target
plugin supporting Folia).

## `OdysseyCitizens` (Citizens) — Paper only
Citizens puppets NPC entities. Registers a `NavigatorFactory` with id **`guide`**: instead of
particles, it spawns an entity of the player's choice that walks the `PathString` ahead of the
player.
- On `start()`: spawn the guide NPC at the trip origin.
- On `tick()`: keep the NPC a configurable number of blocks ahead along the current `Path`; wait
  (pause NPC) if the player falls too far behind; when the path reaches a `Tunnel`, **teleport** the
  NPC through it; despawn on completion or `stop()`.
- Uses the same Trip follow-logic (`06`) to know how far the player has progressed.
- Needs an extra `PlatformApi`/context capability for entity spawn/move/teleport/despawn (added as a
  Citizens-scoped helper, not core).

## `OdysseyEssentials` (EssentialsX) — Paper only
Essentials adds `/home` and `/spawn` teleports.
- Registers a `DestinationProvider` exposing sub-trees `essentials → home/<homes>` and
  `essentials → spawn`, with display names and the Essentials permissions attached to each
  `MinecraftDestination`.
- Registers a `TunnelProvider` so those teleports become one-step `Tunnel`s (origin = player position,
  destination = home/spawn location) — **only** if the player actually has permission to run that
  teleport. This lets routes *use* the teleport as a wormhole in Tier 1.

## `OdysseyTowny` (Towny) — Paper only
Towny lets players form chunk-claimed towns.
- Registers a `DestinationProvider` with a `towny → <town>` sub-tree; each town becomes a
  `MinecraftDestination` whose `Destination` covers the town's claimed region (multi-cell
  `DomainDestination` with a region completion predicate). "Closest town" falls out of the
  multi-endpoint super-sink (`03`).

## Quest plugin integrations — Paper only
Each registers `DestinationProvider`s pointing at the player's active-quest objective location(s)
(e.g. `quest → current`, `quest → <quest-id>`), so players can navigate to "where the quest wants
me." Separate plugins (thin, same pattern), one per quest framework:
- `OdysseyTypewriter` (Typewriter — gabber235). *Internals TBD; investigate its API in
  `resources/` before implementing.*
- `OdysseyQuests` (Quests — PikaMug).
- `OdysseyNotQuests` (NotQuests — Alessio).
- `OdysseyBetonQuest` (BetonQuest — Wolf2323).

## Shared integration pattern
```
onEnable:
  api = server.services.get(PaperOdysseyApi.class)      // provided by folia-plugin
  if api == null: warn("Odyssey not installed"); disable; return
  api.registerDestinationProvider(...)   // and/or tunnel/navigator providers
```
Integrations ship no config of their own beyond enabling/disabling and a few knobs (e.g. guide entity
type). They contribute nothing to the core algorithm — only data.

## Highways (future, related)
Plugin-declared "fast regions" (custom speed runways) reuse the rail `CachedSegment` mechanism
(`04`/`06`): an integration can register segment endpoints + speed, injected as pre-solved Tier-1
edges. Listed here to keep the extension path visible; not a v1 deliverable.
