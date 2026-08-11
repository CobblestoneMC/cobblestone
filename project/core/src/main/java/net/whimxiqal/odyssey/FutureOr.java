/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A value that is <i>either</i> immediately available <i>or</i> pending on a {@link
 * CompletableFuture} — never both.
 *
 * <p>This is the linchpin of Odyssey's cooperative scheduling: a search consumes {@link Immediate}
 * results in a tight synchronous loop (cache hits) and only registers a continuation — parking its
 * worker thread — when it encounters a {@link Pending} value (a cache miss). Modelled as a sealed
 * sum type so callers can switch exhaustively.
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
   * Adapts a {@link CompletableFuture} to a {@code FutureOr}, preserving the immediate fast-path:
   * an already-(successfully)-completed future becomes {@link Immediate} (no parking), otherwise
   * {@link Pending}. Used at platform boundaries where callbacks hand back plain futures.
   *
   * @param future the future
   * @param <T> the value type
   * @return an immediate {@code FutureOr} if the future is already done, else pending
   */
  static <T> FutureOr<T> from(CompletableFuture<T> future) {
    if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
      return of(future.getNow(null));
    }
    return ofFuture(future);
  }

  /**
   * Combines many {@code FutureOr}s into one of a list, preserving immediacy: immediate only if
   * <i>every</i> input is immediate (so an all-cache-hit combine never parks).
   *
   * @param items the inputs, in order
   * @param <T> the value type
   * @return a {@code FutureOr} of the values in input order
   */
  static <T> FutureOr<List<T>> all(List<? extends FutureOr<? extends T>> items) {
    boolean allImmediate = true;
    for (FutureOr<? extends T> item : items) {
      if (!item.isImmediate()) {
        allImmediate = false;
        break;
      }
    }
    if (allImmediate) {
      List<T> values = new ArrayList<>(items.size());
      for (FutureOr<? extends T> item : items) {
        values.add(item.value());
      }
      return of(values);
    }
    List<CompletableFuture<? extends T>> futures = new ArrayList<>(items.size());
    for (FutureOr<? extends T> item : items) {
      futures.add(item.toFuture());
    }
    CompletableFuture<List<T>> combined =
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
            .thenApply(
                ignored -> {
                  List<T> values = new ArrayList<>(futures.size());
                  for (CompletableFuture<? extends T> future : futures) {
                    values.add(future.getNow(null));
                  }
                  return values;
                });
    return ofFuture(combined);
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
   * Transforms the eventual value into another {@code FutureOr}, flattening the result — so a chain
   * that hits only immediate values stays immediate (no parking), and parks only where a link is
   * pending.
   *
   * @param fn the mapping function producing the next {@code FutureOr}
   * @param <R> the mapped type
   * @return the flattened {@code FutureOr}
   */
  <R> FutureOr<R> flatMap(Function<? super T, ? extends FutureOr<R>> fn);

  /**
   * Returns this as a {@link CompletableFuture}: an already-complete future for the immediate case,
   * or the pending future directly.
   *
   * @return the future
   */
  default CompletableFuture<T> toFuture() {
    return isImmediate() ? CompletableFuture.completedFuture(value()) : future();
  }

  /**
   * Runs {@code callback} with the value: synchronously right now if immediate, otherwise on {@code
   * executor} once the future completes successfully. Exceptional completions are ignored here (the
   * search observes failures through the future itself).
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
    public <R> FutureOr<R> flatMap(Function<? super T, ? extends FutureOr<R>> fn) {
      return fn.apply(value);
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
    public <R> FutureOr<R> flatMap(Function<? super T, ? extends FutureOr<R>> fn) {
      return new Pending<>(future.thenCompose(value -> fn.apply(value).toFuture()));
    }

    @Override
    public void whenReady(Consumer<? super T> callback, Executor executor) {
      future.whenCompleteAsync(
          (value, error) -> {
            if (error == null) {
              callback.accept(value);
            }
          },
          executor);
    }
  }
}
