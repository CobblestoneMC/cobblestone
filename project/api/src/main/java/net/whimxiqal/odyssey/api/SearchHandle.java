/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

import java.util.concurrent.CompletableFuture;

/**
 * A handle to an in-flight search: its eventual result plus the ability to cancel it.
 *
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param <D> the domain type
 */
public interface SearchHandle<T extends Enum<T>, I, D extends Domain> {

  /**
   * Returns the future that completes with the search result.
   *
   * @return the result future
   */
  CompletableFuture<NavigationResult<T, I, D>> future();

  /**
   * Cancels the search, completing {@link #future()} with {@link FailureReason#CANCELLED} if it has
   * not already finished. Idempotent.
   */
  void cancel();
}
