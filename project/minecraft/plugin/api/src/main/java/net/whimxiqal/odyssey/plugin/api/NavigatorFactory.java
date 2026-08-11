/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.api;

import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;

public interface NavigatorFactory<P, L> {

  String key();

  /**
   * Creates a navigator.
   *
   * @param player the player to guide
   * @param path the path to follow
   * @return the navigator
   */
  Navigator<L> create(P player, Path<L, MinecraftStepPayload> path);
}
