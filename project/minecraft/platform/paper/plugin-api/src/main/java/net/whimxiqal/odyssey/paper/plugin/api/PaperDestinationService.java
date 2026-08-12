/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin.api;

import java.util.Collection;
import net.whimxiqal.odyssey.plugin.api.DestinationTree;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

public interface PaperDestinationService {

  /**
   * Builds the destination tree visible to the given player.
   *
   * @param player the player requesting navigation
   * @return the (lazily-evaluated) tree
   */
  Collection<DestinationTree<World, Vector3i>> provide(Player player);
}
