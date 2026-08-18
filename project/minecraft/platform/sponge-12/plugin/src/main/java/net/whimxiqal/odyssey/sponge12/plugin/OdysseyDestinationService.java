/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import net.whimxiqal.odyssey.plugin.api.PlatformDestinationTree;
import net.whimxiqal.odyssey.plugin.data.Waypoint;
import net.whimxiqal.odyssey.plugin.data.WaypointDao;
import net.whimxiqal.odyssey.plugin.destination.SimpleMinecraftDestination;
import net.whimxiqal.odyssey.plugin.destination.SimplePlatformDestinationTree;
import net.whimxiqal.odyssey.sponge12.api.SingleCellWorldRegion;
import net.whimxiqal.odyssey.sponge12.api.WholeWorldRegion;
import net.whimxiqal.odyssey.sponge12.plugin.api.DestinationService;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/** Surfaces online players, loaded worlds, and stored waypoints as navigable destinations. */
public final class OdysseyDestinationService implements DestinationService {

  public static final String PLAYER_TREE_KEY = "player";
  public static final String WORLD_TREE_KEY = "world";
  public static final String WAYPOINT_TREE_KEY = "waypoint";

  private final WaypointDao waypoints;

  public OdysseyDestinationService(WaypointDao waypoints) {
    this.waypoints = waypoints;
  }

  @Override
  public Collection<PlatformDestinationTree<ServerWorld, Vector3i>> provide(ServerPlayer player) {
    return List.of(
        providePlayerDestinationTree(player),
        provideWorldDestinationTree(player),
        provideWaypointDestinationTree(player));
  }

  private PlatformDestinationTree<ServerWorld, Vector3i> providePlayerDestinationTree(
      ServerPlayer player) {
    UUID self = player.uniqueId();
    Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> leaves =
        new LinkedHashMap<>();
    for (ServerPlayer other : Sponge.server().onlinePlayers()) {
      if (other.uniqueId().equals(self)) {
        continue;
      }
      UUID uuid = other.uniqueId();
      String name = other.name();
      leaves.put(
          name,
          () -> {
            // Re-resolves the player (and their location) each query, so live trips track them.
            Destination<WorldRegion<ServerWorld, Vector3i>> destination =
                () -> {
                  Optional<ServerPlayer> target = Sponge.server().player(uuid);
                  return target.isEmpty()
                      ? List.of()
                      : List.of(SingleCellWorldRegion.of(target.get().serverLocation()));
                };
            return new SimpleMinecraftDestination<>(
                destination, Component.text(name), List.of(), true);
          });
    }
    return new SimplePlatformDestinationTree<>(PLAYER_TREE_KEY, false, Map.of(), leaves);
  }

  private PlatformDestinationTree<ServerWorld, Vector3i> provideWorldDestinationTree(
      ServerPlayer player) {
    String currentKey = player.world().key().asString();
    Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> leaves =
        new LinkedHashMap<>();
    for (ServerWorld world : Sponge.server().worldManager().worlds()) {
      String worldKey = world.key().asString();
      if (worldKey.equals(currentKey)) {
        continue; // no point navigating to the world you're already in
      }
      leaves.put(
          worldKey,
          () -> {
            WorldRegion<ServerWorld, Vector3i> region = new WholeWorldRegion(worldKey);
            Destination<WorldRegion<ServerWorld, Vector3i>> destination = () -> List.of(region);
            return new SimpleMinecraftDestination<>(
                destination, Component.text(worldKey), List.of());
          });
    }
    return new SimplePlatformDestinationTree<>(WORLD_TREE_KEY, false, Map.of(), leaves);
  }

  private PlatformDestinationTree<ServerWorld, Vector3i> provideWaypointDestinationTree(
      ServerPlayer player) {
    Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> leaves =
        new LinkedHashMap<>();
    // Global first, then personal so a player's own waypoint shadows a global of the same name.
    for (Waypoint waypoint : waypoints.global()) {
      leaves.put(waypoint.name(), () -> toDestination(waypoint));
    }
    for (Waypoint waypoint : waypoints.ownedBy(player.uniqueId())) {
      leaves.put(waypoint.name(), () -> toDestination(waypoint));
    }
    return new SimplePlatformDestinationTree<>(WAYPOINT_TREE_KEY, false, Map.of(), leaves);
  }

  private static MinecraftDestination<ServerWorld, Vector3i> toDestination(Waypoint waypoint) {
    Destination<WorldRegion<ServerWorld, Vector3i>> destination =
        () -> {
          ServerWorld world =
              Sponge.server()
                  .worldManager()
                  .world(ResourceKey.resolve(waypoint.world()))
                  .orElse(null);
          return world == null
              ? List.of()
              : List.of(
                  SingleCellWorldRegion.of(
                      ServerLocation.of(world, waypoint.x(), waypoint.y(), waypoint.z())));
        };
    return new SimpleMinecraftDestination<>(
        destination, Component.text(waypoint.name()), List.of());
  }
}
