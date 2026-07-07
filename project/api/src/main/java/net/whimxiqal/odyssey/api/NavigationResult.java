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
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param <D> the domain type
 */
public sealed interface NavigationResult<T extends Enum<T>, I, D extends Domain>
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
   * @param <T> the step-type enum
   * @param <I> the instruction payload type
   * @param <D> the domain type
   * @param path the solved path
   */
  record Success<T extends Enum<T>, I, D extends Domain>(Path<T, I, D> path)
      implements NavigationResult<T, I, D> {

    @Override
    public boolean success() {
      return true;
    }
  }

  /**
   * A failed result carrying the reason.
   *
   * @param <T> the step-type enum
   * @param <I> the instruction payload type
   * @param <D> the domain type
   * @param reason why the search failed
   */
  record Failure<T extends Enum<T>, I, D extends Domain>(FailureReason reason)
      implements NavigationResult<T, I, D> {

    @Override
    public boolean success() {
      return false;
    }
  }
}
