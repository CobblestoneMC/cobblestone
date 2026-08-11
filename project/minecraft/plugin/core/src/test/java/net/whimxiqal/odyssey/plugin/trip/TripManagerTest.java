/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.trip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.ScheduledTaskHandle;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.plugin.api.Navigator;
import org.junit.jupiter.api.Test;

/** Trip limits, tick-driven completion, and logout cleanup for {@link TripManager}. */
class TripManagerTest {

  @Test
  void enforcesMaxActivePerPlayer() {
    RecordingScheduler scheduler = new RecordingScheduler();
    TripManager<Object, TestTripAgent, Object> manager = new TripManager<>(scheduler, 2);
    TestTripAgent player = new TestTripAgent(UUID.randomUUID());

    assertTrue(
        manager
            .start(player, "trail", new FakeNavigator(), "dest", null, null, false, 0L)
            .isPresent());
    assertTrue(
        manager
            .start(player, "trail", new FakeNavigator(), "dest", null, null, false, 0L)
            .isPresent());
    assertTrue(
        manager
            .start(player, "trail", new FakeNavigator(), "dest", null, null, false, 0L)
            .isEmpty(),
        "over limit");
    assertEquals(2, manager.trips(player.uuid()).size());
  }

  @Test
  void completedTripStopsTicksAndIsUntracked() {
    RecordingScheduler scheduler = new RecordingScheduler();
    TripManager<Object, TestTripAgent, Object> manager = new TripManager<>(scheduler, 3);
    TestTripAgent player = new TestTripAgent(UUID.randomUUID());
    FakeNavigator navigator = new FakeNavigator();
    navigator.completeAfter = 2;

    manager.start(player, "trail", navigator, "dest", null, null, false, 0L);
    assertTrue(navigator.started);

    scheduler.tickAll(); // 1st tick: not complete → tick()
    assertEquals(1, navigator.ticks);
    assertEquals(1, manager.trips(player.uuid()).size());

    scheduler.tickAll(); // 2nd tick: not complete → tick()
    assertEquals(2, navigator.ticks);

    scheduler.tickAll(); // 3rd tick: now complete → stop + untrack, no further tick()
    assertEquals(2, navigator.ticks);
    assertTrue(navigator.stopped);
    assertTrue(manager.trips(player.uuid()).isEmpty());
    assertTrue(scheduler.handle.cancelled);
  }

  @Test
  void liveTripReSearchesAndHotSwapsUntilStopped() {
    RecordingScheduler scheduler = new RecordingScheduler();
    TripManager<Object, TestTripAgent, Object> manager = new TripManager<>(scheduler, 3);
    TestTripAgent player = new TestTripAgent(UUID.randomUUID());
    FakeNavigator navigator = new FakeNavigator();
    FakeLiveSearch live = new FakeLiveSearch();

    manager.start(player, "trail", navigator, "dest", live, null, true, 100L);
    assertEquals(1, scheduler.delayedCount(), "first re-search scheduled");

    scheduler.runDelayedOnce(); // re-search → search completes → hot-swap → reschedule
    assertEquals(1, live.calls);
    assertEquals(1, navigator.updates);
    assertEquals(1, scheduler.delayedCount(), "next re-search scheduled");

    manager.stopAll(player.uuid());
    scheduler.runDelayedOnce(); // stopped: the loop must not search or reschedule again
    assertEquals(1, live.calls);
    assertEquals(1, navigator.updates);
    assertEquals(0, scheduler.delayedCount());
  }

  @Test
  void assignsRecyclableIdsAndCancelsByIdOrDestination() {
    RecordingScheduler scheduler = new RecordingScheduler();
    TripManager<Object, TestTripAgent, Object> manager = new TripManager<>(scheduler, 3);
    TestTripAgent player = new TestTripAgent(UUID.randomUUID());
    Trip<Object, TestTripAgent, Object> first =
        manager
            .start(player, "trail", new FakeNavigator(), "home", null, null, false, 0L)
            .orElseThrow();
    Trip<Object, TestTripAgent, Object> second =
        manager
            .start(player, "trail", new FakeNavigator(), "caves", null, null, false, 0L)
            .orElseThrow();
    assertEquals(1, first.id());
    assertEquals(2, second.id());

    assertTrue(manager.cancel(player.uuid(), 1));
    assertFalse(manager.cancel(player.uuid(), 1)); // already gone
    assertEquals(1, manager.trips(player.uuid()).size());

    // id 1 is free again and is reused for the next trip
    Trip<Object, TestTripAgent, Object> third =
        manager
            .start(player, "trail", new FakeNavigator(), "home", null, null, false, 0L)
            .orElseThrow();
    assertEquals(1, third.id());

    assertEquals(
        1, manager.cancelByDestination(player.uuid(), "HOME")); // case-insensitive; cancels "third"
    assertEquals(1, manager.trips(player.uuid()).size());
    assertEquals(2, manager.trips(player.uuid()).getFirst().id());
  }

  @Test
  void stopAllStopsEveryTripForPlayer() {
    RecordingScheduler scheduler = new RecordingScheduler();
    TripManager<Object, TestTripAgent, Object> manager = new TripManager<>(scheduler, 3);
    TestTripAgent player = new TestTripAgent(UUID.randomUUID());
    FakeNavigator first = new FakeNavigator();
    FakeNavigator second = new FakeNavigator();
    manager.start(player, "trail", first, "dest", null, null, false, 0L);
    manager.start(player, "trail", second, "dest", null, null, false, 0L);

    manager.stopAll(player.uuid());

    assertTrue(first.stopped);
    assertTrue(second.stopped);
    assertTrue(manager.trips(player.uuid()).isEmpty());
  }

  /** A navigator that reports complete after a set number of ticks. */
  private static final class FakeNavigator implements Navigator<Object> {
    boolean started;
    boolean stopped;
    int ticks;
    int updates;
    int completeAfter = Integer.MAX_VALUE;

    @Override
    public void start() {
      started = true;
    }

    @Override
    public void tick() {
      ticks++;
    }

    @Override
    public void update(Path<Object, MinecraftStepPayload> newPath) {
      updates++;
    }

    @Override
    public void stop() {
      stopped = true;
    }

    @Override
    public boolean isComplete() {
      return ticks >= completeAfter;
    }

    @Override
    public double remainingSeconds() {
      return 0.0;
    }
  }

  /** A live search that always returns an (empty) path immediately, counting invocations. */
  private static final class FakeLiveSearch implements LiveSearch<Object> {
    int calls;

    @Override
    public CompletableFuture<Optional<Path<Object, MinecraftStepPayload>>> search() {
      calls++;
      Path<Object, MinecraftStepPayload> path = new Path<>(null, List.of());
      return CompletableFuture.completedFuture(Optional.of(path));
    }
  }

  /** A scheduler that captures repeating tasks so the test can drive ticks deterministically. */
  private static final class RecordingScheduler implements MinecraftScheduler<Object> {
    private final List<Runnable> repeating = new ArrayList<>();
    private final List<Runnable> delayed = new ArrayList<>();
    FakeHandle handle;

    void tickAll() {
      new ArrayList<>(repeating).forEach(Runnable::run);
    }

    int delayedCount() {
      return delayed.size();
    }

    void runDelayedOnce() {
      List<Runnable> snapshot = new ArrayList<>(delayed);
      delayed.clear();
      snapshot.forEach(Runnable::run);
    }

    @Override
    public ScheduledTaskHandle runAtPositionRepeating(
        Position<? extends MinecraftWorld> position, Runnable task, long periodTicks) {
      repeating.add(task);
      handle = new FakeHandle();
      return handle;
    }

    @Override
    public void runAtEntity(Object entity, Runnable task) {
      task.run();
    }

    @Override
    public ScheduledTaskHandle runAtEntityRepeating(
        Object entity, Runnable task, long periodTicks) {
      repeating.add(task);
      handle = new FakeHandle();
      return handle;
    }

    @Override
    public void runAtPosition(Position<? extends MinecraftWorld> position, Runnable task) {
      task.run();
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
    public void runAsyncLater(Runnable task, long delayMillis) {
      delayed.add(task);
    }

    @Override
    public ExecutorService asyncExecutor() {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeHandle implements ScheduledTaskHandle {
    boolean cancelled;

    @Override
    public void cancel() {
      cancelled = true;
    }
  }
}
