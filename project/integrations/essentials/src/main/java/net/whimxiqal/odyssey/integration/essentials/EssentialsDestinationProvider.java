/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.essentials;

import java.util.Collection;
import java.util.List;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestination;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestinationProvider;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestinationTree;
import net.whimxiqal.odyssey.plugin.api.DestinationTree;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * Surfaces Essentials teleports as navigation targets: {@code essentials → home → <name>} (one leaf
 * per the player's homes) and {@code essentials → spawn}. Navigating to one is gated by Odyssey's
 * own {@code odyssey.navigate.essentials.*} permission (default-allow) rather than the Essentials
 * teleport permission — so a player can walk to a place even where {@code /home}/{@code /spawn} is
 * revoked (the teleport transition still requires the Essentials permission). Destinations
 * re-resolve on query, so moving a home or the spawn is reflected without a restart.
 */
final class EssentialsDestinationProvider implements PaperDestinationProvider {

  static final String TREE_KEY = "essentials";
  static final String HOME_KEY = "home";
  static final String SPAWN_KEY = "spawn";

  private final Essentials essentials;

  EssentialsDestinationProvider(Essentials essentials) {
    this.essentials = essentials;
  }

  @Override
  public Collection<DestinationTree<World, Vector3i>> provide(Player player) {
    PaperDestinationTree homes = PaperDestinationTree.node(HOME_KEY).strict();
    for (String home : essentials.homes(player)) {
      homes.leaf(home, () -> PaperDestination.at(essentials.home(player, home), home));
    }
    PaperDestinationTree root = PaperDestinationTree.node(TREE_KEY).subtree(homes);
    if (essentials.hasSpawn()) {
      root.leaf(SPAWN_KEY, () -> PaperDestination.at(essentials.spawn(player), "spawn"));
    }
    return List.of(root.build());
  }
}
