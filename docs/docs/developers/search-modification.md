---
title: Search modification
description: Teach the search new routes, and constrain where it may dig or walk.
---

# Search modification

A `SearchModificationService` is the single hook for changing what a search may do. It does three
independent things, all optional:

| Method | Answers |
| --- | --- |
| `computeTransitions` | "There are routes here pathfinding can't see." |
| `computeBreakChecker` | "This player may not break *that*." |
| `computePassChecker` | "This player may not enter *there*." |

Register one and Cobblestone folds it into every search:

=== "Paper"

    ```java
    import org.cobblestonemc.paper.api.CobblestoneCoreApi;

    CobblestoneCoreApi.registrar().register(this, new WarpModifications(store));
    ```

=== "Sponge"

    ```java
    import org.cobblestonemc.sponge12.api.CobblestoneCoreApi;

    CobblestoneCoreApi.registrar().register(container, new WarpModifications(store));
    ```

As with destinations, the owner you pass is what the registration is dropped with when that plugin
disables.

!!! info "Threading, briefly"

    The three `compute*` methods run **once per search**, on the thread that starts it (normally the
    main thread), so reading server state in them is safe. The checkers they return run **during**
    the search, possibly off the main thread, and answer with futures. Complete the future straight
    away when you can — that keeps the search on its fast path.

---

## Transitions: new ways to travel

A `Transition` is an edge in the graph that isn't walking: **from anywhere in this region, you can
get to this exact location, for this cost**.

```java
Transition.of(origin, destination, costSeconds, payload);
Transition.of(origin, destination, costSeconds, timeSeconds, payload);
Transition.command(player, destination, costSeconds, "/warp market");
```

- **`origin`** is a `WorldRegion` — `SingleCellWorldRegion.of(loc)`, `BoxWorldRegion.of(a, b)`,
  `BoxWorldRegion.around(centre, radius)`, or `WholeWorldRegion.of(world)`.
- **`cost`** is what the search minimizes, in seconds; **`time`** is what the player is told. They
  differ when you want to discourage a route without lying about how long it takes — a
  30-second-cost hop that really takes 3 seconds will be used only when it genuinely helps.
- **`payload`** says what kind of step it is: `MinecraftStepPayload.portal()` for "walk in and you
  are teleported", `MinecraftStepPayload.command("/warp market")` for "run this", or
  `MinecraftStepPayload.of(type)`.

### Two shapes that cover most cases

A **command warp** works from anywhere, so its origin is the whole world the player is in — the
search can take it immediately and prompt the player to type it:

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

    1. Offer a teleport only when the player could actually run it. Cobblestone gates
       *navigation*, never *teleportation* — that stays your permission to check.

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

    1. Offer a teleport only when the player could actually run it. Cobblestone gates
       *navigation*, never *teleportation* — that stays your permission to check.

A **portal pad** teleports whoever walks into it, so its origin is the pad's box and the player is
routed to walk in:

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

!!! tip "Skip what isn't usable"

    Worlds unload, destinations get deleted, permissions change. Returning fewer transitions is
    always safe — just leave out the ones that can't be taken right now, as the built-in
    integrations do.

---

## BreakChecker: what may be dug through

Cobblestone can route a player through blocks they're able to mine. A break checker constrains that
— this is how the Towny integration keeps routes out of other people's land.

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

    The block is supplied lazily (`Supplier<BlockData>`) — don't call it unless your decision
    actually depends on the block type, because reading it may cost a chunk lookup.

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

If your answer needs the main thread — because it fires an event, as Towny's does — schedule it and
complete the future from there:

```java
return (p, location, block) -> {
  CompletableFuture<Boolean> answer = new CompletableFuture<>();
  scheduler.runOnMainThread(() -> answer.complete(protection.canDestroy(p, location)));
  return answer;
};
```

And cache. A single search asks thousands of times, usually about the same few plots and materials;
every built-in integration that does main-thread checks memoizes per search.

---

## PassChecker: where the player may go at all

Same shape, coarser question — may this player enter this cell?

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

Blocking a region here doesn't just hide it — the search genuinely won't route through it, so a
player is never told to walk somewhere they'd be bounced out of.

`BreakChecker.ALLOW` and `PassChecker.ALLOW` are the defaults; override only the method you care
about.

---

## A complete example

The repository ships a working example plugin,
[`examples/paper-warps`](https://github.com/CobblestoneMC/cobblestone/tree/main/project/examples/paper-warps):
named warps reachable with `/warp`, plus portal pads that teleport on entry, both surfaced to
Cobblestone by a single `SearchModificationService`. It compiles against the published API only and
is about as small as a real integration gets.
