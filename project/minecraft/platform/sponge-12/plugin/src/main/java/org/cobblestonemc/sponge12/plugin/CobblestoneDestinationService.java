/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.cobblestonemc.plugin.data.Location;
import org.cobblestonemc.plugin.data.LocationDao;
import org.cobblestonemc.plugin.destination.SimpleMinecraftDestination;
import org.cobblestonemc.plugin.destination.SimplePlatformDestinationTree;
import org.cobblestonemc.sponge12.api.SingleCellWorldRegion;
import org.cobblestonemc.sponge12.api.WholeWorldRegion;
import org.cobblestonemc.sponge12.plugin.api.DestinationService;
import org.cobblestonemc.sponge12.plugin.api.DestinationTree;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/** Surfaces online players, loaded worlds, and stored locations as navigable destinations. */
public final class CobblestoneDestinationService implements DestinationService {

  public static final String PLAYER_TREE_KEY = "player";
  public static final String WORLD_TREE_KEY = "world";
  public static final String LOCATION_TREE_KEY = "location";
  public static final String GLOBAL_TREE_KEY = "global";
  public static final String PRIVATE_TREE_KEY = "private";

  private final LocationDao locations;

  public CobblestoneDestinationService(LocationDao locations) {
    this.locations = locations;
  }

  @Override
  public PlatformDestinationTree<ServerWorld, Vector3i> provide(ServerPlayer player) {
    return new SimplePlatformDestinationTree<>(
        false,
        Map.of(
            PLAYER_TREE_KEY,
            () -> providePlayerDestinationTree(player),
            WORLD_TREE_KEY,
            () -> provideWorldDestinationTree(player),
            LOCATION_TREE_KEY,
            () -> provideLocationDestinationTree(player)),
        Map.of());
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
                  return target
                      .<List<WorldRegion<ServerWorld, Vector3i>>>map(
                          serverPlayer ->
                              List.of(SingleCellWorldRegion.of(serverPlayer.serverLocation())))
                      .orElseGet(List::of);
                };
            return new SimpleMinecraftDestination<>(
                destination, Component.text(name), List.of(), true);
          });
    }
    return new SimplePlatformDestinationTree<>(false, Map.of(), leaves);
  }

  private PlatformDestinationTree<ServerWorld, Vector3i> provideWorldDestinationTree(
      ServerPlayer player) {
    var currentKey = player.world().key();
    Map<String, Map<String, ServerWorld>> namespacedWorlds = new HashMap<>();
    for (var world : Sponge.server().worldManager().worlds()) {
      var key = world.key();
      if (key.equals(currentKey)) {
        continue; // no point navigating to the world you're already in
      }
      namespacedWorlds
          .computeIfAbsent(key.key().namespace(), k -> new HashMap<>())
          .put(key.value(), world);
    }
    var subTrees = DestinationTree.emptySubTrees();
    for (var namespace : namespacedWorlds.entrySet()) {
      var worldLeaves = DestinationTree.emptyLeaves();
      for (var value : namespace.getValue().entrySet()) {
        worldLeaves.put(
            value.getKey(),
            () -> {
              WorldRegion<ServerWorld, Vector3i> region =
                  new WholeWorldRegion(value.getValue().key().asString());
              Destination<WorldRegion<ServerWorld, Vector3i>> destination = () -> List.of(region);
              return new SimpleMinecraftDestination<>(
                  destination, Component.text(value.getValue().key().asString()), List.of());
            });
      }
      subTrees.put(
          namespace.getKey(),
          () -> new SimplePlatformDestinationTree<>(false, Map.of(), worldLeaves));
    }
    return new SimplePlatformDestinationTree<>(false, subTrees, Map.of());
  }

  /**
   * Locations split by scope, so a player's own {@code home} and a server-wide {@code home} are
   * both reachable ({@code location private home} / {@code location global home}) rather than one
   * silently shadowing the other. Neither level is strict, so plain {@code location home} still
   * works whenever only one of the two exists.
   */
  private PlatformDestinationTree<ServerWorld, Vector3i> provideLocationDestinationTree(
      ServerPlayer player) {
    Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> globalLeaves =
        leaves(locations.global());
    Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> privateLeaves =
        leaves(locations.ownedBy(player.uniqueId()));
    return new SimplePlatformDestinationTree<>(
        false,
        Map.of(
            GLOBAL_TREE_KEY,
            () -> new SimplePlatformDestinationTree<>(false, Map.of(), globalLeaves),
            PRIVATE_TREE_KEY,
            () -> new SimplePlatformDestinationTree<>(false, Map.of(), privateLeaves)),
        Map.of());
  }

  /** Location names are unique within a scope (the table's primary key), so this cannot collide. */
  private static Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> leaves(
      List<Location> locations) {
    Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> leaves =
        new LinkedHashMap<>();
    for (Location location : locations) {
      leaves.put(location.name(), () -> toDestination(location));
    }
    return leaves;
  }

  private static MinecraftDestination<ServerWorld, Vector3i> toDestination(Location location) {
    Destination<WorldRegion<ServerWorld, Vector3i>> destination =
        () -> {
          ServerWorld world =
              Sponge.server()
                  .worldManager()
                  .world(ResourceKey.resolve(location.world()))
                  .orElse(null);
          return world == null
              ? List.of()
              : List.of(
                  SingleCellWorldRegion.of(
                      ServerLocation.of(world, location.x(), location.y(), location.z())));
        };
    return new SimpleMinecraftDestination<>(
        destination, Component.text(location.name()), List.of());
  }
}
