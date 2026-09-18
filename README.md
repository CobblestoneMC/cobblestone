<div align="center">

<img src="logo.png" alt="Cobblestone" width="180">

# Cobblestone

**Turn-by-turn navigation for Minecraft servers.**
Ask for a place, follow the trail, get there — no map mod, no client install, no teleporting.

[![Build](https://img.shields.io/github/actions/workflow/status/CobblestoneMC/cobblestone/build.yml?branch=main&label=build)](https://github.com/CobblestoneMC/cobblestone/actions/workflows/build.yml)
[![Modrinth](https://img.shields.io/modrinth/v/cobblestoneplugin?label=modrinth)](https://modrinth.com/plugin/cobblestoneplugin)
[![Downloads](https://img.shields.io/modrinth/dt/cobblestoneplugin?label=downloads)](https://modrinth.com/plugin/cobblestoneplugin)
[![Servers](https://img.shields.io/bstats/servers/33624?label=servers%20%28paper%29)](https://bstats.org/plugin/bukkit/Cobblestone/33624)
<!-- Sponge bStats badge — fill in the Sponge plugin id once it is registered:
[![Servers (Sponge)](https://img.shields.io/bstats/servers/SPONGE_ID?label=servers%20%28sponge%29)](https://bstats.org/plugin/sponge/Cobblestone/SPONGE_ID) -->
[![Maven Central](https://img.shields.io/maven-central/v/org.cobblestonemc/paper-plugin-api?label=api)](https://central.sonatype.com/namespace/org.cobblestonemc)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

**[📖 Documentation](https://cobblestonemc.org)** ·
[⬇ Download](https://modrinth.com/plugin/cobblestoneplugin) ·
[📊 Stats](https://bstats.org/plugin/bukkit/Cobblestone/33624)

</div>

---

## Quickstart

1. Drop the jar into `plugins/` (Paper) or your plugin folder (Sponge).
2. Start the server.
3. Try it:

```
/stone location set home     # remember where you are
/nav home                    # …then walk back to it from anywhere
```

Cobblestone runs a real pathfinding search over your live world and draws the route as a particle
trail. Paper 1.21+ on Java 25+ (Folia included), or Sponge API 12 on Java 21+.

## For admins

Cobblestone accentuates the server you already run.

- **Vanilla SMP** — personal and server-wide saved locations, `/nav death` back to your things,
  routes to other players and other worlds, and nether/end portal links learned by watching players
  use them.
- **Towny** — every town, outpost and plot becomes a destination; `/town spawn` and `/nation spawn`
  become route steps; build protection is respected, so routes never tell players to dig where they
  can't.
- **RPG servers** — quest objectives (BetonQuest, BeautyQuests, Quests) and Citizens NPCs become
  destinations, and a trip can start automatically when a player accepts a quest. Typewriter gets a
  "navigate player" action.

Integrations are separate jars — install only what you use.

Permissions default to allow for players and op for admins, so a fresh install needs no setup:

| Node | Default | Grants |
| --- | --- | --- |
| `cobblestone.navigate` | everyone | `/navigate`, `/cobblestone trips`, `/cobblestone cancel` |
| `cobblestone.location` | everyone | personal locations |
| `cobblestone.navigator` | everyone | non-default navigators |
| `cobblestone.admin.*` | op | `reload`, `portals`, `loglevel`, `-global` locations |

Every destination also has its own node built from its address, default-allow, so you can hide one
town or one NPC:

```
/lp group default permission set cobblestone.navigate.towny.town.riverwood.* false
```

→ [Admin documentation](https://cobblestonemc.org/admins/)

## For players

```
/nav home                       # a place you saved
/nav death                      # where you died
/nav player Steve               # a friend (keeps up as they move)
/nav town riverwood home        # a Towny town, if the server has it
/nav npc Blacksmith             # a Citizens NPC

/stone location set home        # save where you're standing
/stone location set spawn -global
/stone location unset home
/stone location list

/stone trips                    # what you have running
/stone cancel all               # stop everything
```

Add `-no-swim`, `-no-mine`, `-no-world <world>`, `-live` or `-navigator <id>` to shape a route.
Tab-completion knows everything you're allowed to go to.

→ [Player documentation](https://cobblestonemc.org/players/)

## For developers

```kotlin
repositories { mavenCentral() }

dependencies {
    // Paper
    compileOnly("org.cobblestonemc:paper-plugin-api:0.1.0")
    // Sponge
    // compileOnly("org.cobblestonemc:sponge-12-plugin-api:0.1.0")
}
```

**Offer destinations** — they appear under `/navigate`, filed under your plugin's name:

```java
CobblestonePaperApi.registrar().registerDestinations(this, player ->
    DestinationTree.builder()
        .leaf("market", () -> Destination.at(marketLocation(), "Market"))
        .build());
```

**Send a player somewhere** — search, trip and trail in one call:

```java
CobblestonePaperApi.tripService()
    .navigate(player, objective, NavigatorSettings.defaults(), "Lost Lantern");
```

**Teach the search new routes, or constrain it** — warps, pads, claim protection:

```java
CobblestoneCoreApi.registrar().register(this, new SearchModificationService() {
  @Override
  public CompletableFuture<List<Transition>> computeTransitions(Player player) {
    return CompletableFuture.completedFuture(
        List.of(Transition.command(player, market, 3.0, "/warp market")));
  }

  @Override
  public BreakChecker computeBreakChecker(Player player) {
    return (p, location, block) ->
        CompletableFuture.completedFuture(claims.mayBreak(p, location));
  }
});
```

You can also register your own navigator (how a route is drawn) or run a search yourself and read
the `Path`. A complete example plugin lives in
[`project/examples/paper-warps`](project/examples/paper-warps).

→ [Developer documentation](https://cobblestonemc.org/developers/)

## Contributing

Bug reports, integrations and doc fixes are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT. See [LICENSE](LICENSE). Integration modules that link a GPL plugin carry their own license.
