---
title: Searching
description: Run a pathfinding search yourself and read the result.
---

# Searching

`NavigationService` is the navigation library underneath everything else: give it a player and a
place, get back a path. Use it when you want the route as *data* — to measure a distance, to decide
whether somewhere is reachable, or to render it yourself.

If you just want the player guided there, use [Trips](trips.md) instead; it runs the search for you.

## Run a search

=== "Paper"

    ```java
    import org.cobblestonemc.api.NavigationResult;
    import org.cobblestonemc.api.SearchHandle;
    import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
    import org.cobblestonemc.paper.api.CobblestoneCoreApi;

    SearchHandle<Location, MinecraftStepPayload> handle =
        CobblestoneCoreApi.navigationService().navigatePlayer(player, destination);

    handle.future().thenAccept(result -> {
      switch (result) {
        case NavigationResult.Success<Location, MinecraftStepPayload> success -> {
          Path<Location, MinecraftStepPayload> path = success.path();
          log("{} steps, about {}s", path.steps().size(), Math.round(path.duration()));
        }
        case NavigationResult.Failure<Location, MinecraftStepPayload> failure ->
            log("no path: {}", failure.reason());
        case NavigationResult.Error<Location, MinecraftStepPayload> error ->
            log.warn("search failed", error.throwable());
      }
    });
    ```

=== "Sponge"

    ```java
    import org.cobblestonemc.api.NavigationResult;
    import org.cobblestonemc.api.SearchHandle;
    import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
    import org.cobblestonemc.sponge12.api.CobblestoneCoreApi;

    SearchHandle<ServerLocation, MinecraftStepPayload> handle =
        CobblestoneCoreApi.navigationService().navigatePlayer(player, destination);

    handle.future().thenAccept(result -> {
      switch (result) {
        case NavigationResult.Success<ServerLocation, MinecraftStepPayload> success -> {
          Path<ServerLocation, MinecraftStepPayload> path = success.path();
          log("{} steps, about {}s", path.steps().size(), Math.round(path.duration()));
        }
        case NavigationResult.Failure<ServerLocation, MinecraftStepPayload> failure ->
            log("no path: {}", failure.reason());
        case NavigationResult.Error<ServerLocation, MinecraftStepPayload> error ->
            log.warn("search failed", error.throwable());
      }
    });
    ```

The search is always **from the player**, because where a player can go depends on the player:
their world, their permissions, whether they may fly, what other plugins say about the ground in
front of them.

### To a region instead of a point

When "anywhere in this box will do" — a town, an arena, a building — give two opposite corners:

```java
navigationService.navigatePlayerToRegion(player, corner1, corner2);
```

The search heads for the cheapest reachable cell of that box rather than one exact block, which is
both friendlier and faster.

## Cancel

```java
handle.cancel();
```

Idempotent. The future completes with `FailureReason.CANCELLED` if the search hadn't already
finished. Always cancel searches you no longer need — they cost CPU until they finish or hit their
limits.

## Settings

`MinecraftSearchSettings` bundles the algorithm limits with the Minecraft-specific exclusions:

```java
MinecraftSearchSettings settings = new MinecraftSearchSettings(
    SearchSettings.builder()
        .maxCellsVisited(50_000)      // cheaper than the server default
        .maxWallClockMillis(5_000L)
        .heuristicWeight(2.0)         // faster, slightly suboptimal
        .build(),
    Set.of(MinecraftStepType.MINE),   // don't route through digging
    Set.of(),                         // excluded world keys
    Set.of("the_nether"));            // excluded dimensions

navigationService.navigatePlayer(player, destination, settings);
```

`MinecraftSearchSettings.defaults()` uses the library defaults — note that these are *not* the
admin's `config.yml` values, which Cobblestone's own commands apply. A short, bounded search of
your own (the sort you'd run to answer "is this reachable?") should set its own smaller limits
rather than inherit a 60-second budget.

| Setting | Default | Meaning |
| --- | --- | --- |
| `maxCellsVisited` | 200 000 | memory guard; a few hundred bytes per cell |
| `maxWallClockMillis` | 60 000 | total time budget |
| `heuristicWeight` | 1.5 | 1.0 is optimal and slow; higher is faster and bounded-suboptimal |
| `tier1UnsolvedPessimism` | 1.5 | margin on route legs not yet solved |
| `runningAverageWidth` | 5 | heuristic smoothing window |

## Reading a path

```java
record Path<P, T>(P origin, List<Step<P, T>> steps) { }
record Step<P, T>(P position, double cost, double time, T payload) { }
```

- **`origin`** — where the player was when the search ran.
- **`steps`** — every position reached, in order. The last one is the destination.
- **`cost` / `time`** — per step, not cumulative. `path.cost()` and `path.duration()` sum them, so
  cache the result rather than calling them in a loop.
- **`payload`** — a `MinecraftStepPayload`: the `MinecraftStepType` (`WALK`, `SWIM`, `FLY`, `MINE`,
  `FALL`, `CLIMB`, `BOAT`, `HORSE`, `OPEN_DOOR`, `PLACE_BOAT`, `MOUNT_HORSE`, `TELEPORT`) plus a
  `MinecraftInstruction` for the few steps that carry one.

A path can cross worlds: a `TELEPORT` step is where it goes through a portal, a pad, or a command.
Don't assume every step is in the same world as the one before it.

## Threading

- `navigatePlayer` is called from the server thread and returns immediately.
- The future completes on a Cobblestone worker thread. Schedule back before touching server state.
- Cobblestone reads world data on the threads the platform requires, so you don't have to
  pre-load anything.

## Failure reasons

| Reason | Meaning |
| --- | --- |
| `NO_ROUTE` | Nothing connects the origin to the destination. |
| `DESTINATION_UNREACHABLE` | A route exists at the graph level, but the destination itself couldn't be reached. |
| `LIMIT_EXCEEDED` | Ran out of cells before finding a path. |
| `TIMED_OUT` | Ran out of wall-clock time. |
| `CANCELLED` | Someone called `cancel()`, or the player logged off. |

`LIMIT_EXCEEDED` and `TIMED_OUT` mean *"not with this budget"*, not *"impossible"* — worth
distinguishing in whatever you tell the player.
