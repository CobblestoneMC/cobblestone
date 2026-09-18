---
title: Navigators
description: Draw a route your own way — a compass, a hologram, an NPC that walks ahead.
---

# Navigators

A **navigator** is how a trip is shown to the player. Cobblestone ships one, `trail`, which draws a
ribbon of particles along the path. Register your own and players select it with
`/navigate <destination> -navigator <id>`, or your plugin picks it when it starts a trip.

## The contract

```java
public interface Navigator<L> {
  void start();                                   // the trip begins
  void tick();                                    // every server tick: render, advance
  void update(Path<L, MinecraftStepPayload> p);   // a re-search replaced the path
  void stop();                                    // arrival, cancellation, or logout
  boolean isComplete();                           // has the player arrived?
  double remainingSeconds();                      // for /cobblestone trips

  // Optional:
  default boolean consumeRecalcRequest();              // "I've lost them, re-search"
  default Optional<L> consumeGuideRequest();           // "give me a short path back to the trail"
  default void setGuidePath(Path<L, MinecraftStepPayload> guide);
}
```

`tick()` is called **every server tick** while the trip lives, on the scheduler Cobblestone uses for
that player (a region task on Folia). Keep it cheap and don't block.

`isComplete()` returning `true` ends the trip. Cobblestone does not decide arrival for you —
"close enough" is a display decision, and the built-in trail uses two blocks.

## A minimal navigator

An action-bar compass: no particles, just a bearing and a distance.

=== "Paper"

    ```java
    final class CompassNavigator implements Navigator<Location> {
      private final Player player;
      private Path<Location, MinecraftStepPayload> path;
      private int step;

      CompassNavigator(Player player, Path<Location, MinecraftStepPayload> path) {
        this.player = player;
        this.path = path;
      }

      @Override public void start() { step = 0; }

      @Override public void tick() {
        if (isComplete() || !player.isOnline()) {
          return;
        }
        Location target = path.steps().get(step).position();
        if (player.getLocation().distanceSquared(target) < 4) {
          step++;
          return;
        }
        player.sendActionBar(Component.text(bearing(player.getLocation(), target)));
      }

      @Override public void update(Path<Location, MinecraftStepPayload> newPath) {
        this.path = newPath;
        this.step = 0;
      }

      @Override public void stop() {
        player.sendActionBar(Component.empty());
      }

      @Override public boolean isComplete() {
        return step >= path.steps().size();
      }

      @Override public double remainingSeconds() {
        double total = 0;
        for (int i = step; i < path.steps().size(); i++) {
          total += path.steps().get(i).time();
        }
        return total;
      }
    }
    ```

=== "Sponge"

    ```java
    final class CompassNavigator implements Navigator<ServerLocation> {
      private final ServerPlayer player;
      private Path<ServerLocation, MinecraftStepPayload> path;
      private int step;

      CompassNavigator(ServerPlayer player, Path<ServerLocation, MinecraftStepPayload> path) {
        this.player = player;
        this.path = path;
      }

      @Override public void start() { step = 0; }

      @Override public void tick() {
        if (isComplete() || !player.isOnline()) {
          return;
        }
        ServerLocation target = path.steps().get(step).position();
        if (player.position().distanceSquared(target.position()) < 4) {
          step++;
          return;
        }
        player.sendActionBar(Component.text(bearing(player.serverLocation(), target)));
      }

      @Override public void update(Path<ServerLocation, MinecraftStepPayload> newPath) {
        this.path = newPath;
        this.step = 0;
      }

      @Override public void stop() {
        player.sendActionBar(Component.empty());
      }

      @Override public boolean isComplete() {
        return step >= path.steps().size();
      }

      @Override public double remainingSeconds() {
        double total = 0;
        for (int i = step; i < path.steps().size(); i++) {
          total += path.steps().get(i).time();
        }
        return total;
      }
    }
    ```

## Register it

=== "Paper"

    ```java
    CobblestonePaperApi.registrar().registerNavigator(this, "compass",
        (player, path, settings) -> new CompassNavigator(player, path));
    ```

=== "Sponge"

    ```java
    CobblestonePluginApi.registrar().registerNavigator(container, "compass",
        (player, path, settings) -> new CompassNavigator(player, path));
    ```

Ids are lower-case and first registration wins, so pick something specific. The node
`cobblestone.navigator.compass` gates it (default allow), and everything you registered is dropped
when your plugin disables.

## Settings

The `NavigatorSettings` handed to your factory carries per-trip overrides. Declare typed keys for
whatever your navigator understands:

```java
public final class CompassSettings {
  public static final String NAVIGATOR_ID = "compass";

  public static final NavigatorSettingKey<Boolean> SHOW_DISTANCE =
      new NavigatorSettingKey<>("compass.show_distance");

  public static NavigatorSettings showingDistance(boolean show) {
    return NavigatorSettings.builder(NAVIGATOR_ID).set(SHOW_DISTANCE, show).build();
  }
}
```

Read them in the factory, falling back to your own config when unset — that's the contract the
built-in trail follows:

```java
boolean showDistance = settings.get(CompassSettings.SHOW_DISTANCE).orElse(config.showDistance());
```

## Reading the path

A `Path` is an origin plus an ordered list of `Step`s. Each step carries the position reached, its
`cost` and `time` in seconds, and a `MinecraftStepPayload`:

```java
for (Step<Location, MinecraftStepPayload> step : path.steps()) {
  MinecraftStepType type = step.payload().stepType();   // WALK, SWIM, FALL, BOAT, TELEPORT…
  MinecraftInstruction instruction = step.payload().instruction();
}
```

`path.duration()` is the estimated real travel time, `path.cost()` the metric the search minimized.

### Prompting on action steps

Some step types are things the player must *do* rather than walk: `OPEN_DOOR`, `PLACE_BOAT`,
`MOUNT_HORSE`, `TELEPORT`. `MinecraftStepType#isAction()` tells you which, and a step may also carry
an instruction:

```java
if (step.payload().instruction()
    instanceof MinecraftInstruction.CommandInstruction(String command)) {
  // e.g. "/warp market" — make it clickable rather than making them type it.
  player.sendMessage(Component.text("Run " + command)
      .clickEvent(ClickEvent.runCommand(command)));
}
```

A navigator that silently walks a player into a step they have to trigger themselves will look
broken. Say something.

## Straying (optional)

Two optional hooks let a navigator ask the trip for help when the player wanders off:

- **`consumeRecalcRequest()`** — return `true` and the trip runs a fresh search from wherever the
  player now is, then calls your `update(newPath)`. The built-in trail does this once per second at
  most, past a configured distance.
- **`consumeGuideRequest()`** — return a target and Cobblestone computes a short path from the
  player back to it, handing it to `setGuidePath`. That's how the trail draws a real route back to
  itself rather than a straight line through a wall.

Both default to "never", so ignore them until you want them.
