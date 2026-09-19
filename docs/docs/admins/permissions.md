---
title: Permissions
description: Command nodes and per-destination permission nodes.
---

# Permissions

Cobblestone defines a fixed set of command nodes and generates a node for every destination and
navigator.

## Command nodes

| Node | Default | Grants |
| --- | --- | --- |
| `cobblestone.navigate` | allow | `/navigate`, `/cobblestone trips`, `/cobblestone cancel` |
| `cobblestone.location` | allow | `/cobblestone location set/unset/list` for personal locations |
| `cobblestone.navigator` | allow | Parent of the per-navigator nodes |
| `cobblestone.admin.location.global` | op | Creating and deleting global locations |
| `cobblestone.admin.reload` | op | `/cobblestone reload` |
| `cobblestone.admin.portals` | op | `/cobblestone portals clear` |
| `cobblestone.admin.loglevel` | op | `/cobblestone loglevel` |

=== "Paper"

    Declared in `paper-plugin.yml` with the defaults above.

=== "Sponge"

    Registered with the permission service on startup. Player nodes are assigned to the `USER`
    role and admin nodes to the `ADMIN` role.

## Destination nodes

Each destination has a canonical address, which maps to a node under `cobblestone.navigate`:

| Address | Node |
| --- | --- |
| `cobblestone location private home` | `cobblestone.navigate.cobblestone.location.private.home` |
| `cobblestone death` | `cobblestone.navigate.cobblestone.death` |
| `cobblestone player Steve` | `cobblestone.navigate.cobblestone.player.Steve` |
| `towny town riverwood home` | `cobblestone.navigate.towny.town.riverwood.home` |
| `citizens npc Blacksmith` | `cobblestone.navigate.citizens.npc.Blacksmith` |

The first segment is the lower-cased name of the owning plugin. Integrations register under the
plugin they integrate with (`towny`, `citizens`, `essentials`, `betonquest`, …), not under the
name of the companion plugin.

### Evaluation

- **Default allow.** A destination is permitted unless its node is explicitly set to `false`.
- **Exact node only.** Parent nodes are not consulted, so denying `cobblestone.navigate.towny` has
  no effect. Deny a branch with a wildcard that your permission plugin expands:

    ```
    /lp group default permission set cobblestone.navigate.towny.* false
    ```

    The permission plugin's own precedence rules apply, so a narrower grant overrides a broader
    deny.

- **Tab completion.** Denied destinations are omitted from suggestions.

### Examples

Deny navigation to players:

```
/lp group default permission set cobblestone.navigate.cobblestone.player.* false
```

Deny the death destination except for one group:

```
/lp group default permission set cobblestone.navigate.cobblestone.death false
/lp group donor permission set cobblestone.navigate.cobblestone.death true
```

Deny a single town:

```
/lp group default permission set cobblestone.navigate.towny.town.riverwood.* false
```

Deny all Citizens NPCs except one:

```
/lp group default permission set cobblestone.navigate.citizens.* false
/lp group default permission set cobblestone.navigate.citizens.npc.Blacksmith true
```

### Navigation and teleportation

Destination nodes control only whether a player may be routed to a destination. Teleport commands
such as `/home`, `/town spawn`, and `/warp` remain governed by their own plugins' permissions.
Integrations offer a teleport as a route step only when the player may run the command.

### Provider permissions

A provider may attach additional required permissions to a destination
(`MinecraftDestination#permissions`). The player must hold all of them. Cobblestone's own
destinations declare none.

## Navigator nodes

Non-default navigators are gated by `cobblestone.navigator.<id>`:

```
/lp group default permission set cobblestone.navigator.guide false
```

These nodes default to allow and are checked only when `-navigator <id>` is used. The default
`trail` navigator requires only `cobblestone.navigate`.

## Case sensitivity

Destination keys are used verbatim as node segments (`…citizens.npc.Blacksmith`). If a deny has no
effect, try the lower-cased form; not all permission plugins fold case consistently.
