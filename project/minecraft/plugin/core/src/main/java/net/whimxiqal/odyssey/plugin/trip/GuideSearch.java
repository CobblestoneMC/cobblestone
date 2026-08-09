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
  CompletableFuture<Optional<Path<Step<L, MinecraftStepPayload>>>> search(L target);
}
