/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.Position;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.ScheduledTaskHandle;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.message.CobblestoneColors;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.navigator.TrailBlock;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.cobblestonemc.plugin.navigator.Vec3;
import org.cobblestonemc.plugin.trip.TestTripAgent;
import org.cobblestonemc.plugin.trip.Trip;
import org.cobblestonemc.plugin.trip.TripLabel;
import org.cobblestonemc.plugin.trip.TripManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When {@link TrailLabelManager} shows, moves and removes a player's trail labels, and what they
 * say.
 */
class TrailLabelManagerTest {

  private static final CobblestoneLogger LOGGER =
      new CobblestoneLogger() {
        @Override
        public void trace(String message, Object... args) {}

        @Override
        public void debug(String message, Object... args) {}

        @Override
        public void info(String message, Object... args) {}

        @Override
        public void warn(String message, Object... args) {}

        @Override
        public void error(String message, Throwable throwable, Object... args) {}
      };

  private static final Vec3 EYE = new Vec3(0.5, 1.62, 0.5);
  private static final Vec3 AHEAD = new Vec3(3.0, 0.9, 0.5);
  private static final Vec3 OVER = AHEAD.plus(new Vec3(0, HologramPlacement.HEIGHT, 0));

  private final TestScheduler scheduler = new TestScheduler();
  private final FakePlatform platform = new FakePlatform();
  private final List<Component> sent = new ArrayList<>();
  private final TestTripAgent player =
      new TestTripAgent(
          UUID.randomUUID(),
          new Audience() {
            @Override
            public void sendMessage(Component message) {
              sent.add(message);
            }
          });

  private TripManager<Object, TestTripAgent, Vec3> trips;
  private TrailLabelManager<Object, TestTripAgent, Vec3> labels;

  @BeforeEach
  void setUp() {
    trips = new TripManager<>(scheduler, 5);
    labels =
        new TrailLabelManager<>(
            trips, scheduler, new Messages(Locale.ENGLISH, false, LOGGER), platform);
    platform.lookAt(OVER);
  }

  /** Starts a trip along a trail through {@code ahead}, and draws the player's labels. */
  private Trip<Object, TestTripAgent, Vec3> start(TripLabel label, Vec3 ahead) {
    Trip<Object, TestTripAgent, Vec3> trip = start(label, new AheadNavigator(ahead));
    scheduler.tick();
    return trip;
  }

  private Trip<Object, TestTripAgent, Vec3> start(TripLabel label, AheadNavigator navigator) {
    return trips.start(player, navigator, label, null, null, false, 0L).orElseThrow();
  }

  private static TripLabel address(String... keys) {
    return TripLabel.address(List.of(keys));
  }

  @Test
  void showsTheTripsNameOverTheTrailWhileThePlayerLooksThere() {
    start(address("essentials", "player", "Steve"), AHEAD);

    FakeHologram label = platform.only();
    assertEquals(OVER, label.at);
    assertEquals("Steve", plain(label.text));
    assertEquals(CobblestoneColors.SECONDARY, label.text.color());
    assertEquals(TrailLabelManager.PERIOD_TICKS, scheduler.period, "follows the player");
  }

  @Test
  void hiddenWhileThePlayerLooksAlongTheTrail() {
    platform.lookAt(AHEAD);
    start(address("player", "Steve"), AHEAD);
    assertTrue(platform.spawned.isEmpty());
  }

  @Test
  void appearsAndDisappearsWithSomeSlack() {
    platform.lookAt(AHEAD);
    start(address("player", "Steve"), AHEAD);
    assertTrue(platform.spawned.isEmpty());

    platform.lookAt(OVER);
    scheduler.tick();
    FakeHologram label = platform.only();

    platform.lookDegreesAbove(OVER, 15); // past where it appears, short of where it disappears
    scheduler.tick();
    assertFalse(label.removed);

    platform.lookDegreesAbove(OVER, 22);
    scheduler.tick();
    assertTrue(label.removed);
  }

  @Test
  void theWindowIsWiderThanItIsTall() {
    platform.lookDegreesAside(OVER, 18);
    start(address("player", "Steve"), AHEAD);
    FakeHologram label = platform.only();

    platform.lookDegreesAside(OVER, 28);
    scheduler.tick();
    assertFalse(label.removed);

    platform.lookDegreesAside(OVER, 35);
    scheduler.tick();
    assertTrue(label.removed);
  }

  @Test
  void tripsToTheSameNameAreToldApartAndStacked() {
    start(address("essentials", "player", "Steve"), AHEAD);
    start(address("towny", "town", "Steve"), AHEAD);

    assertEquals(2, platform.spawned.size());
    FakeHologram first = platform.spawned.get(0);
    FakeHologram second = platform.spawned.get(1);
    assertEquals("player Steve", plain(first.text));
    assertEquals("town Steve", plain(second.text));
    assertEquals(OVER, first.at);
    assertEquals(OVER.plus(new Vec3(0, HologramPlacement.LINE_SPACING, 0)), second.at);
  }

  @Test
  void aLabelIsRenamedWhenAnotherTripStartsOrEnds() {
    start(address("essentials", "player", "Steve"), AHEAD);
    FakeHologram player = platform.only();
    assertEquals("Steve", plain(player.text));

    Trip<Object, TestTripAgent, Vec3> town =
        start(address("towny", "town", "Steve"), new Vec3(0.5, 0.9, 3.0));
    assertEquals("player Steve", plain(player.text));

    trips.stop(town);
    scheduler.tick();
    assertEquals("Steve", plain(player.text));
    assertFalse(player.removed);
  }

  @Test
  void followsTheTrail() {
    AheadNavigator navigator = new AheadNavigator(AHEAD);
    start(address("player", "Steve"), navigator);
    scheduler.tick();
    FakeHologram label = platform.only();

    navigator.ahead = Optional.of(AHEAD.plus(new Vec3(0, 0, 0.5)));
    platform.lookAt(label.at.plus(new Vec3(0, 0, 0.5)));
    scheduler.tick();
    assertEquals(OVER.plus(new Vec3(0, 0, 0.5)), label.at);
    assertEquals(1, platform.spawned.size(), "moved, not replaced");
  }

  @Test
  void respawnedWhenItCannotMove() {
    AheadNavigator navigator = new AheadNavigator(AHEAD);
    start(address("player", "Steve"), navigator);
    scheduler.tick();
    FakeHologram label = platform.only();
    label.movable = false;

    navigator.ahead = Optional.of(AHEAD.plus(new Vec3(0, 0, 0.5)));
    scheduler.tick();
    assertTrue(label.removed);
    assertEquals(2, platform.spawned.size());
    assertEquals(OVER.plus(new Vec3(0, 0, 0.5)), platform.spawned.getLast().at);
  }

  @Test
  void removedWhenTheTrailAheadIsGone() {
    AheadNavigator navigator = new AheadNavigator(AHEAD);
    start(address("player", "Steve"), navigator);
    scheduler.tick();
    navigator.ahead = Optional.empty();
    scheduler.tick();
    assertTrue(platform.only().removed);
  }

  @Test
  void notShownOutOfSight() {
    platform.probe = (x, y, z) -> x == 2 && y >= 2 ? TrailBlock.SOLID : TrailBlock.OPEN;
    start(address("player", "Steve"), AHEAD);
    assertTrue(platform.spawned.isEmpty());
  }

  @Test
  void hittingTheLabelCancelsItsTrip() {
    start(address("essentials", "player", "Steve"), AHEAD);
    Trip<Object, TestTripAgent, Vec3> town = start(address("towny", "town", "Steve"), AHEAD);

    platform.spawned.get(1).onHit.run();

    assertEquals(1, trips.trips(player.uuid()).size());
    assertFalse(trips.trips(player.uuid()).contains(town));
    assertTrue(platform.spawned.get(1).removed);
    assertFalse(platform.spawned.get(0).removed);
    assertEquals(
        List.of("Canceled your trip to towny town Steve."),
        sent.stream().map(TrailLabelManagerTest::plain).toList());

    platform.spawned.get(1).onHit.run(); // a second hit before it is gone does nothing
    assertEquals(1, sent.size());
  }

  @Test
  void removedWithTheLastTrip() {
    Trip<Object, TestTripAgent, Vec3> trip = start(address("player", "Steve"), AHEAD);
    trips.stop(trip);
    assertTrue(platform.only().removed);
    assertTrue(scheduler.handle.cancelled);
  }

  @Test
  void removedOnLogout() {
    start(address("player", "Steve"), AHEAD);
    trips.stopAll(player.uuid());
    assertTrue(platform.only().removed);
    assertTrue(scheduler.handle.cancelled);
  }

  @Test
  void navigatorsThatDontAttachGetNoLabel() {
    trips.start(player, new PlainNavigator(), address("player", "Steve"), null, null, false, 0L);
    assertEquals(null, scheduler.ticker, "nothing to update");
    assertTrue(platform.spawned.isEmpty());
  }

  @Test
  void aNavigatorWithoutATripIsIgnored() {
    new AheadNavigator(AHEAD).start();
    assertEquals(null, scheduler.ticker);
  }

  /** The text of a component tree, without styling. */
  private static String plain(Component component) {
    StringBuilder out = new StringBuilder();
    if (component instanceof TextComponent text) {
      out.append(text.content());
    }
    component.children().forEach(child -> out.append(plain(child)));
    return out.toString();
  }

  /** A player at the origin over a flat floor, looking wherever the test says. */
  private static final class FakePlatform implements HologramPlatform<TestTripAgent, Vec3> {
    final List<FakeHologram> spawned = new ArrayList<>();
    TrailSmoother.BlockProbe probe = (x, y, z) -> y < 0 ? TrailBlock.SOLID : TrailBlock.OPEN;
    Vec3 look;

    void lookAt(Vec3 target) {
      look = target.minus(EYE).unit();
    }

    /** Looks toward {@code target}, tilted up by {@code degrees}. */
    void lookDegreesAbove(Vec3 target, double degrees) {
      Vec3 toward = target.minus(EYE);
      double horizontal = Math.hypot(toward.x(), toward.z());
      double pitch = Math.atan2(toward.y(), horizontal) + Math.toRadians(degrees);
      look =
          new Vec3(
              toward.x() / horizontal * Math.cos(pitch),
              Math.sin(pitch),
              toward.z() / horizontal * Math.cos(pitch));
    }

    /** Looks level with {@code target}, turned {@code degrees} to its side. */
    void lookDegreesAside(Vec3 target, double degrees) {
      Vec3 toward = target.minus(EYE);
      double pitch = Math.atan2(toward.y(), Math.hypot(toward.x(), toward.z()));
      double yaw = Math.atan2(toward.z(), toward.x()) + Math.toRadians(degrees);
      look =
          new Vec3(
              Math.cos(yaw) * Math.cos(pitch), Math.sin(pitch), Math.sin(yaw) * Math.cos(pitch));
    }

    @Override
    public Vec3 point(Vec3 location) {
      return location;
    }

    FakeHologram only() {
      assertEquals(1, spawned.size());
      return spawned.getFirst();
    }

    @Override
    public Optional<Gaze> gaze(TestTripAgent agent) {
      return Optional.of(new Gaze(EYE, look));
    }

    @Override
    public TrailSmoother.BlockProbe probe(TestTripAgent agent) {
      return probe;
    }

    @Override
    public Optional<Hologram> spawn(TestTripAgent agent, Vec3 at, Component text, Runnable onHit) {
      FakeHologram hologram = new FakeHologram(at, text, onHit);
      spawned.add(hologram);
      return Optional.of(hologram);
    }
  }

  private static final class FakeHologram implements Hologram {
    Vec3 at;
    Component text;
    final Runnable onHit;
    boolean removed;
    boolean movable = true;

    FakeHologram(Vec3 at, Component text, Runnable onHit) {
      this.at = at;
      this.text = text;
      this.onHit = onHit;
    }

    @Override
    public boolean move(Vec3 at) {
      if (!movable) {
        return false;
      }
      this.at = at;
      return true;
    }

    @Override
    public void text(Component text) {
      this.text = text;
    }

    @Override
    public void remove() {
      removed = true;
    }
  }

  /** A navigator that never completes. */
  private static class PlainNavigator implements Navigator<Vec3> {
    @Override
    public void start() {}

    @Override
    public void tick() {}

    @Override
    public void update(Path<Vec3, MinecraftStepPayload> newPath) {}

    @Override
    public void stop() {}

    @Override
    public boolean isComplete() {
      return false;
    }

    @Override
    public double remainingSeconds() {
      return 0;
    }
  }

  /** A trail navigator whose trail passes a settable point ahead of the player. */
  private final class AheadNavigator extends PlainNavigator {
    Optional<Vec3> ahead;

    AheadNavigator(Vec3 ahead) {
      this.ahead = Optional.of(ahead);
    }

    @Override
    public void start() {
      labels.attach(player.uuid(), this, distance -> ahead.map(point -> point));
    }

    @Override
    public void stop() {
      labels.detach(player.uuid(), this);
    }
  }

  /** Runs one-shot work immediately and keeps the labels' repeating task for the test to fire. */
  private static final class TestScheduler implements MinecraftScheduler<Object> {
    Runnable ticker;
    long period;
    Handle handle;

    void tick() {
      ticker.run();
    }

    @Override
    public void runAtEntity(Object entity, Runnable task) {
      task.run();
    }

    @Override
    public ScheduledTaskHandle runAtEntityRepeating(
        Object entity, Runnable task, long periodTicks) {
      Handle created = new Handle();
      if (periodTicks != 1L) { // trip ticks (period 1) are not needed here
        ticker = task;
        period = periodTicks;
        handle = created;
      }
      return created;
    }

    @Override
    public void runAtPosition(Position<? extends MinecraftWorld> position, Runnable task) {
      task.run();
    }

    @Override
    public ScheduledTaskHandle runAtPositionRepeating(
        Position<? extends MinecraftWorld> position, Runnable task, long periodTicks) {
      return new Handle();
    }

    @Override
    public void runGlobal(Runnable task) {
      task.run();
    }

    @Override
    public void runAsync(Runnable task) {
      task.run();
    }

    @Override
    public void runAsyncLater(Runnable task, long delayMillis) {}

    @Override
    public ExecutorService asyncExecutor() {
      throw new UnsupportedOperationException();
    }
  }

  private static final class Handle implements ScheduledTaskHandle {
    boolean cancelled;

    @Override
    public void cancel() {
      cancelled = true;
    }
  }
}
