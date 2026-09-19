---
title: Search modification
description: Add transitions and restrict breaking and movement.
---

# Search modification

A `SearchModificationService` modifies searches through three optional methods:

| Method | Purpose |
| --- | --- |
| `computeTransitions` | Adds edges, such as teleports. |
| `computeBreakChecker` | Restricts which blocks may be broken. |
| `computePassChecker` | Restricts which cells may be entered. |

Registered services apply to every search:

=== "Paper"

    ```java
    import org.cobblestonemc.paper.api.CobblestoneCoreApi;

    CobblestoneCoreApi.registrar().register(this, new WarpModifications(store));
    ```

=== "Sponge"

    ```java
    import org.cobblestonemc.sponge.api.CobblestoneCoreApi;

    CobblestoneCoreApi.registrar().register(container, new WarpModifications(store));
    ```

The registration is removed when the owner disables.

The `compute*` methods run once per search on the calling thread, normally the main thread. The
returned checkers run during the search, possibly off the main thread. See
[Threading](index.md#threading).

---

## Transitions

A `Transition` is an edge from any cell in an origin region to a destination location, at a given
cost.

```java
Transition.of(origin, destination, costSeconds, payload);
Transition.of(origin, destination, costSeconds, timeSeconds, payload);
Transition.command(player, destination, costSeconds, "/warp market");
```

- **`origin`**: A `WorldRegion`: `SingleCellWorldRegion.of(loc)`, `BoxWorldRegion.of(a, b)`,
  `BoxWorldRegion.around(center, radius)`, or `WholeWorldRegion.of(world)`.
- **`cost`**: The value minimized by the search, in seconds. **`time`**: The travel time reported to
  the player. Set `cost` above `time` to discourage a transition without misreporting its duration.
- **`payload`**: The step type: `MinecraftStepPayload.portal()`,
  `MinecraftStepPayload.command("/warp market")`, or `MinecraftStepPayload.of(type)`.

### Command transitions

A command can be run from anywhere, so its origin is the player's entire world:

=== "Paper"

    ``` { .java .annotate }
    @Override
    public CompletableFuture<List<Transition>> computeTransitions(Player player) {
      List<Transition> transitions = new ArrayList<>();
      for (Warp warp : store.warps()) {
        if (!player.hasPermission("warps.use." + warp.name())) {
          continue;                                   // (1)!
        }
        transitions.add(Transition.command(
            player, warp.location(), 3.0, "/warp " + warp.name()));
      }
      return CompletableFuture.completedFuture(transitions);
    }
    ```

    1. Offer a teleport only if the player may use it. Cobblestone does not check teleport
       permissions.

=== "Sponge"

    ``` { .java .annotate }
    @Override
    public CompletableFuture<List<Transition>> computeTransitions(ServerPlayer player) {
      List<Transition> transitions = new ArrayList<>();
      for (Warp warp : store.warps()) {
        if (!player.hasPermission("warps.use." + warp.name())) {
          continue;                                   // (1)!
        }
        transitions.add(Transition.command(
            player, warp.location(), 3.0, "/warp " + warp.name()));
      }
      return CompletableFuture.completedFuture(transitions);
    }
    ```

    1. Offer a teleport only if the player may use it. Cobblestone does not check teleport
       permissions.

### Portal transitions

A portal pad teleports players on entry, so its origin is the pad's bounding box:

=== "Paper"

    ```java
    BoxWorldRegion pad = BoxWorldRegion.of(padCorner1, padCorner2);
    transitions.add(Transition.of(pad, exitLocation, 1.0, MinecraftStepPayload.portal()));
    ```

=== "Sponge"

    ```java
    BoxWorldRegion pad = BoxWorldRegion.of(padCorner1, padCorner2);
    transitions.add(Transition.of(pad, exitLocation, 1.0, MinecraftStepPayload.portal()));
    ```

Omit transitions that are currently unusable, such as those into unloaded worlds.

---

## Break checkers

Routes may mine through blocks. A break checker restricts which blocks may be broken; the Towny
integration uses one to enforce build protection.

=== "Paper"

    ```java
    @Override
    public BreakChecker computeBreakChecker(Player player) {
      // Called once per search: capture what you need here, on the main thread.
      RegionQuery query = worldGuard.createQuery();
      return (p, location, block) -> {
        // Runs during the search, possibly off-thread. Answer without blocking.
        return CompletableFuture.completedFuture(query.testBuild(location, p));
      };
    }
    ```

    The block is supplied lazily (`Supplier<BlockData>`). Call it only if needed; it may require a
    chunk lookup.

=== "Sponge"

    ```java
    @Override
    public BreakChecker computeBreakChecker(ServerPlayer player) {
      // Called once per search: capture what you need here, on the main thread.
      var claims = claimService.snapshotFor(player);
      return (p, location, block) -> {
        // Runs during the search, possibly off-thread. Answer without blocking.
        return CompletableFuture.completedFuture(claims.mayBreak(location, block));
      };
    }
    ```

If the check requires the main thread, schedule it and complete the future there:

```java
return (p, location, block) -> {
  CompletableFuture<Boolean> answer = new CompletableFuture<>();
  scheduler.runOnMainThread(() -> answer.complete(protection.canDestroy(p, location)));
  return answer;
};
```

A search may invoke the checker thousands of times. Cache results per search.

---

## Pass checkers

A pass checker restricts which cells the player may enter.

=== "Paper"

    ```java
    @Override
    public PassChecker computePassChecker(Player player) {
      boolean vip = player.hasPermission("myserver.vip");
      return (p, location) ->
          CompletableFuture.completedFuture(vip || !vipArea.contains(location));
    }
    ```

=== "Sponge"

    ```java
    @Override
    public PassChecker computePassChecker(ServerPlayer player) {
      boolean vip = player.hasPermission("myserver.vip");
      return (p, location) ->
          CompletableFuture.completedFuture(vip || !vipArea.contains(location));
    }
    ```

Searches do not route through denied cells.

`BreakChecker.ALLOW` and `PassChecker.ALLOW` are the defaults.

---

## Example plugin

[`examples/paper-warps`](https://github.com/CobblestoneMC/cobblestone/tree/main/project/examples/paper-warps)
implements `/warp` commands and portal pads with a single `SearchModificationService`, using only the
published API.
