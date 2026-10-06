/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.sidebar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.Position;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.ScheduledTaskHandle;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.data.PlayerPreferences;
import org.cobblestonemc.plugin.data.PlayerPreferencesDao;
import org.cobblestonemc.plugin.message.CobblestoneColors;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.trip.TestTripAgent;
import org.cobblestonemc.plugin.trip.Trip;
import org.cobblestonemc.plugin.trip.TripLabel;
import org.cobblestonemc.plugin.trip.TripManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** When {@link TripSidebar} opens, redraws, and closes a player's sidebar, and what it shows. */
class TripSidebarTest {

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

  private final TestScheduler scheduler = new TestScheduler();
  private final MemoryPreferences preferences = new MemoryPreferences();
  private final AtomicBoolean enabled = new AtomicBoolean(true);
  private final List<FakeDisplay> displays = new ArrayList<>();
  private final TestTripAgent player = new TestTripAgent(UUID.randomUUID());

  private TripManager<Object, TestTripAgent, Object> trips;
  private TripSidebar<Object, TestTripAgent, Object> sidebar;

  @BeforeEach
  void setUp() {
    trips = new TripManager<>(scheduler, 5);
    sidebar = newSidebar();
    trips.onChange(sidebar::refresh);
  }

  private TripSidebar<Object, TestTripAgent, Object> newSidebar() {
    return new TripSidebar<>(
        trips,
        scheduler,
        new Messages(Locale.ENGLISH, true, LOGGER),
        preferences,
        enabled::get,
        agent -> {
          FakeDisplay display = new FakeDisplay();
          displays.add(display);
          return display;
        },
        agent -> Locale.ENGLISH,
        LOGGER);
  }

  private Trip<Object, TestTripAgent, Object> start(TripLabel label, double remaining) {
    return trips
        .start(player, new FixedNavigator(remaining), label, null, null, false, 0L)
        .orElseThrow();
  }

  private FakeDisplay onlyDisplay() {
    assertEquals(1, displays.size());
    return displays.getFirst();
  }

  @Test
  void opensWithTheFirstTripAndDrawsTheHeadingAndRow() {
    start(TripLabel.address(List.of("cobblestone", "location", "private", "home")), 204);

    FakeDisplay display = onlyDisplay();
    assertEquals("Trips", plain(display.title));
    assertTrue(display.title.hasDecoration(TextDecoration.UNDERLINED));
    assertEquals(List.of("home - 3m 24s"), display.plainLines());

    List<Component> parts = display.lines.getFirst().children();
    assertEquals(CobblestoneColors.SECONDARY, parts.get(0).color());
    assertEquals(NamedTextColor.DARK_GRAY, parts.get(1).color());
    assertEquals(NamedTextColor.GRAY, parts.get(2).color());
  }

  @Test
  void redrawsEverySecondAndListsTripsByIdWithShortenedLabels() {
    FixedNavigator caves = new FixedNavigator(5);
    trips.start(
        player,
        caves,
        TripLabel.address(List.of("location", "private", "caves")),
        null,
        null,
        false,
        0L);
    start(TripLabel.address(List.of("location", "private", "home")), 3700);
    start(TripLabel.address(List.of("location", "global", "home")), 61);
    start(TripLabel.of("Lost Lantern"), 0);

    FakeDisplay display = onlyDisplay();
    assertEquals(20L, scheduler.sidebarPeriod, "redraws once a second");
    assertEquals(
        List.of(
            "caves - 5s", "private home - 1h 1m 40s", "global home - 1m 1s", "Lost Lantern - 0s"),
        display.plainLines());

    caves.remaining = 4;
    scheduler.runSidebarTicker();
    assertEquals("caves - 4s", display.plainLines().getFirst());
  }

  @Test
  void closesWithTheLastTripAndStopsRedrawing() {
    Trip<Object, TestTripAgent, Object> first = start(TripLabel.of("a"), 10);
    Trip<Object, TestTripAgent, Object> second = start(TripLabel.of("b"), 10);
    FakeDisplay display = onlyDisplay();

    trips.stop(first);
    assertFalse(display.closed);
    assertEquals(List.of("b - 10s"), display.plainLines());

    trips.stop(second);
    assertTrue(display.closed);
    assertTrue(scheduler.sidebarHandle.cancelled);
  }

  @Test
  void aPlayerCanHideItAndTheChoiceIsStored() {
    start(TripLabel.of("a"), 10);
    sidebar.setShown(player, false);
    assertTrue(onlyDisplay().closed);
    assertFalse(preferences.get(player.uuid()).orElseThrow().sidebar());

    // A fresh controller (as after a reconnect or restart) reads the stored choice back.
    TripSidebar<Object, TestTripAgent, Object> reloaded = newSidebar();
    assertFalse(reloaded.isShown(player.uuid()));
    reloaded.refresh(player);
    assertEquals(1, displays.size(), "nothing reopened while hidden");

    sidebar.setShown(player, true);
    assertEquals(2, displays.size());
    assertFalse(displays.getLast().closed);
  }

  @Test
  void nothingIsShownWhileTheServerHasItTurnedOff() {
    enabled.set(false);
    start(TripLabel.of("a"), 10);
    assertTrue(displays.isEmpty());
    assertFalse(sidebar.available());
  }

  @Test
  void turningItOffServerWideClosesOpenSidebarsOnTheNextRedraw() {
    start(TripLabel.of("a"), 10);
    enabled.set(false);
    scheduler.runSidebarTicker();
    assertTrue(onlyDisplay().closed);
  }

  @Test
  void forgetClosesTheSidebarOnLogout() {
    start(TripLabel.of("a"), 10);
    sidebar.forget(player.uuid());
    assertTrue(onlyDisplay().closed);
    assertTrue(scheduler.sidebarHandle.cancelled);
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

  /** Records what the sidebar was last told to show. */
  private static final class FakeDisplay implements SidebarDisplay {
    Component title;
    List<Component> lines = List.of();
    boolean closed;

    @Override
    public void render(Component title, List<Component> lines) {
      this.title = title;
      this.lines = lines;
    }

    @Override
    public void close() {
      closed = true;
    }

    List<String> plainLines() {
      return lines.stream().map(TripSidebarTest::plain).toList();
    }
  }

  /** A navigator that never completes and reports a settable remaining time. */
  private static final class FixedNavigator implements Navigator<Object> {
    double remaining;

    FixedNavigator(double remaining) {
      this.remaining = remaining;
    }

    @Override
    public void start() {}

    @Override
    public void tick() {}

    @Override
    public void update(Path<Object, MinecraftStepPayload> newPath) {}

    @Override
    public void stop() {}

    @Override
    public boolean isComplete() {
      return false;
    }

    @Override
    public double remainingSeconds() {
      return remaining;
    }
  }

  private static final class MemoryPreferences implements PlayerPreferencesDao {
    private final Map<UUID, PlayerPreferences> rows = new HashMap<>();

    @Override
    public void upsert(PlayerPreferences preferences) {
      rows.put(preferences.player(), preferences);
    }

    @Override
    public Optional<PlayerPreferences> get(UUID player) {
      return Optional.ofNullable(rows.get(player));
    }
  }

  /**
   * Runs one-shot work immediately. Of the repeating tasks it keeps only the sidebar's (period 20),
   * so a test can fire its redraw; trip ticks (period 1) are not needed here.
   */
  private static final class TestScheduler implements MinecraftScheduler<Object> {
    Runnable sidebarTicker;
    long sidebarPeriod;
    Handle sidebarHandle;

    void runSidebarTicker() {
      sidebarTicker.run();
    }

    @Override
    public void runAtEntity(Object entity, Runnable task) {
      task.run();
    }

    @Override
    public ScheduledTaskHandle runAtEntityRepeating(
        Object entity, Runnable task, long periodTicks) {
      Handle handle = new Handle();
      if (periodTicks != 1L) {
        sidebarTicker = task;
        sidebarPeriod = periodTicks;
        sidebarHandle = handle;
      }
      return handle;
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
