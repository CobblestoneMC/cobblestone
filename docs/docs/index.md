---
title: Cobblestone
description: Server-side navigation for Minecraft. Cobblestone computes a walkable route to a destination and renders it for the player.
hide:
  - navigation
---

<!-- Google tag (gtag.js) -->
<script async src="https://www.googletagmanager.com/gtag/js?id=G-Q76LDBF416"></script>
<script>
  window.dataLayer = window.dataLayer || [];
  function gtag(){dataLayer.push(arguments);}
  gtag('js', new Date());

  gtag('config', 'G-Q76LDBF416');
</script>

<div class="cs-hero" markdown>
<div class="cs-hero__text" markdown>

# Cobblestone

<p class="cs-hero__tagline">Server-side navigation for Minecraft.</p>

<div class="cs-badges">
<a href="https://modrinth.com/plugin/cobblestoneplugin"><img alt="Modrinth version" src="https://img.shields.io/modrinth/v/cobblestoneplugin?label=modrinth"></a>
<a href="https://modrinth.com/plugin/cobblestoneplugin"><img alt="Modrinth downloads" src="https://img.shields.io/modrinth/dt/cobblestoneplugin?label=downloads"></a>
<a href="https://bstats.org/plugin/bukkit/Cobblestone/33624"><img alt="Servers (Paper)" src="https://img.shields.io/bstats/servers/33624?label=servers%20%28paper%29"></a>
<a href="https://bstats.org/plugin/sponge/Cobblestone/33625"><img alt="Servers (Sponge)" src="https://img.shields.io/bstats/servers/33625?label=servers%20%28sponge%29"></a>
<a href="https://central.sonatype.com/artifact/org.cobblestonemc/paper-plugin-api"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/org.cobblestonemc/paper-plugin-api?label=api"></a>
<img alt="License" src="https://img.shields.io/badge/license-MIT-blue">
</div>

</div>
<div class="cs-hero__art">
<img src="assets/illustrations/hero-route.svg" alt="">
</div>
</div>

<div class="grid cards" markdown>

-   **[Players](players.md)**

    Save locations and navigate to them.

-   **[Server admins](admins/index.md)**

    Installation, commands, permissions, and configuration.

-   **[Developers](developers/index.md)**

    Register destinations, modify searches, and start trips.

</div>

## Overview

Cobblestone runs an A* search over the live world and renders the resulting route as a particle
trail. Routes account for walking, swimming, climbing, falling, boats, horses, doors, and portals.
If the player leaves the route, it is recomputed.

Cobblestone does not teleport players itself. Integrations can register teleport commands, such as
EssentialsX `/home` or Towny `/town spawn`, as route steps that the player is prompted to run.

```
/stone location set home        # save the current position
/nav home                       # navigate back to it
```

## Architecture

```mermaid
flowchart LR
    A["/navigate &lt;destination&gt;"] --> B[Destination providers]
    B --> C[A* search]
    D[Search modifications] --> C
    C --> E[Path]
    E --> F[Trip]
    F --> G[Navigator]
    G -. player strays .-> C
```

Destination providers, search modifications, and navigators are all extensible. See the
[developer documentation](developers/index.md).

## Supported versions

| Platform | Minecraft | Java |
| --- | --- | --- |
| Paper | 26.1+ | 25+ |
| Sponge | 1.21.1 | 21+ |
| Sponge | 26.1+ | 25+ |

## Performance

Searches run asynchronously and are bounded per player. The [configuration](admins/config.md#search)
file exposes options to increase or reduce the resources each search may use.

## Metrics

Cobblestone reports anonymous, aggregate usage data to [bStats](https://bstats.org). Disable it
with `metrics.enabled: false`.

## Support

- [Issue tracker](https://github.com/CobblestoneMC/cobblestone/issues)
- [Releases and changelogs](https://modrinth.com/plugin/cobblestoneplugin)
- [Source code](https://github.com/CobblestoneMC/cobblestone)
