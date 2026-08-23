/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.api;

import java.util.concurrent.CompletableFuture;

/**
 * A handle to an in-flight search: its eventual result plus the ability to cancel it.
 *
 * @param <P> the position type
 * @param <T> the payload type
 */
public interface SearchHandle<P, T> {

  /**
   * Returns the future that completes with the search result.
   *
   * @return the result future
   */
  CompletableFuture<NavigationResult<P, T>> future();

  /**
   * Cancels the search, completing {@link #future()} with {@link FailureReason#CANCELLED} if it has
   * not already finished. Idempotent.
   */
  void cancel();
}
