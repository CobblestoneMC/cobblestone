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
 * @param <P> the position type
 * @param <T> the payload type
 */
public sealed interface NavigationResult<P, T>
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
   * @param <P> the position type
   * @param <T> the payload type
   * @param path the solved path
   */
  record Success<P, T>(Path<P, T> path) implements NavigationResult<P, T> {

    @Override
    public boolean success() {
      return true;
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
  }
}
