/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The outcome of a search: either a solved {@link Path} or a {@link FailureReason}.
 *
 * @param <P> the position type
 * @param <T> the payload type
 */
public sealed interface NavigationResult<P, T>
    permits NavigationResult.Success, NavigationResult.Failure, NavigationResult.Error {

  /**
   * Returns whether the search succeeded.
   *
   * @return {@code true} for a {@link Success}
   */
  boolean success();

  <L> NavigationResult<L, T> map(Function<P, L> positionFunc);

  /**
   * A successful result carrying the solved path.
   *
   * @param <P> the position type
   * @param <T> the payload type
   * @param path the solved path
   */
  record Success<P, T>(Path<P, T> path) implements NavigationResult<P, T> {

    @Override
    public boolean success() {
      return true;
    }

    @Override
    public <L> NavigationResult<L, T> map(Function<P, L> positionFunc) {
      List<Step<L, T>> steps = new ArrayList<>();
      for (Step<P, T> step : path.steps()) {
        steps.add(
            new Step<>(
                positionFunc.apply(step.position()), step.cost(), step.time(), step.payload()));
      }
      return new NavigationResult.Success<>(new Path<>(positionFunc.apply(path.origin()), steps));
    }
  }

  /**
   * A failed result carrying the reason.
   *
   * @param <P> the position type
   * @param <T> the payload type
   * @param reason why the search failed
   */
  record Failure<P, T>(FailureReason reason) implements NavigationResult<P, T> {

    @Override
    public boolean success() {
      return false;
    }

    @Override
    public <L> NavigationResult<L, T> map(Function<P, L> positionFunc) {
      return new NavigationResult.Failure<>(reason);
    }
  }

  /**
   * A failed result carrying the reason.
   *
   * @param <P> the position type
   * @param <T> the payload type
   * @param throwable cause of error
   */
  record Error<P, T>(Throwable throwable) implements NavigationResult<P, T> {

    @Override
    public boolean success() {
      return false;
    }

    @Override
    public <L> NavigationResult<L, T> map(Function<P, L> positionFunc) {
      return new NavigationResult.Error<>(throwable);
    }
  }
}
