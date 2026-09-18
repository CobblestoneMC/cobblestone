---
title: Permissions
description: Cobblestone's permission nodes, and the per-destination gate that decides where each player may navigate.
---

# Permissions

Cobblestone has two kinds of permission: a small fixed set for its commands, and a generated node
for every destination and navigator on the server.

## Command nodes

| Node | Default | Grants |
| --- | --- | --- |
| `cobblestone.navigate` | allow | `/navigate`, and the trip commands that manage its results (`/cobblestone trips`, `/cobblestone cancel`) |
| `cobblestone.location` | allow | `/cobblestone location set/unset/list` for the player's own locations |
| `cobblestone.navigator` | allow | parent of the per-navigator nodes |
| `cobblestone.admin.location.global` | op | creating and deleting `-global` locations |
| `cobblestone.admin.reload` | op | `/cobblestone reload` |
| `cobblestone.admin.portals` | op | `/cobblestone portals clear` |
| `cobblestone.admin.loglevel` | op | `/cobblestone loglevel` |

=== "Paper"

    Declared in the plugin's `paper-plugin.yml`, so a permission plugin sees them with the defaults
    above without any setup.

=== "Sponge"

    Described to the permission service on server start, with the same defaults, assigned to
    Sponge's `USER` role for the player-facing nodes and `ADMIN` for the rest. A node that is not
    described has no default and permission plugins deny it, which is why Cobblestone describes all
    of them explicitly.

---

## The per-destination gate

Every destination in the `/navigate` tree has a **canonical address** — the full key path to it.
Each address maps to a permission node under `cobblestone.navigate`:

| Address | Node |
| --- | --- |
| `cobblestone location private home` | `cobblestone.navigate.cobblestone.location.private.home` |
| `cobblestone death` | `cobblestone.navigate.cobblestone.death` |
| `cobblestone player Steve` | `cobblestone.navigate.cobblestone.player.Steve` |
| `towny town riverwood home` | `cobblestone.navigate.towny.town.riverwood.home` |
| `citizens npc Blacksmith` | `cobblestone.navigate.citizens.npc.Blacksmith` |

The first element is the registering plugin's name, lower-cased: Cobblestone's own destinations sit
under `cobblestone`, and an integration's sit under the plugin it integrates with (`towny`,
`citizens`, `essentials`, `betonquest`, …) — not under the companion plugin's name.

!!! info "Default allow"

    A destination is navigable **unless its node is explicitly set to false**. An unset node is a
    yes. This has to work that way: nobody can declare a node per town, per NPC or per player up
    front, so an undeclared node must not read as a denial.

!!! warning "Only the exact node is checked"

    Cobblestone tests the destination's own full node — it does not walk up the address looking for
    a denied ancestor. Denying `cobblestone.navigate.towny` therefore blocks nothing. To deny a
    whole branch, use a wildcard your permission plugin expands (LuckPerms does):

    ```
    /lp group default permission set cobblestone.navigate.towny.* false
    ```

    Because your permission plugin resolves the node, its own specificity rules apply: a broad deny
    plus a narrow grant still grants.

### Worked examples

Stop everyone from navigating to other players:

```
/lp group default permission set cobblestone.navigate.cobblestone.player.* false
```

Hide the death destination from one rank, but keep it for donors:

```
/lp group default permission set cobblestone.navigate.cobblestone.death false
/lp group donor permission set cobblestone.navigate.cobblestone.death true
```

Hide one town, leaving the rest navigable:

```
/lp group default permission set cobblestone.navigate.towny.town.riverwood.* false
```

Turn the whole Citizens branch off except one NPC:

```
/lp group default permission set cobblestone.navigate.citizens.* false
/lp group default permission set cobblestone.navigate.citizens.npc.Blacksmith true
```

!!! tip "Denied destinations disappear"

    The gate is applied to tab-completion as well as to the command, so a player never sees a
    destination they are not allowed to route to.

### Navigating is not teleporting

This gate answers "may this player be *shown the way* there?" — nothing else. Whether they may
`/home`, `/town spawn` or `/warp` there is that plugin's own permission, and Cobblestone never
overrides it: an integration only offers a teleport as a route step when the player could run the
command themselves.

That separation is useful in both directions. You can let players route to a town's gates without
letting them teleport in, or let them teleport to a spawn while hiding it from navigation.

!!! note "A provider may also require its own permissions"

    Besides the gate, a destination can carry hard requirements from the plugin that registered it
    (`MinecraftDestination#permissions`), all of which the player must hold. Those are for genuine
    access control — Cobblestone's own destinations use none.

---

## The navigator gate

A non-default navigator is gated by `cobblestone.navigator.<id>`:

```
/lp group default permission set cobblestone.navigator.guide false
```

Same semantics as the destination gate: default allow, checked only when the player actually asks
for that navigator with `-navigator <id>`. The built-in `trail` navigator is the default and is
covered by `cobblestone.navigate` alone.

---

## A note on names in nodes

Destination keys become node segments verbatim, so an NPC called `Blacksmith` yields
`…citizens.npc.Blacksmith`. Most permission plugins fold case when matching, but not all do
consistently — if a targeted deny seems to do nothing, try the lower-cased spelling. Names with
spaces make awkward nodes; that's one reason providers are asked to avoid them.
