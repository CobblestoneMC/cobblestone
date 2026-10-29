/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WorkersTest {

  @Test
  void zeroLeavesTheCountsToCobblestone() {
    assertEquals(Workers.defaultComputeThreads(), Workers.computeThreads(0));
    assertEquals(Workers.defaultComputeThreads(), Workers.computeThreads(-1));
    assertEquals(3, Workers.computeThreads(3));
    assertTrue(Workers.defaultComputeThreads() >= 2);
    assertEquals(Workers.DEFAULT_IO_THREADS, Workers.ioThreads(0));
    assertEquals(5, Workers.ioThreads(5));
  }

  @Test
  void eachKindOfWorkRunsOnItsOwnNamedDaemonThreads() throws Exception {
    try (Workers workers = new Workers(1, 1)) {
      Thread compute = threadOf(workers.compute());
      assertEquals("cobblestone-compute-1", compute.getName());
      assertTrue(compute.isDaemon());
      assertEquals("cobblestone-io-1", threadOf(workers.io()).getName());
      assertEquals("cobblestone-io-1", threadOf(workers.lane("data")).getName());
      assertEquals("cobblestone-compute-1", threadOf(workers.search().executor()).getName());
    }
  }

  @Test
  void aDelayWaitsOnTheTimerAndRunsOnCompute() throws Exception {
    try (Workers workers = new Workers(1, 1)) {
      CompletableFuture<Thread> later = new CompletableFuture<>();
      long start = workers.time().millis();
      workers.time().schedule(() -> later.complete(Thread.currentThread()), 50);
      assertEquals("cobblestone-compute-1", later.get(5, TimeUnit.SECONDS).getName());
      assertTrue(workers.time().millis() - start >= 50);
    }
  }

  @Test
  void aLaneKeepsItsOrderAcrossManyIoThreads() throws Exception {
    List<Integer> ran = new CopyOnWriteArrayList<>();
    CountDownLatch done = new CountDownLatch(1);
    try (Workers workers = new Workers(1, 4)) {
      Executor lane = workers.lane("data");
      IntStream.range(0, 500).forEach(i -> lane.execute(() -> ran.add(i)));
      lane.execute(done::countDown);
      assertTrue(done.await(5, TimeUnit.SECONDS));
    }
    assertEquals(IntStream.range(0, 500).boxed().toList(), ran);
  }

  @Test
  void aFailedTaskDoesNotStallItsLane() throws Exception {
    try (Workers workers = new Workers(1, 1)) {
      Executor lane = workers.lane("data");
      Thread.UncaughtExceptionHandler quiet = (thread, error) -> {};
      lane.execute(() -> Thread.currentThread().setUncaughtExceptionHandler(quiet));
      lane.execute(
          () -> {
            throw new IllegalStateException("expected");
          });
      CompletableFuture<Void> after = new CompletableFuture<>();
      lane.execute(() -> after.complete(null));
      after.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void closingFinishesIoWorkAlreadyGiven() throws Exception {
    List<Integer> written = new CopyOnWriteArrayList<>();
    Workers workers = new Workers(1, 1);
    Executor lane = workers.lane("data");
    CountDownLatch started = new CountDownLatch(1);
    lane.execute(
        () -> {
          started.countDown();
          sleep(100);
          written.add(0);
        });
    lane.execute(() -> written.add(1));
    assertTrue(started.await(5, TimeUnit.SECONDS));

    workers.close();

    assertEquals(List.of(0, 1), written);
    assertThrows(RejectedExecutionException.class, () -> lane.execute(() -> written.add(2)));
  }

  private static Thread threadOf(Executor executor) throws Exception {
    CompletableFuture<Thread> thread = new CompletableFuture<>();
    executor.execute(() -> thread.complete(Thread.currentThread()));
    return thread.get(5, TimeUnit.SECONDS);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
