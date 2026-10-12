/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import org.jetbrains.annotations.Nullable;

/**
 * Supplies one branch of the {@code /navigate} tree for a player, in Cobblestone's own terms.
 * Cobblestone's built-in destinations are one; each integration's {@code DestinationService} is
 * another, once {@linkplain PlatformTrees adapted}.
 *
 * @param <P> the platform player type
 */
@FunctionalInterface
public interface DestinationProvider<P> {

  /**
   * The root of this branch for the given player.
   *
   * @param player the player requesting navigation
   * @return the branch, or {@code null} if there is nothing to offer this player right now
   */
  @Nullable
  DestinationNode<NavigationTarget> provide(P player);
}
