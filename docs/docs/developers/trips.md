---
title: Trips
description: Send a player on a guided journey from your own code.
---

# Trips

A **trip** is a journey in progress: a solved path, a navigator drawing it, and the bookkeeping that
recalculates when the player strays and ends it when they arrive. `TripService` is the shortest path
from "I know where this player should go" to "the player is being shown the way".

This is what quest integrations and NPC guides use. If all you want is *go there*, you want this
page and nothing else.

## Send a player somewhere

=== "Paper"

    ```java
    import org.cobblestonemc.paper.plugin.api.CobblestonePaperApi;
    import org.cobblestonemc.plugin.api.NavigatorSettings;

    CobblestonePaperApi.tripService()
        .navigate(player, objectiveLocation, NavigatorSettings.defaults(), "Lost Lantern");
    ```

=== "Sponge"

    ```java
    import org.cobblestonemc.sponge12.plugin.api.CobblestonePluginApi;
    import org.cobblestonemc.plugin.api.NavigatorSettings;

    CobblestonePluginApi.tripService()
        .navigate(player, objectiveLocation, NavigatorSettings.defaults(), "Lost Lantern");
    ```

That one call runs the search, starts the trip, and draws the trail. The `label` is what
`/cobblestone trips` shows the player; pass it whenever you have a human-readable name.

## Handle the outcome

`navigate` returns a `CompletableFuture<TripOutcome>`:

```java
tripService.navigate(player, objective, NavigatorSettings.defaults(), "Lost Lantern")
    .thenAccept(outcome -> {
      switch (outcome) {
        case TripOutcome.Started started ->
            log("trip {} started, about {}s", started.tripId(), started.durationSeconds());
        case TripOutcome.Failed failed ->
            player.sendMessage("I can't find a way there right now.");   // failed.reason()
        case TripOutcome.TripLimitReached ignored ->
            player.sendMessage("Finish or cancel a trip first.");
        case TripOutcome.Error error ->
            log.warn("navigation blew up", error.throwable());
      }
    });
```

`FailureReason` is one of `NO_ROUTE`, `DESTINATION_UNREACHABLE`, `LIMIT_EXCEEDED`, `TIMED_OUT`,
`CANCELLED`.

If all you care about is the failure, there's a shorthand that ignores success:

```java
tripService.navigate(player, objective, NavigatorSettings.defaults(),
    reason -> player.sendMessage("No route: " + reason));
```

!!! warning "The future completes off the main thread"

    Searches are asynchronous. Schedule back onto the server (or region) thread before touching
    most server state in the callback.

## Style the trip

`NavigatorSettings` picks the navigator and overrides its appearance for this trip only. Anything
you don't set falls back to the server's config.

=== "Paper"

    ```java
    import org.bukkit.Color;
    import org.bukkit.Particle;
    import org.cobblestonemc.paper.plugin.api.TrailNavigatorSettings;

    NavigatorSettings settings = TrailNavigatorSettings.builder()
        .particles(List.of(Particle.DUST))
        .colors(List.of(Color.fromRGB(0xFFD54F), Color.fromRGB(0x4FC3F7)))
        .highlightParticles(List.of(Particle.END_ROD))
        .build();

    tripService.navigate(player, objective, settings, "Lost Lantern");
    ```

=== "Sponge"

    ```java
    import org.spongepowered.api.effect.particle.ParticleTypes;
    import org.spongepowered.api.util.Color;
    import org.cobblestonemc.sponge12.plugin.api.TrailNavigatorSettings;

    NavigatorSettings settings = TrailNavigatorSettings.builder()
        .particles(List.of(ParticleTypes.DUST.get()))
        .colors(List.of(Color.ofRgb(0xFFD54F), Color.ofRgb(0x4FC3F7)))
        .highlightParticles(List.of(ParticleTypes.END_ROD.get()))
        .build();

    tripService.navigate(player, objective, settings, "Lost Lantern");
    ```

To use a different navigator entirely — yours, or another plugin's — build settings for its id:

```java
NavigatorSettings.builder("guide").build();
```

And `NavigatorSettings.defaults()` means "the server's default navigator, unstyled", which is
usually the polite choice: it respects whatever the admin configured.

## Start a trip from a path you already have

If you ran the search yourself (see [Searching](searching.md)) and want Cobblestone to render the
result:

=== "Paper"

    ```java
    SearchHandle<Location, MinecraftStepPayload> handle =
        CobblestoneCoreApi.navigationService().navigatePlayer(player, destination);

    handle.future().thenAccept(result -> {
      if (result instanceof NavigationResult.Success<Location, MinecraftStepPayload> success) {
        CobblestonePaperApi.tripService()
            .startTrip(player, success.path(), NavigatorSettings.defaults());
      }
    });
    ```

=== "Sponge"

    ```java
    SearchHandle<ServerLocation, MinecraftStepPayload> handle =
        CobblestoneCoreApi.navigationService().navigatePlayer(player, destination);

    handle.future().thenAccept(result -> {
      if (result instanceof NavigationResult.Success<ServerLocation, MinecraftStepPayload> success) {
        CobblestonePluginApi.tripService()
            .startTrip(player, success.path(), NavigatorSettings.defaults());
      }
    });
    ```

A trip started this way is **not live** — it renders the path you gave it. For a route that keeps
itself up to date, use `navigate`, which owns the re-search.

## What the player can do about it

Trips you start are ordinary trips: they appear in `/cobblestone trips`, count toward
`trips.max_active_per_player`, and can be cancelled with `/cobblestone cancel <id>`. Respect that —
don't restart a trip the player just cancelled.

Trips end when the player arrives, cancels, or disconnects.
