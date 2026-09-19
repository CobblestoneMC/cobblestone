<div align="center">

<img src="logo.png" alt="Cobblestone" width="180">

# Cobblestone

**Server-side navigation for Minecraft.**

[![Modrinth](https://img.shields.io/modrinth/v/cobblestoneplugin?label=modrinth)](https://modrinth.com/plugin/cobblestoneplugin)
[![Downloads](https://img.shields.io/modrinth/dt/cobblestoneplugin?label=downloads)](https://modrinth.com/plugin/cobblestoneplugin)
[![Servers (Paper)](https://img.shields.io/bstats/servers/33624?label=servers%20%28paper%29)](https://bstats.org/plugin/bukkit/Cobblestone/33624)
[![Servers (Sponge)](https://img.shields.io/bstats/servers/33625?label=servers%20%28sponge%29)](https://bstats.org/plugin/sponge/Cobblestone/33625)
[![Maven Central](https://img.shields.io/maven-central/v/org.cobblestonemc/paper-plugin-api?label=api)](https://central.sonatype.com/namespace/org.cobblestonemc)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

**[📖 Documentation](https://cobblestonemc.org)** ·
[⬇ Download](https://modrinth.com/plugin/cobblestoneplugin) ·
[📊 Stats](https://bstats.org/plugin/bukkit/Cobblestone/33624)

</div>

---

## Quickstart

1. Place the jar in `plugins/` (Paper) or `mods/` (Sponge).
2. Start the server.
3. Try it:

```
/stone location set home     # save the current position
/nav home                    # navigate back to it
```

Cobblestone runs an A* search over the live world and renders the route as a particle trail.

| Platform | Minecraft | Java |
| --- | --- | --- |
| Paper | 26.1+ | 25+ |
| Sponge | 1.21.1 | 21+ |
| Sponge | 26.1+ | 25+ |

## For admins

- **Core**: Personal and global saved locations, death locations, players, worlds, and learned
  nether and End portal links.
- **Towny**: Towns, outposts, and plots as destinations; `/town spawn` and `/nation spawn` as route
  steps; build protection.
- **EssentialsX**: Homes and spawn as destinations; `/home` and `/spawn` as route steps.
- **Citizens, BetonQuest, BeautyQuests, Quests, Typewriter**: NPCs and quest objectives as
  destinations, with optional automatic trips.

Integrations are distributed as separate plugins.

Player permissions default to allow; admin permissions default to op.

| Node | Default | Grants |
| --- | --- | --- |
| `cobblestone.navigate` | everyone | `/navigate`, `/cobblestone trips`, `/cobblestone cancel` |
| `cobblestone.location` | everyone | personal locations |
| `cobblestone.navigator` | everyone | non-default navigators |
| `cobblestone.admin.*` | op | `reload`, `portals`, `loglevel`, `-global` locations |

Each destination also has a node derived from its address:

```
/lp group default permission set cobblestone.navigate.towny.town.riverwood.* false
```

→ [Admin documentation](https://cobblestonemc.org/admins/)

## For players

```
/nav home                       # saved location
/nav death                      # death location
/nav player Steve               # player
/nav town riverwood home        # Towny town
/nav npc Blacksmith             # Citizens NPC

/stone location set home        # save the current position
/stone location set spawn -global
/stone location unset home
/stone location list

/stone trips                    # list active trips
/stone cancel all               # cancel all trips
```

Flags: `-no-<mode>`, `-no-world <world>`, `-no-dimension <dimension>`, `-live`, `-no-live`,
`-navigator <id>`.

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

**Register destinations** under your plugin's name:

```java
CobblestonePaperApi.registrar().registerDestinations(this, player ->
    DestinationTree.builder()
        .leaf("market", () -> Destination.at(marketLocation(), "Market"))
        .build());
```

**Start a trip:**

```java
CobblestonePaperApi.tripService()
    .navigate(player, objective, NavigatorSettings.defaults(), "Lost Lantern");
```

**Add transitions and restrict breaking:**

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

Custom navigators and direct searches are also supported. See
[`project/examples/paper-warps`](project/examples/paper-warps) for a complete example.

→ [Developer documentation](https://cobblestonemc.org/developers/)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT. See [LICENSE](LICENSE). Integration modules that link a GPL plugin carry their own license.
