/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.trip;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;

/**
 * A cheap, short-range search from the player to a nearby target (the current trail step), used to
 * draw a real "return to trail" path instead of a straight line. Supplied by the platform command
 * layer; run by the {@link Trip} when the navigator asks (throttled).
 *
 * @param <L> the native location type
 */
@FunctionalInterface
public interface GuideSearch<L> {

  /**
   * Runs one short guide search toward {@code target}.
   *
   * @param target the location to guide the player toward
   * @return a future of the guide path, or empty if none was found
   */
  CompletableFuture<Optional<Path<L, MinecraftStepPayload>>> search(L target);
}
