/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FutureOrTest {

  @Test
  void immediateExposesValueAndRejectsFuture() {
    FutureOr<String> fo = FutureOr.of("hi");
    assertTrue(fo.isImmediate());
    assertEquals("hi", fo.value());
    assertThrows(IllegalStateException.class, fo::future);
  }

  @Test
  void pendingExposesFuture() {
    CompletableFuture<String> future = new CompletableFuture<>();
    FutureOr<String> fo = FutureOr.ofFuture(future);
    assertFalse(fo.isImmediate());
    assertEquals(future, fo.future());
  }

  @Test
  void pendingValueThrowsWhileIncompleteThenReturnsAfterCompletion() {
    CompletableFuture<String> future = new CompletableFuture<>();
    FutureOr<String> fo = FutureOr.ofFuture(future);
    assertThrows(IllegalStateException.class, fo::value);
    future.complete("ready");
    assertEquals("ready", fo.value());
  }

  @Test
  void mapPreservesImmediacy() {
    FutureOr<Integer> mapped = FutureOr.of("abc").map(String::length);
    assertTrue(mapped.isImmediate());
    assertEquals(3, mapped.value());

    CompletableFuture<String> future = new CompletableFuture<>();
    FutureOr<Integer> mappedPending = FutureOr.ofFuture(future).map(String::length);
    assertFalse(mappedPending.isImmediate());
    future.complete("abcd");
    assertEquals(4, mappedPending.future().join());
  }

  @Test
  void whenReadyImmediateRunsSynchronously() {
    AtomicReference<String> seen = new AtomicReference<>();
    // Executor must NOT be used for the immediate case; pass one that would fail if invoked.
    FutureOr.of("now").whenReady(seen::set, task -> {
      throw new AssertionError("executor should not be used for an immediate value");
    });
    assertEquals("now", seen.get());
  }

  @Test
  void whenReadyPendingRunsOnExecutorAfterCompletion() {
    AtomicReference<String> seen = new AtomicReference<>();
    CompletableFuture<String> future = new CompletableFuture<>();
    FutureOr.ofFuture(future).whenReady(seen::set, Runnable::run);
    assertEquals(null, seen.get(), "callback must not fire before completion");
    future.complete("later");
    assertEquals("later", seen.get());
  }

  @Test
  void whenReadyPendingIgnoresFailure() {
    AtomicReference<String> seen = new AtomicReference<>();
    CompletableFuture<String> future = new CompletableFuture<>();
    FutureOr.ofFuture(future).whenReady(seen::set, Runnable::run);
    future.completeExceptionally(new RuntimeException("boom"));
    assertEquals(null, seen.get(), "callback must not fire on failure");
  }
}
