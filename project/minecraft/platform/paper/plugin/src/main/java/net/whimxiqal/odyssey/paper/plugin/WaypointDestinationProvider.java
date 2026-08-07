/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

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
import net.whimxiqal.odyssey.plugin.data.Waypoint;
import net.whimxiqal.odyssey.plugin.data.WaypointDao;
import net.whimxiqal.odyssey.plugin.destination.SimpleDestinationTree;
import net.whimxiqal.odyssey.plugin.destination.SimpleMinecraftDestination;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * The built-in {@link PaperDestinationProvider} for Odyssey-owned waypoints. It surfaces a {@code
 * waypoint} tree of the player's personal waypoints layered over the server-wide global ones (a
 * personal waypoint shadows a global one of the same name). Odyssey registers this as a Bukkit
 * service like any third-party provider.
 */
public final class WaypointDestinationProvider implements PaperDestinationProvider {

  /** The tree key under which waypoints are addressed (e.g. {@code /nav waypoint home}). */
  public static final String TREE_KEY = "waypoint";

  private final WaypointDao waypoints;

  /**
   * Creates the provider.
   *
   * @param waypoints the waypoint DAO to read from
   */
  public WaypointDestinationProvider(WaypointDao waypoints) {
    this.waypoints = waypoints;
  }

  @Override
  public DestinationTree<World, Vector3i> provide(Player player) {
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves = new LinkedHashMap<>();
    // Global first, then personal so a player's own waypoint shadows a global of the same name.
    for (Waypoint waypoint : waypoints.global()) {
      leaves.put(waypoint.name(), () -> toDestination(waypoint));
    }
    for (Waypoint waypoint : waypoints.ownedBy(player.getUniqueId())) {
      leaves.put(waypoint.name(), () -> toDestination(waypoint));
    }
    return new SimpleDestinationTree<>(TREE_KEY, false, Map.of(), leaves);
  }

  private static MinecraftDestination<World, Vector3i> toDestination(Waypoint waypoint) {
    NamespacedKey worldKey = NamespacedKey.fromString(waypoint.world());
    World world = worldKey == null ? null : Bukkit.getWorld(worldKey);
    Location location = new Location(world, waypoint.x(), waypoint.y(), waypoint.z());
    WorldRegion<World, Vector3i> region = SingleCellWorldRegion.of(location);
    Destination<WorldRegion<World, Vector3i>> destination = () -> List.of(region);
    return new SimpleMinecraftDestination<>(destination, Component.text(waypoint.name()), List.of());
  }
}
