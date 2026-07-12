/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

/**
 * The outcome of a search: either a solved {@link Path} or a {@link FailureReason}.
 *
 * @param <S> the step type
 */
public sealed interface NavigationResult<S>
    permits NavigationResult.Success, NavigationResult.Failure {

  /**
   * Returns whether the search succeeded.
   *
   * @return {@code true} for a {@link Success}
   */
  boolean success();

  /**
   * A successful result carrying the solved path.
   *
   * @param <S> the step type
   * @param path the solved path
   */
  record Success<S>(Path<S> path)
      implements NavigationResult<S> {

    @Override
    public boolean success() {
      return true;
    }
  }

  /**
   * A failed result carrying the reason.
   *
   * @param <S> the step type
   * @param reason why the search failed
   */
  record Failure<S>(FailureReason reason)
      implements NavigationResult<S> {

    @Override
    public boolean success() {
      return false;
    }
  }
}
