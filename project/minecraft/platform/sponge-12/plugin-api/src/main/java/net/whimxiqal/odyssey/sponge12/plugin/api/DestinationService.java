/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin.api;

import java.util.Collection;
import net.whimxiqal.odyssey.plugin.api.PlatformDestinationTree;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/** A provider of navigable destinations, queried per-player when a search runs. */
public interface DestinationService {

  /**
   * Builds the destination tree visible to the given player.
   *
   * @param player the player requesting navigation
   * @return the (lazily-evaluated) trees
   */
  Collection<PlatformDestinationTree<ServerWorld, Vector3i>> provide(ServerPlayer player);
}
