/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.essentials;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.cobblestonemc.paper.plugin.api.Destination;
import org.cobblestonemc.paper.plugin.api.DestinationService;
import org.cobblestonemc.paper.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.joml.Vector3i;

/**
 * Surfaces Essentials teleports as navigation targets: {@code cobblestoneessentials → home →
 * <name>} (one leaf per the player's homes) and {@code cobblestoneessentials → spawn}. Navigating
 * to one is gated by Cobblestone's own {@code cobblestone.navigate.cobblestoneessentials.*}
 * permission (default-allow) rather than the Essentials teleport permission — so a player can walk
 * to a place even where {@code /home}/{@code /spawn} is revoked (the teleport transition still
 * requires the Essentials permission). Destinations re-resolve on query, so moving a home or the
 * spawn is reflected without a restart.
 */
final class EssentialsDestinationService implements DestinationService {

  static final String HOME_KEY = "home";
  static final String SPAWN_KEY = "spawn";

  private final Essentials essentials;

  EssentialsDestinationService(Essentials essentials) {
    this.essentials = essentials;
  }

  @Override
  public PlatformDestinationTree<World, Vector3i> provide(Player player) {
    DestinationTree homes = DestinationTree.builder().strict();
    for (String home : essentials.homes(player)) {
      homes.leaf(home, () -> Destination.at(essentials.home(player, home), home));
    }
    DestinationTree root = DestinationTree.builder().subtree(HOME_KEY, homes);
    if (essentials.hasSpawn()) {
      root.leaf(SPAWN_KEY, () -> Destination.at(essentials.spawn(player), "spawn"));
    }
    return root.build();
  }
}
