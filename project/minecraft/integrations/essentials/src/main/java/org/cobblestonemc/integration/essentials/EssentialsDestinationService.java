/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.essentials;

import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.cobblestonemc.paper.plugin.api.Destination;
import org.cobblestonemc.paper.plugin.api.DestinationService;
import org.cobblestonemc.paper.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.joml.Vector3i;

/**
 * Surfaces Essentials teleports as navigation targets: {@code essentials → home → <name>} (one
 * destination per the player's homes) and {@code essentials → spawn}. Navigating to one is gated by
 * Cobblestone's own {@code cobblestone.navigate.essentials.*} permission (default-allow) rather
 * than the Essentials teleport permission — so a player can walk to a place even where {@code
 * /home}/{@code /spawn} is revoked (the teleport transition still requires the Essentials
 * permission). Destinations re-resolve on query, so moving a home or the spawn is reflected without
 * a restart.
 *
 * <p>The items these show in the navigation menu come from this plugin's config: by default each
 * home is a little house.
 */
final class EssentialsDestinationService implements DestinationService {

  static final String HOME_KEY = "home";
  static final String SPAWN_KEY = "spawn";

  /** A wooden house, from minecraft-heads.com. */
  static final String HOUSE =
      Icon.HEAD_PREFIX
          + "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjNkMDJjZGMwNzViYjFjYzVmNmZlM2M3NzExYWU0OTc3ZTM4YjkxMGQ1MGVkNjAyM2RmNzM5MTNlNWU3ZmNmZiJ9fX0=";

  private final Essentials essentials;
  private final FileConfiguration config;

  EssentialsDestinationService(Essentials essentials, FileConfiguration config) {
    this.essentials = essentials;
    this.config = config;
  }

  @Override
  public PlatformDestinationTree<World, Vector3i> provide(Player player) {
    Display house = display("home", HOUSE);
    DestinationTree homes = DestinationTree.builder().strict();
    for (String home : essentials.homes(player)) {
      homes
          .destination(home, () -> Destination.at(essentials.home(player, home), home))
          .display(home, house);
    }
    DestinationTree root =
        DestinationTree.builder()
            .children(HOME_KEY, homes)
            .display(HOME_KEY, display("homes", HOUSE));
    if (essentials.hasSpawn()) {
      root.destination(SPAWN_KEY, () -> Destination.at(essentials.spawn(player), "spawn"))
          .display(SPAWN_KEY, display("spawn", "minecraft:beacon"));
    }
    return root.build();
  }

  @Override
  public Display display(Player player) {
    return display("root", "minecraft:oak_door");
  }

  private Display display(String key, String fallback) {
    return Display.of(
        Icon.parse(config.getString("icons." + key, fallback))
            .or(() -> Icon.parse(fallback))
            .orElseThrow());
  }
}
