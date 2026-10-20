---
title: Players
description: Save locations, navigate to destinations, and manage trips.
---

<div class="cs-illustration">
<img src="../assets/illustrations/player-trail.svg" alt="">
</div>

# Players

Cobblestone computes a route to a destination and draws it as a particle trail. No client
modifications are required.

| Command | Alias | Purpose |
| --- | --- | --- |
| `/navigate` | `/nav` | Navigate to a destination, or open the navigation menu. |
| `/location` | `/loc` | Save, remove, and list locations. |
| `/cobblestone` | `/stone` | Manage trips. Also holds `navigate` and `location` as subcommands. |

## Quick start

Save your current position:

```
/loc set home
```

Navigate back to it from anywhere:

```
/nav home
```

Cobblestone reports `Searching for a route...`, then `Success!` once a route is found. Hover over
`Success!` to see the search time and estimated travel time. Hover over `Searching for a route...`,
or over the error if no route is found, to see the destination's address. For a fixed place, such
as a saved location, your death location or a set of coordinates, the coordinates are shown too,
so you can still find your way there by hand. The trip ends automatically when you
arrive within two blocks of the destination.

## The trail

The trail renders only the next segment of the route, brightest at the point to head for next.

- **Straying.** If you leave the trail, a short guide path is drawn back to it. Beyond 32 blocks
  (by default), the route is recomputed from your current position.
- **Action steps.** Some steps require an action: opening a door, placing a boat, mounting a horse,
  entering a portal, or running a command. Cobblestone highlights the location and sends a
  message. Command prompts, such as `Run /warp market.`, can be clicked.
- **Ending.** A trip ends on arrival, on `/stone cancel`, or on disconnect. Death does not end a
  trip. A trip to a pet or mount ends if it dies, and a trip to a player ends if they log off.

## The navigation menu

Type `/nav` on its own to open a menu of everywhere you can go. Bedrock players on a server with
[Geyser](https://geysermc.org) can open it without typing by doing the **Simple Clap** emote.

- Items marked **»** open a further list. Others start navigating as soon as you click them.
- Something that is both a place and a list, such as a town with its plots, opens. The place
  itself is listed first.
- A destination that needs more from you, such as `coordinates block`, asks for it in a form
  before starting.
- The top row of the first page shows the destinations you went to most recently, so a favorite is
  one click away. They are forgotten when the server restarts.

## Destinations

Type `/nav ` and press ++tab++ to list the destinations available to you. The list depends on the
plugins installed on the server.

### Saved locations

```
/nav home                          # a personal location
/nav location private home         # the same, fully qualified
/nav location global spawn         # a server-wide location
```

### Death location

```
/nav death
```

Your most recent death location. Admins may disable this destination. If you have not died since it
was enabled, you are told so.

### Respawn point

```
/nav respawn
```

Where you will respawn: your bed, a charged respawn anchor, or a point set by `/spawnpoint`. If you
have none, you are told so.

### World spawn

```
/nav spawn
```

Where you respawn if you have no respawn point of your own. The server has one, normally in the
overworld.

### Surface

```
/nav surface
```

The nearest place open to the sky. The route heads upward, so it is the quickest way out of a cave
or a mine. Water, leaves and glass count as a roof. If you are already open to the sky, you are
told so instead.

### Target

```
/nav target
```

The block you are looking at, up to 256 blocks away: the open space in front of it, or the ground
below that if it is the side of a wall. Water counts as something to look at, unless you are
underwater; grass and flowers are looked through.

### Coordinates

```
/nav coordinates block 100 64 -200                 # a block in this world
/nav coordinates block 100 64 -200 world_nether    # a block in another world
/nav coordinates surface 100 -200                  # the open-sky spot at x=100, z=-200
```

Whole numbers only. The world is optional and defaults to the one you are in. Tab completion offers
your own coordinates. The word `coordinates` is always required, so `/nav surface` still means the
nearest open sky. Coordinates in terrain that has never been generated are refused.

### Mount

```
/nav mount
/nav horse
```

The tamed horse, donkey, mule, llama or camel you last rode. `horse` is another name for it, for
any of these. The route is recomputed periodically as it moves while it is loaded, and otherwise
heads to where it was last seen. If it dies, the trip ends, and there is no mount to go back to
until you ride another.

### Pets

```
/nav pet Rex
```

Your named, tamed animals that are currently loaded. Pets that share a name are numbered by when
they were tamed: `Rex`, `Rex-2`, … The route is recomputed periodically as the pet moves, and the
trip ends if the pet dies.

### Players

```
/nav player Steve
```

Online players only. The route is recomputed periodically as the target moves, and the trip ends
if they log off.

### Worlds

```
/nav world world_nether
```

Routes through a known entry point, typically a portal that Cobblestone has observed in use.

### Integrations

Available only when the corresponding integration is installed:

```
/nav town riverwood home           # Towny: town spawn
/nav town riverwood outpost north  # Towny: outpost
/nav npc Blacksmith                # Citizens: NPC
/nav home cabin                    # EssentialsX: home
/nav spawn                         # EssentialsX: spawn
/nav quest lostlantern             # Quests, BeautyQuests: current objective
/nav compass tower                 # BetonQuest: quest compass target
```

## Addresses

Every destination has a full address, such as `cobblestone location private home`. Leading words
may be omitted as long as the remainder identifies exactly one destination:

```
/nav cobblestone location private home
/nav location home
/nav home
```

The destination's own name is always required. Some levels, such as town and NPC names, are marked
strict by their provider and are also required. A destination that takes values, such as
`coordinates`, takes them after its name; they are never part of the address.

If the input matches more than one destination, the candidates are listed:

```
That destination is ambiguous; be more specific: location private home, essentials home home
```

Tab completion is suppressed when there are too many candidates. Type a few more characters and
press ++tab++ again.

## Flags

Flags follow the destination and may be combined.

| Flag | Effect |
| --- | --- |
| `-live` / `-no-live` | Enable or disable periodic route recomputation. Enabled by default only for moving targets. |
| `-no-<mode>` | Exclude a travel mode, e.g. `-no-swim`. |
| `-no-mode <mode>` | Long form of the above. |
| `-no-world <world>` | Exclude a world, e.g. `minecraft:the_nether`. |
| `-no-dimension <dimension>` | Exclude a dimension, e.g. `the_end`. |
| `-navigator <id>` | Select a navigator. The default is `trail`. |

Travel modes: `walk`, `swim`, `fly`, `mine`, `fall`, `climb`, `boat`, `horse`, `door`. The `fly`
mode is used only if you are able to fly, and `mine` only where you may break blocks.

```
/nav home -no-swim -no-mine
/nav player Steve -no-live
/nav home -no-world minecraft:the_nether
```

## Saved locations

```
/loc set <name>      # save the current position
/loc unset <name>    # delete a location
/loc list            # list personal and global locations
```

`/loc` is short for `/location`, which is also available as `/cobblestone location`. Names are single words and are scoped to each
player. Where a personal and a global location share a name, use `location private <name>` or
`location global <name>`.

## Trips

Up to three trips may run at once by default.

```
/stone trips              # list active trips
/stone cancel <id>        # cancel one trip
/stone cancel all         # cancel all trips and pending searches
/stone hud [on|off]       # show or hide your trip HUD
```

`/stone cancel` with no argument is equivalent to `all`.

While you have trips, a HUD on the right of the screen lists each one with its estimated time left,
updated every second:

```
Trips
home - 3m 24s
caves - 48s
```

Each trip is named by the shortest part of its address that tells it apart from your other trips.
`/stone hud` with no argument switches it on or off; your choice is remembered.

To tell several trails apart, stand still for a second: a few blocks ahead of you, the name of
each trip appears over its trail (or the guide back to it), named the same way as in the HUD. With
one trip to a player named Steve it reads `Steve`; add a trip to a town also named Steve and they
read `player Steve` and `town Steve`. A label only appears where you can see it, and the labels go
away as soon as you move, so they stay out of your way while you follow the trail. **Hit the red
`Cancel` above a label to cancel that trip**; it works from any distance you can see it from. On
Sponge servers, other players nearby can see your labels too, but only you can cancel your trips
with them.

## Errors

| Message | Cause |
| --- | --- |
| `There is no route to that destination.` | No traversable route exists. |
| `The search ran out of resources before finding a route.` | The search reached its cell limit. |
| `The search took too long and was stopped.` | The search reached its time limit. |
| `No destination matches ...` | No destination has that name. |
| `That destination is ambiguous; be more specific: ...` | Add a word from the full address. |
| `Usage: /navigate ...` | The destination takes values; type them as shown. |
| `... is not valid there. Usage: /navigate ...` | One of the values could not be read. |
| `You already have too many active trips; cancel one first.` | Cancel a trip with `/stone cancel`. |
| `You don't have permission to do that.` | The destination or navigator is restricted. |

Search limits are set by the server. See [Configuration](admins/config.md#search).
