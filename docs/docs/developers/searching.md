---
title: Searching
description: Run a search and read the resulting path.
---

# Searching

`NavigationService` computes a path from a player to a destination. Use it to measure distance,
test reachability, or render a path yourself. To guide a player, use [Trips](trips.md) instead.

## Running a search

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
    import org.cobblestonemc.sponge.api.CobblestoneCoreApi;

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

Searches always originate at a player, since traversability depends on the player's world,
permissions, and abilities.

### Regions

To target any cell within a box, pass two opposite corners:

```java
navigationService.navigatePlayerToRegion(player, corner1, corner2);
```

The search targets the cheapest reachable cell in the box.

## Cancellation

```java
handle.cancel();
```

Idempotent. If the search is still running, the future completes with `FailureReason.CANCELLED`.
Cancel searches that are no longer needed.

## Settings

`MinecraftSearchSettings` combines algorithm limits with Minecraft-specific exclusions:

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

`MinecraftSearchSettings.defaults()` uses library defaults, not the values in `config.yml`. Short
queries, such as reachability checks, should set smaller limits.

| Setting | Default | Meaning |
| --- | --- | --- |
| `maxCellsVisited` | 200 000 | Cell limit; a few hundred bytes per cell |
| `maxWallClockMillis` | 60 000 | Time limit |
| `heuristicWeight` | 1.5 | `1.0` is optimal; higher is faster and bounded-suboptimal |
| `tier1UnsolvedPessimism` | 1.5 | Cost multiplier for unsolved route legs |
| `runningAverageWidth` | 5 | Heuristic smoothing window |

## Paths

```java
record Path<P, T>(P origin, List<Step<P, T>> steps) { }
record Step<P, T>(P position, double cost, double time, T payload) { }
```

- **`origin`**: The player's position when the search ran.
- **`steps`**: Positions in order. The last is the destination.
- **`cost`, `time`**: Per step, not cumulative. `path.cost()` and `path.duration()` compute sums;
  cache the results.
- **`payload`**: A `MinecraftStepPayload` containing the `MinecraftStepType` (`WALK`, `SWIM`, `FLY`,
  `MINE`, `FALL`, `CLIMB`, `BOAT`, `HORSE`, `OPEN_DOOR`, `PLACE_BOAT`, `MOUNT_HORSE`, `TELEPORT`)
  and an optional `MinecraftInstruction`.

Paths may cross worlds at `TELEPORT` steps.

## Threading

- Call `navigatePlayer` from the server thread. It returns immediately.
- The future completes on a Cobblestone worker thread.
- World data is read on the threads the platform requires; no preloading is needed.

## Failure reasons

| Reason | Meaning |
| --- | --- |
| `NO_ROUTE` | No route connects the origin to the destination. |
| `DESTINATION_UNREACHABLE` | The destination itself is not reachable. |
| `LIMIT_EXCEEDED` | The cell limit was reached. |
| `TIMED_OUT` | The time limit was reached. |
| `CANCELLED` | `cancel()` was called, or the player disconnected. |

`LIMIT_EXCEEDED` and `TIMED_OUT` indicate that no route was found within the limits, not that none
exists.
