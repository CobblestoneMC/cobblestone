/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A value that is <i>either</i> immediately available <i>or</i> pending on a
 * {@link CompletableFuture} — never both.
 *
 * <p>This is the linchpin of Odyssey's cooperative scheduling: a search consumes
 * {@link Immediate} results in a tight synchronous loop (cache hits) and only registers a
 * continuation — parking its worker thread — when it encounters a {@link Pending} value (a cache
 * miss). Modelled as a sealed sum type so callers can switch exhaustively.
 *
 * @param <T> the value type
 */
public sealed interface FutureOr<T> permits FutureOr.Immediate, FutureOr.Pending {

  /**
   * Wraps an already-available value.
   *
   * @param value the value
   * @param <T> the value type
   * @return an immediate {@code FutureOr}
   */
  static <T> FutureOr<T> of(T value) {
    return new Immediate<>(value);
  }

  /**
   * Wraps a pending future.
   *
   * @param future the pending future
   * @param <T> the value type
   * @return a pending {@code FutureOr}
   */
  static <T> FutureOr<T> ofFuture(CompletableFuture<T> future) {
    return new Pending<>(future);
  }

  /**
   * Returns whether the value is immediately available.
   *
   * @return {@code true} for {@link Immediate}
   */
  boolean isImmediate();

  /**
   * Returns the value: the immediate value directly, or — for a pending {@code FutureOr} — the
   * completed future's value.
   *
   * @return the value
   * @throws IllegalStateException if this is pending and its future has not yet completed
   */
  T value();

  /**
   * Returns the pending future.
   *
   * @return the future
   * @throws IllegalStateException if this is {@link Immediate}
   */
  CompletableFuture<T> future();

  /**
   * Transforms the eventual value, preserving immediacy.
   *
   * @param fn the mapping function
   * @param <R> the mapped type
   * @return a mapped {@code FutureOr}
   */
  <R> FutureOr<R> map(Function<? super T, ? extends R> fn);

  /**
   * Runs {@code callback} with the value: synchronously right now if immediate, otherwise on
   * {@code executor} once the future completes successfully. Exceptional completions are ignored
   * here (the search observes failures through the future itself).
   *
   * @param callback the consumer of the value
   * @param executor the executor for the pending case
   */
  void whenReady(Consumer<? super T> callback, Executor executor);

  /**
   * An immediately-available value (a cache hit).
   *
   * @param <T> the value type
   * @param value the value
   */
  record Immediate<T>(T value) implements FutureOr<T> {

    @Override
    public boolean isImmediate() {
      return true;
    }

    @Override
    public CompletableFuture<T> future() {
      throw new IllegalStateException("FutureOr is immediate, not pending");
    }

    @Override
    public <R> FutureOr<R> map(Function<? super T, ? extends R> fn) {
      return new Immediate<>(fn.apply(value));
    }

    @Override
    public void whenReady(Consumer<? super T> callback, Executor executor) {
      callback.accept(value);
    }
  }

  /**
   * A value pending on a future (a cache miss).
   *
   * @param <T> the value type
   * @param future the pending future
   */
  record Pending<T>(CompletableFuture<T> future) implements FutureOr<T> {

    @Override
    public boolean isImmediate() {
      return false;
    }

    @Override
    public T value() {
      if (!future.isDone()) {
        throw new IllegalStateException("FutureOr is pending and its future has not yet completed");
      }
      return future.getNow(null);
    }

    @Override
    public <R> FutureOr<R> map(Function<? super T, ? extends R> fn) {
      return new Pending<>(future.thenApply(fn));
    }

    @Override
    public void whenReady(Consumer<? super T> callback, Executor executor) {
      future.whenCompleteAsync((value, error) -> {
        if (error == null) {
          callback.accept(value);
        }
      }, executor);
    }
  }
}
