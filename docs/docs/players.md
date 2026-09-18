---
title: For players
description: Save places, navigate to them, and manage your trips.
---

<div class="cs-illustration">
<img src="../assets/illustrations/player-trail.svg" alt="">
</div>

# For players

Cobblestone finds you a route and draws it on the ground. You walk it. That's the whole idea —
nothing teleports you, and you never need a map mod.

Two commands do almost everything:

| Command | Short form | What it's for |
| --- | --- | --- |
| `/navigate` | `/nav` | Go somewhere. |
| `/cobblestone` | `/stone` | Everything else: saving places, listing trips, cancelling. |

---

## Your first trip

Stand somewhere you want to remember — your base, say — and name it:

```
/stone location set home
```

Now go anywhere. When you want to come back:

```
/nav home
```

Cobblestone answers `Searching for a route...`, thinks for a moment, then `Success!`. Hover over
that message to see how long the search took and how long the walk should take.

A trail of particles appears ahead of you. Follow it. When you get within a couple of blocks of
your destination, the trip ends on its own.

!!! tip "The route is real"

    The trail goes where you can actually go. It climbs ladders, swims rivers, walks you into a
    nether portal and out the other side, and will happily route you the long way around a
    mountain if that's genuinely faster.

---

## Following the trail

**The trail flows ahead of you**, brightest at the point you should head for next. Only the next
stretch is drawn, not the whole route, so a long journey doesn't fill the sky with particles.

**If you wander off**, Cobblestone draws a short column of particles from you back to the trail.
Stray far enough (32 blocks by default) and it quietly recalculates the whole route from where you
now are — you don't have to run the command again.

**Some steps need you to do something.** When the route goes through a door, a boat, a horse, or a
teleport, Cobblestone highlights the spot and tells you what to do:

- `Run /warp market.` — click the message, or type it, and the route continues from where you land.
- `Perform the highlighted action to continue along the trail.` — place a boat, mount the horse,
  open the door, step into the portal.

**Trips end when you disconnect.** Dying or getting thoroughly lost doesn't cancel one — logging
out does.

---

## Where you can go

Type `/nav ` and press ++tab++. Cobblestone offers everything you're allowed to navigate to, in
groups. What's on offer depends on which plugins your server runs.

### Places you saved

```
/nav home                          # your own saved location
/nav location private home         # the same thing, spelled out in full
/nav location global spawn         # a location the admins saved for everyone
```

### Where you died

```
/nav death
```

Your most recent death, remembered so you can walk back to your things. (An admin can turn this
off; if they have, the destination simply isn't offered.)

### Another player

```
/nav player Steve
```

Online players only. Because the target moves, this trip **keeps recalculating on its own** —
Cobblestone follows them as they walk.

### Another world

```
/nav world world_nether
```

Routes you to that world by whatever way in actually exists — usually a portal it has watched
somebody use.

### Things other plugins offer

If your server runs the matching integration:

```
/nav town riverwood home           # Towny: a town's spawn
/nav town riverwood outpost north  # …an outpost
/nav npc Blacksmith                # Citizens: any NPC
/nav home cabin                    # EssentialsX: one of your /home points
/nav spawn                         # EssentialsX: server spawn
/nav quest lostlantern             # Quests / BeautyQuests: your current objective
/nav compass tower                 # BetonQuest: a quest-compass target
```

---

## Typing less

Every destination has a full address, like `cobblestone location private home` or `towny town
riverwood home`. You never have to type all of it: **you can leave out any of the leading words**,
as long as what's left still points at exactly one place.

```
/nav cobblestone location private home     # the full address
/nav location home
/nav home                                  # usually enough
```

The **last** word — the name of the thing itself — is always required. Some levels are also marked
as required by the plugin that provides them (town names, NPC names), because skipping them would
be chaos.

If what you typed matches more than one place, Cobblestone tells you so and lists the candidates:

```
That destination is ambiguous; be more specific: location private home, essentials home home
```

Add another word from the address and try again.

!!! tip

    Tab-completion only suggests things when there are few enough to be useful. If pressing ++tab++
    offers nothing, type a few more letters and try again.

---

## Options

Flags go after the destination and can be combined.

### `-live` / `-no-live`

Keeps the route up to date as things move:

```
/nav player Steve -no-live     # freeze the route at where they were
/nav home -live                # recompute the route as you travel
```

Trips to a moving target (another player) are live by default; everything else isn't.

### `-no-<mode>` — travel without a mode

Don't feel like swimming? Don't want the route to assume you'll dig?

```
/nav home -no-swim
/nav home -no-mine -no-boat
/nav home -no-mode fly         # the long form, same thing
```

Modes: `walk`, `swim`, `fly`, `mine`, `fall`, `climb`, `boat`, `horse`, `door`.

Some of those you only have if the server gives them to you anyway — `fly` is used only if you can
actually fly, and `mine` only where you're allowed to break blocks.

### `-no-world` / `-no-dimension` — stay out of somewhere

```
/nav home -no-world minecraft:the_nether
/nav home -no-dimension the_end
```

Useful when the fastest route is a nether tunnel and you would rather not.

### `-navigator <id>` — change how the route is shown

```
/nav home -navigator trail
```

`trail` is the built-in particle trail and the default. Other plugins can add their own; your
server may or may not have any.

---

## Managing what you saved

```
/stone location set <name>      # save where you're standing
/stone location unset <name>    # forget it
/stone location list            # everything you can use, yours and the server's
```

`/stone loc` is a shorter way to type `/stone location`.

Names are one word, and your own names are yours alone — your `home` and another player's `home`
never collide. If the server has a global location with the same name as yours, reach them apart
with `location private home` and `location global home`.

---

## Managing your trips

You can have more than one trip running at once (three, by default):

```
/stone trips              # list them, with ids and remaining time
/stone cancel 2           # cancel trip 2
/stone cancel all         # cancel everything, including searches still running
/stone cancel             # same as "all"
```

A search that's still thinking counts too — `cancel` stops it.

---

## When it doesn't work

| Message | What happened |
| --- | --- |
| `There is no route to that destination.` | No walkable way exists — the destination may be on an island, walled in, or in a world with no way in. |
| `The search ran out of resources before finding a route.` | The destination is very far away, or the way there is a maze. The server sets this limit. |
| `The search took too long and was stopped.` | Same idea, but it hit the time budget instead. |
| `No destination matches ...` | Nothing by that name — check with ++tab++. |
| `That destination is ambiguous; be more specific: ...` | Add another word from the address. |
| `You already have too many active trips; cancel one first.` | `/stone cancel all`. |
| `You don't have permission to do that.` | An admin has restricted that destination or navigator. |

If a route exists but Cobblestone can't find it, tell your admins — the search budget and how much
of the world it's allowed to read are both [server settings](admins/config.md#search).
