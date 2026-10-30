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
import net.kyori.adventure.text.format.NamedTextColor;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.Position;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.ScheduledTaskHandle;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.api.Navigator;
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

/** When {@link TrailLabelManager} shows and removes a player's trail labels, and what they say. */
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

  private static final Vec3 FEET = new Vec3(0.5, 0.0, 0.5);
  private static final Vec3 AHEAD = new Vec3(3.0, 0.9, 0.5);
  private static final Vec3 OVER = AHEAD.plus(new Vec3(0, HologramPlacement.HEIGHT, 0));
  private static final Vec3 EYE = FEET.plus(new Vec3(0, 1.62, 0));

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
  }

  /** Starts a trip along a trail through {@code ahead}, and stands still until labels show. */
  private Trip<Object, TestTripAgent, Vec3> start(TripLabel label, Vec3 ahead) {
    Trip<Object, TestTripAgent, Vec3> trip = start(label, new AheadNavigator(ahead));
    standStill();
    return trip;
  }

  private Trip<Object, TestTripAgent, Vec3> start(TripLabel label, AheadNavigator navigator) {
    return trips.start(player, navigator, label, null, null, false, 0L).orElseThrow();
  }

  /** Updates the labels as often as a player arriving in a block must wait for them. */
  private void standStill() {
    for (int i = 0; i <= updatesToShow(); i++) {
      scheduler.tick();
    }
  }

  /** How many updates after the one a player arrives in a block on that their labels appear. */
  private static int updatesToShow() {
    return (int) (TrailLabelManager.STILL_TICKS / TrailLabelManager.PERIOD_TICKS);
  }

  private static TripLabel address(String... keys) {
    return TripLabel.address(List.of(keys));
  }

  @Test
  void appearsOnceThePlayerHasStoodStillForASecond() {
    start(address("essentials", "player", "Steve"), new AheadNavigator(AHEAD));
    scheduler.tick(); // arrives
    for (int i = 1; i < updatesToShow(); i++) {
      scheduler.tick();
      assertTrue(platform.spawned.isEmpty(), "not yet, after " + i + " updates");
    }
    scheduler.tick();

    FakeHologram label = platform.only();
    assertEquals(OVER, label.at);
    assertEquals("✦ Steve ✦", plain(label.text));
    assertEquals(20, updatesToShow() * TrailLabelManager.PERIOD_TICKS, "a second");

    FakeHologram cancel = platform.cancels().getFirst();
    assertEquals(OVER.plus(new Vec3(0, HologramPlacement.CANCEL_RISE, 0)), cancel.at, "above");
    assertEquals(NamedTextColor.RED, cancel.text.color());
  }

  @Test
  void goesAwayWhileThePlayerMovesAndComesBackWhenTheyStop() {
    start(address("player", "Steve"), AHEAD);
    FakeHologram first = platform.only();

    platform.feet = FEET.plus(new Vec3(1, 0, 0));
    scheduler.tick();
    assertTrue(first.removed);
    assertTrue(platform.cancels().getFirst().removed);

    platform.feet = FEET.plus(new Vec3(2, 0, 0));
    for (int i = 0; i < updatesToShow(); i++) {
      scheduler.tick();
    }
    assertEquals(1, platform.names().size(), "the wait starts over in each new block");
    scheduler.tick();
    assertEquals(2, platform.names().size());
    assertFalse(platform.names().getLast().removed);
  }

  @Test
  void jumpingCountsAsMoving() {
    start(address("player", "Steve"), AHEAD);
    platform.feet = FEET.plus(new Vec3(0, 1.1, 0));
    scheduler.tick();
    assertTrue(platform.only().removed);
  }

  @Test
  void movingWithinTheBlockDoesNotCount() {
    AheadNavigator navigator = new AheadNavigator(AHEAD);
    start(address("player", "Steve"), navigator);
    standStill();
    FakeHologram label = platform.only();

    platform.feet = FEET.plus(new Vec3(0.2, 0, -0.2));
    navigator.ahead = Optional.of(AHEAD.plus(new Vec3(0.2, 0, 0)));
    scheduler.tick();
    assertFalse(label.removed);
    assertEquals(OVER, label.at, "stays where it appeared");
  }

  @Test
  void appearsAsSoonAsTheTrailHasASpot() {
    AheadNavigator navigator = new AheadNavigator(AHEAD);
    navigator.ahead = Optional.empty(); // e.g. off the trail, before the guide back is found
    start(address("player", "Steve"), navigator);
    standStill();
    assertTrue(platform.spawned.isEmpty());

    navigator.ahead = Optional.of(AHEAD);
    scheduler.tick();
    assertEquals(OVER, platform.only().at);
    assertEquals(1, platform.cancels().size());
  }

  @Test
  void staysWhileTheTrailIsBrieflyGone() {
    AheadNavigator navigator = new AheadNavigator(AHEAD);
    start(address("player", "Steve"), navigator);
    standStill();
    navigator.ahead = Optional.empty(); // e.g. a new guide not drawn yet
    scheduler.tick();
    navigator.ahead = Optional.of(AHEAD);
    scheduler.tick();
    assertFalse(platform.only().removed, "no blink");
  }

  @Test
  void goneWhileThePlayerIsOffline() {
    start(address("player", "Steve"), AHEAD);
    platform.online = false;
    scheduler.tick();
    assertTrue(platform.only().removed);
  }

  @Test
  void tripsToTheSameNameAreToldApartAndStacked() {
    start(address("essentials", "player", "Steve"), AHEAD);
    start(address("towny", "town", "Steve"), AHEAD);

    assertEquals(2, platform.names().size());
    FakeHologram first = platform.names().get(0);
    FakeHologram second = platform.names().get(1);
    assertEquals("✦ player Steve ✦", plain(first.text));
    assertEquals("✦ town Steve ✦", plain(second.text));
    assertEquals(OVER, first.at);
    assertEquals(OVER.plus(new Vec3(0, HologramPlacement.LABEL_SPACING, 0)), second.at);
  }

  @Test
  void aLabelIsRenamedWhenAnotherTripStartsOrEnds() {
    start(address("essentials", "player", "Steve"), AHEAD);
    FakeHologram player = platform.only();
    assertEquals("✦ Steve ✦", plain(player.text));

    Trip<Object, TestTripAgent, Vec3> town =
        start(address("towny", "town", "Steve"), new Vec3(0.5, 0.9, 3.0));
    assertEquals("✦ player Steve ✦", plain(player.text));

    trips.stop(town);
    scheduler.tick();
    assertEquals("✦ Steve ✦", plain(player.text));
    assertFalse(player.removed);
  }

  @Test
  void notShownOutOfSight() {
    platform.probe = (x, y, z) -> x == 2 && y >= 1 ? TrailBlock.SOLID : TrailBlock.OPEN;
    start(address("player", "Steve"), AHEAD);
    assertTrue(platform.spawned.isEmpty());
  }

  @Test
  void hittingCancelCancelsItsTrip() {
    start(address("essentials", "player", "Steve"), AHEAD);
    Trip<Object, TestTripAgent, Vec3> town = start(address("towny", "town", "Steve"), AHEAD);

    platform.lookAt(platform.cancels().get(1).at);
    assertTrue(labels.swing(player.uuid()), "the click does nothing else");

    assertEquals(1, trips.trips(player.uuid()).size());
    assertFalse(trips.trips(player.uuid()).contains(town));
    assertTrue(platform.names().get(1).removed);
    assertTrue(platform.cancels().get(1).removed);
    assertFalse(platform.names().get(0).removed);
    assertEquals(
        List.of("Canceled your trip to town Steve."),
        sent.stream().map(TrailLabelManagerTest::plain).toList(),
        "named as the label was");

    assertFalse(labels.swing(player.uuid()), "nothing there any more");
    assertEquals(1, sent.size());
  }

  @Test
  void aPlayerWhoCannotAimGetsNoCancel() {
    platform.aims = false;
    Trip<Object, TestTripAgent, Vec3> trip = start(address("player", "Steve"), AHEAD);
    assertTrue(platform.cancels().isEmpty());

    platform.lookAt(HologramPlacement.cancelAt(platform.only().at));
    assertFalse(labels.swing(player.uuid()), "where Cancel would be is just air");
    assertEquals(1, trips.trips(player.uuid()).size());

    trips.stop(trip);
    assertTrue(platform.only().removed);
  }

  @Test
  void hittingFromFarAwayCounts() {
    Vec3 far = new Vec3(20.0, 0.9, 0.5); // well past the 3 blocks a survival player can reach
    start(address("player", "Steve"), far);
    platform.lookAt(platform.cancels().getFirst().at);
    assertTrue(labels.swing(player.uuid()));
    assertTrue(trips.trips(player.uuid()).isEmpty());
  }

  @Test
  void hittingTheNameDoesNothing() {
    start(address("player", "Steve"), AHEAD);
    platform.lookAt(platform.only().at);
    assertFalse(labels.swing(player.uuid()));
    assertEquals(1, trips.trips(player.uuid()).size());
    assertTrue(sent.isEmpty());
  }

  @Test
  void swingingElsewhereDoesNothing() {
    start(address("player", "Steve"), AHEAD);
    platform.look = new Vec3(-1, 0, 0);
    assertFalse(labels.swing(player.uuid()));
    assertEquals(1, trips.trips(player.uuid()).size());
  }

  @Test
  void swingingBeforeTheLabelShowsDoesNothing() {
    start(address("player", "Steve"), new AheadNavigator(AHEAD));
    platform.lookAt(OVER.plus(new Vec3(0, HologramPlacement.CANCEL_RISE, 0)));
    assertFalse(labels.swing(player.uuid()));
    assertEquals(1, trips.trips(player.uuid()).size());
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

  /** A player standing over a flat floor, wherever the test puts them. */
  private static final class FakePlatform implements HologramPlatform<TestTripAgent, Vec3> {
    final List<FakeHologram> spawned = new ArrayList<>();
    TrailSmoother.BlockProbe probe = (x, y, z) -> y < 0 ? TrailBlock.SOLID : TrailBlock.OPEN;
    Vec3 feet = FEET;
    Vec3 look = new Vec3(1, 0, 0);
    boolean online = true;
    boolean aims = true;

    /** The one label's name line. */
    FakeHologram only() {
      assertEquals(1, names().size());
      return names().getFirst();
    }

    /** Every name line placed, in order. */
    List<FakeHologram> names() {
      return spawned.stream().filter(label -> !plain(label.text).equals("Cancel")).toList();
    }

    /** Every {@code Cancel} line placed, in order. */
    List<FakeHologram> cancels() {
      return spawned.stream().filter(label -> plain(label.text).equals("Cancel")).toList();
    }

    void lookAt(Vec3 target) {
      look = target.minus(EYE).unit();
    }

    @Override
    public Vec3 point(Vec3 location) {
      return location;
    }

    @Override
    public Optional<PlayerPosition> position(TestTripAgent agent) {
      return online
          ? Optional.of(new PlayerPosition(feet, feet.plus(new Vec3(0, 1.62, 0)), look))
          : Optional.empty();
    }

    @Override
    public TrailSmoother.BlockProbe probe(TestTripAgent agent) {
      return probe;
    }

    @Override
    public boolean aims(TestTripAgent agent) {
      return aims;
    }

    @Override
    public Optional<Hologram> spawn(TestTripAgent agent, Vec3 at, Component text) {
      FakeHologram hologram = new FakeHologram(at, text);
      spawned.add(hologram);
      return Optional.of(hologram);
    }
  }

  private static final class FakeHologram implements Hologram {
    final Vec3 at;
    Component text;
    boolean removed;

    FakeHologram(Vec3 at, Component text) {
      this.at = at;
      this.text = text;
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
