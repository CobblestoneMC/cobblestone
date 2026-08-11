/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.paper.api.SingleCellWorldRegion;
import net.whimxiqal.odyssey.paper.api.WholeWorldRegion;
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

public class OdysseyDestinationProvider implements PaperDestinationProvider {
  public static final String PLAYER_TREE_KEY = "player";
  public static final String WORLD_TREE_KEY = "world";
  public static final String WAYPOINT_TREE_KEY = "waypoint";

  private final WaypointDao waypoints;

  public OdysseyDestinationProvider(WaypointDao waypoints) {
    this.waypoints = waypoints;
  }

  @Override
  public Collection<DestinationTree<World, Vector3i>> provide(Player player) {
    return List.of(
        providePlayerDestinationTree(player),
        provideWorldDestinationTree(player),
        provideWaypointDestinationTree(player));
  }

  private DestinationTree<World, Vector3i> providePlayerDestinationTree(Player player) {
    UUID self = player.getUniqueId();
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves = new LinkedHashMap<>();
    for (Player other : Bukkit.getOnlinePlayers()) {
      if (other.getUniqueId().equals(self)) {
        continue;
      }
      UUID uuid = other.getUniqueId();
      String name = other.getName();
      leaves.put(
          name,
          () -> {
            // Re-resolves the player (and their current location) on each query, so live trips
            // track them.
            Destination<WorldRegion<World, Vector3i>> destination =
                () -> {
                  Player target = Bukkit.getPlayer(uuid);
                  return target == null
                      ? List.of()
                      : List.of(SingleCellWorldRegion.of(target.getLocation()));
                };
            return new SimpleMinecraftDestination<>(
                destination, Component.text(name), List.of(), true);
          });
    }
    return new SimpleDestinationTree<>(PLAYER_TREE_KEY, false, Map.of(), leaves);
  }

  private DestinationTree<World, Vector3i> provideWorldDestinationTree(Player player) {
    String currentKey = player.getWorld().getKey().asString();
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves = new LinkedHashMap<>();
    for (World world : Bukkit.getWorlds()) {
      String worldKey = world.getKey().asString();
      if (worldKey.equals(currentKey)) {
        continue; // no point navigating to the world you're already in
      }
      String name = world.getName();
      leaves.put(
          name,
          () -> {
            WorldRegion<World, Vector3i> region = new WholeWorldRegion(worldKey);
            Destination<WorldRegion<World, Vector3i>> destination = () -> List.of(region);
            return new SimpleMinecraftDestination<>(destination, Component.text(name), List.of());
          });
    }
    return new SimpleDestinationTree<>(WORLD_TREE_KEY, false, Map.of(), leaves);
  }

  private DestinationTree<World, Vector3i> provideWaypointDestinationTree(Player player) {
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves = new LinkedHashMap<>();
    // Global first, then personal so a player's own waypoint shadows a global of the same name.
    for (Waypoint waypoint : waypoints.global()) {
      leaves.put(waypoint.name(), () -> toDestination(waypoint));
    }
    for (Waypoint waypoint : waypoints.ownedBy(player.getUniqueId())) {
      leaves.put(waypoint.name(), () -> toDestination(waypoint));
    }
    return new SimpleDestinationTree<>(WAYPOINT_TREE_KEY, false, Map.of(), leaves);
  }

  private static MinecraftDestination<World, Vector3i> toDestination(Waypoint waypoint) {
    NamespacedKey worldKey = NamespacedKey.fromString(waypoint.world());
    World world = worldKey == null ? null : Bukkit.getWorld(worldKey);
    Location location = new Location(world, waypoint.x(), waypoint.y(), waypoint.z());
    WorldRegion<World, Vector3i> region = SingleCellWorldRegion.of(location);
    Destination<WorldRegion<World, Vector3i>> destination = () -> List.of(region);
    return new SimpleMinecraftDestination<>(
        destination, Component.text(waypoint.name()), List.of());
  }
}
