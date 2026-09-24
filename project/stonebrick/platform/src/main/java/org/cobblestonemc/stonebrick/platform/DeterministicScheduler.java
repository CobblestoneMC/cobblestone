/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.cobblestonemc.Position;
import org.cobblestonemc.TimeSource;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.ScheduledTaskHandle;

/**
 * A scheduler that runs every task on the caller's thread, in a fixed order, against a virtual
 * clock.
 *
 * <p><b>This is what makes a benchmark comparable.</b> A search's expansion order depends on which
 * chunk read landed first, and on a real thread pool that varies from run to run by more than most
 * real improvements would change it. Running everything on one thread in issue order removes that
 * entirely: the same scenario produces byte-identical behaviour every time, so a difference between
 * two algorithm versions is the algorithm.
 *
 * <p><b>The virtual clock is the other half.</b> Delayed work — a simulated disk read, a search's
 * deadline — is queued by the time it should happen rather than slept on. When nothing is ready to
 * run, {@link #drainUntil} jumps the clock straight to the next due task. So a solve that waits ten
 * simulated minutes for a spinning disk runs in milliseconds, and its reported parked time is ten
 * minutes, which is the number that was wanted.
 *
 * <p>Not thread-safe, and deliberately so: anything submitting work from another thread would
 * reintroduce exactly the ordering nondeterminism this exists to remove. A run under this scheduler
 * is single-threaded from end to end.
 */
public final class DeterministicScheduler
    implements MinecraftScheduler<Object>, TimeSource, AutoCloseable {

  /** Immediate work, in submission order. */
  private final Deque<Runnable> ready = new ArrayDeque<>();

  /** Delayed work, ordered by due time then by submission, so ties are not left to the heap. */
  private final PriorityQueue<Delayed> timers =
      new PriorityQueue<>(
          java.util.Comparator.comparingLong(Delayed::dueMillis)
              .thenComparingLong(Delayed::sequence));

  private long nowMillis;
  private long sequence;
  private long tasksRun;
  private boolean shutdown;

  private record Delayed(long dueMillis, long sequence, Runnable task) {}

  /** Returns how many tasks have been run, for reporting and for spotting a runaway loop. */
  public long tasksRun() {
    return tasksRun;
  }

  /** Returns the current virtual time in milliseconds. */
  @Override
  public long millis() {
    return nowMillis;
  }

  @Override
  public void schedule(Runnable task, long delayMillis) {
    timers.add(new Delayed(nowMillis + Math.max(0, delayMillis), sequence++, task));
  }

  @Override
  public void runAsync(Runnable task) {
    ready.addLast(task);
  }

  @Override
  public void runAsyncLater(Runnable task, long delayMillis) {
    schedule(task, delayMillis);
  }

  @Override
  public TimeSource time() {
    return this;
  }

  @Override
  public ExecutorService asyncExecutor() {
    return new AbstractExecutorService() {
      @Override
      public void execute(Runnable command) {
        runAsync(command);
      }

      @Override
      public void shutdown() {
        shutdown = true;
      }

      @Override
      public List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> pending = List.copyOf(ready);
        ready.clear();
        timers.clear();
        return pending;
      }

      @Override
      public boolean isShutdown() {
        return shutdown;
      }

      @Override
      public boolean isTerminated() {
        return shutdown && ready.isEmpty() && timers.isEmpty();
      }

      @Override
      public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isTerminated();
      }
    };
  }

  @Override
  public void runAtPosition(Position<? extends MinecraftWorld> position, Runnable task) {
    runAsync(task);
  }

  @Override
  public void runGlobal(Runnable task) {
    runAsync(task);
  }

  @Override
  public ScheduledTaskHandle runAtPositionRepeating(
      Position<? extends MinecraftWorld> position, Runnable task, long periodTicks) {
    return repeating(task, periodTicks);
  }

  @Override
  public void runAtEntity(Object entity, Runnable task) {
    runAsync(task);
  }

  @Override
  public ScheduledTaskHandle runAtEntityRepeating(Object entity, Runnable task, long periodTicks) {
    return repeating(task, periodTicks);
  }

  private ScheduledTaskHandle repeating(Runnable task, long periodTicks) {
    long period = Math.max(1, periodTicks) * 50L;
    boolean[] cancelled = {false};
    scheduleRepeat(task, period, cancelled);
    return () -> cancelled[0] = true;
  }

  private void scheduleRepeat(Runnable task, long period, boolean[] cancelled) {
    schedule(
        () -> {
          if (cancelled[0]) {
            return;
          }
          task.run();
          scheduleRepeat(task, period, cancelled);
        },
        period);
  }

  /**
   * Runs queued work until {@code done} is satisfied, advancing the virtual clock whenever nothing
   * is immediately ready.
   *
   * @param done the condition to stop on, checked between tasks
   * @param maxTasks a ceiling on tasks run, so a search that never settles fails the test instead
   *     of hanging it
   * @return {@code true} if {@code done} became true, {@code false} if work or the ceiling ran out
   */
  public boolean drainUntil(BooleanSupplier done, long maxTasks) {
    long ran = 0;
    while (ran < maxTasks) {
      if (done.getAsBoolean()) {
        return true;
      }
      Runnable next = ready.pollFirst();
      if (next == null) {
        Delayed timer = timers.poll();
        if (timer == null) {
          // Nothing ready and nothing pending: whatever we are waiting for is never coming.
          return done.getAsBoolean();
        }
        // Everything is waiting, so let time pass — this is the jump that makes a simulated disk
        // cost no real seconds.
        nowMillis = Math.max(nowMillis, timer.dueMillis());
        next = timer.task();
      }
      next.run();
      ran++;
      tasksRun++;
    }
    return done.getAsBoolean();
  }

  /** Runs queued work until nothing is left. */
  public void drain(long maxTasks) {
    drainUntil(() -> ready.isEmpty() && timers.isEmpty(), maxTasks);
  }

  @Override
  public void close() {
    ready.clear();
    timers.clear();
  }
}
