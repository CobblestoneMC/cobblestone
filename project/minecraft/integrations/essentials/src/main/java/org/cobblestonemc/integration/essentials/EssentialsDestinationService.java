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
import org.cobblestonemc.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.joml.Vector3i;

/**
 * Surfaces Essentials teleports as navigation targets: {@code cobblestoneessentials → home →
 * <name>} (one leaf per the player's homes) and {@code cobblestoneessentials → spawn}. Navigating
 * to one is gated by Cobblestone's own {@code cobblestone.navigate.cobblestoneessentials.*}
 * permission (default-allow) rather than the Essentials teleport permission — so a player can walk
 * to a place even where {@code /home}/{@code /spawn} is revoked (the teleport transition still
 * requires the Essentials permission). Destinations re-resolve on query, so moving a home or the
 * spawn is reflected without a restart.
 *
 * <p>How each entry looks in the navigation menu comes from this plugin's config.
 */
final class EssentialsDestinationService implements DestinationService {

  static final String HOME_KEY = "home";
  static final String SPAWN_KEY = "spawn";

  private final Essentials essentials;
  private final FileConfiguration config;

  EssentialsDestinationService(Essentials essentials, FileConfiguration config) {
    this.essentials = essentials;
    this.config = config;
  }

  @Override
  public DestinationTree<MinecraftDestination<World, Vector3i>> provide(Player player) {
    Display home = display("home");
    var homes = Destination.tree().strict();
    for (String name : essentials.homes(player)) {
      homes.entry(
          name,
          Destination.entry()
              .destination(() -> Destination.at(essentials.home(player, name), name))
              .display(home));
    }
    var root =
        Destination.tree()
            .entry(HOME_KEY, Destination.entry().subtree(homes).display(display("homes")));
    if (essentials.hasSpawn()) {
      root.entry(
          SPAWN_KEY,
          Destination.entry()
              .destination(() -> Destination.at(essentials.spawn(player), "spawn"))
              .display(display("spawn")));
    }
    return root.build();
  }

  @Override
  public Display display(Player player) {
    return display("root");
  }

  /** The configured item for an entry, or the menu's own default if it is left blank. */
  private Display display(String key) {
    return Icon.parse(config.getString("icons." + key)).map(Display::of).orElse(Display.none());
  }
}
