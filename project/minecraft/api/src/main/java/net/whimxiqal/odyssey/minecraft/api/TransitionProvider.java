/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.api.Transition;

/**
 * A lazy, per-player, asynchronous supplier of {@link Transition}s (portals, teleports, mounts, …).
 *
 * <p>Developers register these to expose custom wormholes; the plugin gathers them (plus vanilla
 * portal transitions) into the list passed to a search.
 */
@FunctionalInterface
public interface TransitionProvider {

  /**
   * Computes the transitions available to the given player.
   *
   * @param player the player
   * @return a future of the available transitions
   */
  CompletableFuture<List<? extends Transition<MinecraftStepType, MinecraftInstruction, MinecraftWorld>>>
      compute(OdysseyPlayer player);
}
