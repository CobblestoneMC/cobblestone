---
title: Navigators
description: Implement custom route rendering.
---

# Navigators

A **navigator** renders a trip. The built-in `trail` navigator draws particles along the path.
Players select a navigator with `-navigator <id>`; plugins select one when starting a trip.

## Interface

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

`tick()` runs every server tick on the player's scheduler (a region task on Folia). It must not
block.

The trip ends when `isComplete()` returns `true`. Arrival is determined by the navigator; the
built-in trail uses a two-block radius.

## Example

An action-bar compass that displays a bearing:

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

## Registration

=== "Paper"

    ```java
    CobblestonePaperApi.registrar().registerNavigator(this, "compass",
        (player, path, settings) -> new CompassNavigator(player, path));
    ```

=== "Sponge"

    ```java
    CobblestoneSpongeApi.registrar().registerNavigator(container, "compass",
        (player, path, settings) -> new CompassNavigator(player, path));
    ```

Ids are lower-case. The first registration of an id takes precedence. The navigator is gated by
`cobblestone.navigator.<id>` (default allow) and removed when the owner disables.

## Settings

`NavigatorSettings` carries per-trip overrides. Declare typed keys for the settings your navigator
supports:

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

Read them in the factory, falling back to configuration when unset:

```java
boolean showDistance = settings.get(CompassSettings.SHOW_DISTANCE).orElse(config.showDistance());
```

## Reading the path

A `Path` is an origin and an ordered list of `Step`s. Each step has a position, `cost` and `time` in
seconds, and a `MinecraftStepPayload`:

```java
for (Step<Location, MinecraftStepPayload> step : path.steps()) {
  MinecraftStepType type = step.payload().stepType();   // WALK, SWIM, FALL, BOAT, TELEPORT…
  MinecraftInstruction instruction = step.payload().instruction();
}
```

`path.duration()` is the estimated travel time; `path.cost()` is the value minimized by the search.

### Action steps

`OPEN_DOOR`, `PLACE_BOAT`, `MOUNT_HORSE`, and `TELEPORT` require player action.
`MinecraftStepType#isAction()` identifies them. A step may also carry an instruction:

```java
if (step.payload().instruction()
    instanceof MinecraftInstruction.CommandInstruction(String command)) {
  // e.g. "/warp market" — make it clickable rather than making them type it.
  player.sendMessage(Component.text("Run " + command)
      .clickEvent(ClickEvent.runCommand(command)));
}
```

Navigators should notify the player at action steps.

## Straying

Two optional hooks handle players who leave the route:

- **`consumeRecalcRequest()`**: Return `true` to re-search from the player's position. The new
  path is passed to `update`.
- **`consumeGuideRequest()`**: Return a target to compute a short path from the player to it. The
  result is passed to `setGuidePath`.

Both default to no request.
