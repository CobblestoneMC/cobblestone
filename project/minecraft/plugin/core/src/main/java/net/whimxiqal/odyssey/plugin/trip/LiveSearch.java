/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.trip;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;

/**
 * The re-search behavior a live {@link Trip} runs on its recalculation interval. Supplied by the
 * platform command layer (it knows how to search); the neutral {@link Trip} only schedules it and
 * hot-swaps the navigator's path with the result.
 *
 * @param <L> the native location type
 */
@FunctionalInterface
public interface LiveSearch<L> {

  /**
   * Runs one re-search.
   *
   * @return a future of the fresh path, or empty if the search failed, was skipped (e.g. the
   *     concurrency budget was full), or the player is gone — in which case the current path is kept
   */
  CompletableFuture<Optional<Path<Step<L, MinecraftStepPayload>>>> search();
}
