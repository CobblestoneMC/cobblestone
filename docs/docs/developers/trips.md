---
title: Trips
description: Start trips programmatically.
---

# Trips

A **trip** is a path being followed by a player, rendered by a navigator. Trips recompute when the
player strays and end on arrival. `TripService` starts trips programmatically.

## Starting a trip

=== "Paper"

    ```java
    import org.cobblestonemc.paper.plugin.api.CobblestonePaperApi;
    import org.cobblestonemc.plugin.api.NavigatorSettings;

    CobblestonePaperApi.tripService()
        .navigate(player, objectiveLocation, NavigatorSettings.defaults(), "Lost Lantern");
    ```

=== "Sponge"

    ```java
    import org.cobblestonemc.sponge.plugin.api.CobblestoneSpongeApi;
    import org.cobblestonemc.plugin.api.NavigatorSettings;

    CobblestoneSpongeApi.tripService()
        .navigate(player, objectiveLocation, NavigatorSettings.defaults(), "Lost Lantern");
    ```

This runs the search and starts the trip. The label is displayed in `/cobblestone trips`.

## Outcomes

`navigate` returns a `CompletableFuture<TripOutcome>`:

```java
tripService.navigate(player, objective, NavigatorSettings.defaults(), "Lost Lantern")
    .thenAccept(outcome -> {
      switch (outcome) {
        case TripOutcome.Started started ->
            log("trip {} started, about {}s", started.tripId(), started.durationSeconds());
        case TripOutcome.Failed failed ->
            player.sendMessage("No route found.");   // failed.reason()
        case TripOutcome.TripLimitReached ignored ->
            player.sendMessage("Finish or cancel a trip first.");
        case TripOutcome.Error error ->
            log.warn("navigation failed", error.throwable());
      }
    });
```

`FailureReason` is one of `NO_ROUTE`, `DESTINATION_UNREACHABLE`, `LIMIT_EXCEEDED`, `TIMED_OUT`,
`CANCELLED`.

An overload accepts only a failure callback:

```java
tripService.navigate(player, objective, NavigatorSettings.defaults(),
    reason -> player.sendMessage("No route: " + reason));
```

!!! warning

    The future completes off the main thread. Return to the server or region thread before
    accessing server state.

## Navigator settings

`NavigatorSettings` selects the navigator and overrides its appearance for a single trip. Unset
values fall back to the server configuration.

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
    import org.cobblestonemc.sponge.plugin.api.TrailNavigatorSettings;

    NavigatorSettings settings = TrailNavigatorSettings.builder()
        .particles(List.of(ParticleTypes.DUST.get()))
        .colors(List.of(Color.ofRgb(0xFFD54F), Color.ofRgb(0x4FC3F7)))
        .highlightParticles(List.of(ParticleTypes.END_ROD.get()))
        .build();

    tripService.navigate(player, objective, settings, "Lost Lantern");
    ```

To use another navigator, build settings for its id:

```java
NavigatorSettings.builder("guide").build();
```

`NavigatorSettings.defaults()` selects the server's default navigator with its configured
appearance.

## Starting a trip from a path

To render a path from a search you ran (see [Searching](searching.md)):

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
        CobblestoneSpongeApi.tripService()
            .startTrip(player, success.path(), NavigatorSettings.defaults());
      }
    });
    ```

Trips started this way are not live. Use `navigate` for trips that re-search.

## Lifecycle

Programmatic trips appear in `/cobblestone trips`, count toward `trips.max_active_per_player`, and
can be cancelled by the player. Do not restart a trip the player has cancelled.

Trips end on arrival, cancellation, or disconnect.
