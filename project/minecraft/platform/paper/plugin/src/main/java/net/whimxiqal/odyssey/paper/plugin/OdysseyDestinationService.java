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
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.paper.api.SingleCellWorldRegion;
import net.whimxiqal.odyssey.paper.api.WholeWorldRegion;
import net.whimxiqal.odyssey.paper.plugin.api.DestinationService;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import net.whimxiqal.odyssey.plugin.api.PlatformDestinationTree;
import net.whimxiqal.odyssey.plugin.data.Waypoint;
import net.whimxiqal.odyssey.plugin.data.WaypointDao;
import net.whimxiqal.odyssey.plugin.destination.SimpleMinecraftDestination;
import net.whimxiqal.odyssey.plugin.destination.SimplePlatformDestinationTree;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

public class OdysseyDestinationService implements DestinationService {
  public static final String PLAYER_TREE_KEY = "player";
  public static final String WORLD_TREE_KEY = "world";
  public static final String WAYPOINT_TREE_KEY = "waypoint";
  public static final String GLOBAL_TREE_KEY = "global";
  public static final String PRIVATE_TREE_KEY = "private";

  private final WaypointDao waypoints;

  public OdysseyDestinationService(WaypointDao waypoints) {
    this.waypoints = waypoints;
  }

  @Override
  public PlatformDestinationTree<World, Vector3i> provide(Player player) {
    return new SimplePlatformDestinationTree<>(
        false,
        Map.of(
            PLAYER_TREE_KEY,
            () -> providePlayerDestinationTree(player),
            WORLD_TREE_KEY,
            () -> provideWorldDestinationTree(player),
            WAYPOINT_TREE_KEY,
            () -> provideWaypointDestinationTree(player)),
        Map.of());
  }

  private PlatformDestinationTree<World, Vector3i> providePlayerDestinationTree(Player player) {
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
    return new SimplePlatformDestinationTree<>(false, Map.of(), leaves);
  }

  private PlatformDestinationTree<World, Vector3i> provideWorldDestinationTree(Player player) {
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
    return new SimplePlatformDestinationTree<>(false, Map.of(), leaves);
  }

  /**
   * Waypoints split by scope, so a player's own {@code home} and a server-wide {@code home} are
   * both reachable ({@code waypoint private home} / {@code waypoint global home}) rather than one
   * silently shadowing the other. Neither level is strict, so plain {@code waypoint home} still
   * works whenever only one of the two exists.
   */
  private PlatformDestinationTree<World, Vector3i> provideWaypointDestinationTree(Player player) {
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> globalLeaves =
        leaves(waypoints.global());
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> privateLeaves =
        leaves(waypoints.ownedBy(player.getUniqueId()));
    return new SimplePlatformDestinationTree<>(
        false,
        Map.of(
            GLOBAL_TREE_KEY,
            () -> new SimplePlatformDestinationTree<>(false, Map.of(), globalLeaves),
            PRIVATE_TREE_KEY,
            () -> new SimplePlatformDestinationTree<>(false, Map.of(), privateLeaves)),
        Map.of());
  }

  /** Waypoint names are unique within a scope (the table's primary key), so this cannot collide. */
  private static Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves(
      List<Waypoint> waypoints) {
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves = new LinkedHashMap<>();
    for (Waypoint waypoint : waypoints) {
      leaves.put(waypoint.name(), toDestination(waypoint));
    }
    return leaves;
  }

  private static Supplier<MinecraftDestination<World, Vector3i>> toDestination(Waypoint waypoint) {
    NamespacedKey worldKey = NamespacedKey.fromString(waypoint.world());
    World world = worldKey == null ? null : Bukkit.getWorld(worldKey);
    Location location = new Location(world, waypoint.x(), waypoint.y(), waypoint.z());
    WorldRegion<World, Vector3i> region = SingleCellWorldRegion.of(location);
    Destination<WorldRegion<World, Vector3i>> destination = () -> List.of(region);
    return () ->
        new SimpleMinecraftDestination<>(destination, Component.text(waypoint.name()), List.of());
  }
}
