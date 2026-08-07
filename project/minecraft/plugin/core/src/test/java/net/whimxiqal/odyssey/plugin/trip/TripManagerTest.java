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
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.FutureOr;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.MinecraftBlock;
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
    TripManager<Object> manager = new TripManager<>(scheduler, 2, 5);
    UUID player = UUID.randomUUID();

    assertTrue(manager.start(player, anchor(), "trail", new FakeNavigator()).isPresent());
    assertTrue(manager.start(player, anchor(), "trail", new FakeNavigator()).isPresent());
    assertTrue(manager.start(player, anchor(), "trail", new FakeNavigator()).isEmpty(), "over limit");
    assertEquals(2, manager.trips(player).size());
  }

  @Test
  void completedTripStopsTicksAndIsUntracked() {
    RecordingScheduler scheduler = new RecordingScheduler();
    TripManager<Object> manager = new TripManager<>(scheduler, 3, 5);
    UUID player = UUID.randomUUID();
    FakeNavigator navigator = new FakeNavigator();
    navigator.completeAfter = 2;

    manager.start(player, anchor(), "trail", navigator);
    assertTrue(navigator.started);

    scheduler.tickAll(); // 1st tick: not complete → tick()
    assertEquals(1, navigator.ticks);
    assertEquals(1, manager.trips(player).size());

    scheduler.tickAll(); // 2nd tick: not complete → tick()
    assertEquals(2, navigator.ticks);

    scheduler.tickAll(); // 3rd tick: now complete → stop + untrack, no further tick()
    assertEquals(2, navigator.ticks);
    assertTrue(navigator.stopped);
    assertTrue(manager.trips(player).isEmpty());
    assertTrue(scheduler.handle.cancelled);
  }

  @Test
  void liveTripReSearchesAndHotSwapsUntilStopped() {
    RecordingScheduler scheduler = new RecordingScheduler();
    TripManager<Object> manager = new TripManager<>(scheduler, 3, 5);
    UUID player = UUID.randomUUID();
    FakeNavigator navigator = new FakeNavigator();
    FakeLiveSearch live = new FakeLiveSearch();

    manager.startLive(player, anchor(), "trail", navigator, live, 100L);
    assertEquals(1, scheduler.delayedCount(), "first re-search scheduled");

    scheduler.runDelayedOnce(); // re-search → search completes → hot-swap → reschedule
    assertEquals(1, live.calls);
    assertEquals(1, navigator.updates);
    assertEquals(1, scheduler.delayedCount(), "next re-search scheduled");

    manager.stopAll(player);
    scheduler.runDelayedOnce(); // stopped: the loop must not search or reschedule again
    assertEquals(1, live.calls);
    assertEquals(1, navigator.updates);
    assertEquals(0, scheduler.delayedCount());
  }

  @Test
  void stopAllStopsEveryTripForPlayer() {
    RecordingScheduler scheduler = new RecordingScheduler();
    TripManager<Object> manager = new TripManager<>(scheduler, 3, 5);
    UUID player = UUID.randomUUID();
    FakeNavigator first = new FakeNavigator();
    FakeNavigator second = new FakeNavigator();
    manager.start(player, anchor(), "trail", first);
    manager.start(player, anchor(), "trail", second);

    manager.stopAll(player);

    assertTrue(first.stopped);
    assertTrue(second.stopped);
    assertTrue(manager.trips(player).isEmpty());
  }

  private static Position<MinecraftWorld> anchor() {
    return new Position<>(new Cell(0, 0, 0), new FakeWorld());
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
    public void update(Path<Step<Object, MinecraftStepPayload>> newPath) {
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
  }

  /** A live search that always returns an (empty) path immediately, counting invocations. */
  private static final class FakeLiveSearch implements LiveSearch<Object> {
    int calls;

    @Override
    public CompletableFuture<Optional<Path<Step<Object, MinecraftStepPayload>>>> search() {
      calls++;
      Path<Step<Object, MinecraftStepPayload>> path = new Path<>() {
        @Override
        public List<Step<Object, MinecraftStepPayload>> steps() {
          return List.of();
        }

        @Override
        public double cost() {
          return 0.0;
        }
      };
      return CompletableFuture.completedFuture(Optional.of(path));
    }
  }

  /** A scheduler that captures repeating tasks so the test can drive ticks deterministically. */
  private static final class RecordingScheduler implements MinecraftScheduler {
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

  /** A minimal world so an anchor {@link Position} can be built without a server. */
  private static final class FakeWorld implements MinecraftWorld {
    @Override
    public int minY() {
      return 0;
    }

    @Override
    public int maxY() {
      return 255;
    }

    @Override
    public String key() {
      return "test:world";
    }

    @Override
    public Environment environment() {
      return Environment.OVERWORLD;
    }

    @Override
    public FutureOr<MinecraftBlock> blockAt(Cell cell) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof FakeWorld;
    }

    @Override
    public int hashCode() {
      return 1;
    }
  }
}
