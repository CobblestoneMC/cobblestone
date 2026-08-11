/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.essentials;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.paper.api.SingleCellWorldRegion;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestinationProvider;
import net.whimxiqal.odyssey.plugin.api.DestinationTree;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import net.whimxiqal.odyssey.plugin.destination.SimpleDestinationTree;
import net.whimxiqal.odyssey.plugin.destination.SimpleMinecraftDestination;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * Surfaces Essentials teleports as navigation targets: {@code essentials → home → <name>} (one leaf
 * per the player's homes) and {@code essentials → spawn}. Navigating to one is gated by Odyssey's own
 * {@code odyssey.navigate.essentials.*} permission (default-allow) rather than the Essentials teleport
 * permission — so a player can walk to a place even where {@code /home}/{@code /spawn} is revoked (the
 * teleport transition still requires the Essentials permission).
 *
 * <p>The destinations are re-resolved when queried (behind {@link Supplier}s), so moving a home or the
 * spawn is reflected without a restart.
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
    Map<String, Supplier<? extends DestinationTree<World, Vector3i>>> subTrees = new LinkedHashMap<>();
    subTrees.put(HOME_KEY, () -> homeTree(player));

    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves = new LinkedHashMap<>();
    if (essentials.hasSpawn()) {
      leaves.put(SPAWN_KEY, () -> destination(essentials.spawn(player), "spawn"));
    }
    return List.of(new SimpleDestinationTree<>(TREE_KEY, false, subTrees, leaves));
  }

  private DestinationTree<World, Vector3i> homeTree(Player player) {
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves = new LinkedHashMap<>();
    for (String home : essentials.homes(player)) {
      leaves.put(home, () -> destination(essentials.home(player, home), home));
    }
    return new SimpleDestinationTree<>(HOME_KEY, true, Map.of(), leaves);
  }

  /** Builds a single-cell destination at the given location, or an empty one if it is unavailable. */
  private static MinecraftDestination<World, Vector3i> destination(Location location, String name) {
    Destination<WorldRegion<World, Vector3i>> destination = location == null
        ? List::of
        : () -> List.of(SingleCellWorldRegion.of(location));
    return new SimpleMinecraftDestination<>(destination, Component.text(name), List.of());
  }
}
