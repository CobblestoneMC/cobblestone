/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.Validity;
import org.cobblestonemc.plugin.data.DeathLocationDao;
import org.cobblestonemc.plugin.data.Location;
import org.cobblestonemc.plugin.data.LocationDao;
import org.cobblestonemc.plugin.destination.DestinationDetails;
import org.cobblestonemc.plugin.destination.DestinationNode;
import org.cobblestonemc.plugin.destination.DestinationProvider;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Message0;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.sponge.SpongeRegions;
import org.cobblestonemc.sponge.api.WholeWorldRegion;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;

/**
 * Cobblestone's own destinations: saved locations, other players, worlds, and the player's last
 * death. Written in Cobblestone's own terms ({@link NavigationTarget}) rather than adapted from the
 * integration API.
 */
public final class BuiltInDestinations implements DestinationProvider<ServerPlayer> {

  public static final String PLAYER_TREE_KEY = "player";
  public static final String WORLD_TREE_KEY = "world";
  public static final String LOCATION_TREE_KEY = "location";
  public static final String GLOBAL_TREE_KEY = "global";
  public static final String PRIVATE_TREE_KEY = "private";
  public static final String DEATH_KEY = "death";

  private final LocationDao locations;
  private final DeathLocationDao deaths;
  private final SpongeRegions regions;
  private final BooleanSupplier trackDeaths;
  private final Messages messages;

  public BuiltInDestinations(
      LocationDao locations,
      DeathLocationDao deaths,
      SpongeRegions regions,
      BooleanSupplier trackDeaths,
      Messages messages) {
    this.locations = locations;
    this.deaths = deaths;
    this.regions = regions;
    this.trackDeaths = trackDeaths;
    this.messages = messages;
  }

  @Override
  public DestinationNode<NavigationTarget> provide(ServerPlayer player) {
    DestinationNode.Builder<NavigationTarget> root = DestinationNode.builder();
    // With death tracking off there is never a death to go back to, so the key is not offered.
    if (trackDeaths.getAsBoolean()) {
      root.leaf(DEATH_KEY, DestinationLeaf.ofValidity(() -> death(player)));
    }
    return root.subtree(PLAYER_TREE_KEY, () -> players(player))
        .subtree(WORLD_TREE_KEY, () -> worlds(player))
        .subtree(LOCATION_TREE_KEY, () -> locations(player))
        .build();
  }

  /** The player's last death; invalid if none is on record. */
  private Validity<NavigationTarget> death(ServerPlayer player) {
    return deaths
        .get(player.uniqueId())
        .map(death -> stored(DEATH_KEY, death.world(), death.x(), death.y(), death.z()))
        .map(Validity::valid)
        .orElseGet(() -> invalid(player, CobblestoneMessages.NAVIGATE_NO_DEATH));
  }

  private DestinationNode<NavigationTarget> players(ServerPlayer player) {
    UUID self = player.uniqueId();
    DestinationNode.Builder<NavigationTarget> node = DestinationNode.builder();
    for (ServerPlayer other : Sponge.server().onlinePlayers()) {
      if (other.uniqueId().equals(self)) {
        continue;
      }
      UUID uuid = other.uniqueId();
      String name = other.name();
      node.leaf(
          name,
          DestinationLeaf.of(
              () ->
                  // Re-resolves the player (and their location) on each search, so live trips
                  // track them, and ends the trip if they log off.
                  NavigationTarget.builder(
                          name,
                          () ->
                              Sponge.server()
                                  .player(uuid)
                                  .map(target -> regions.block(target.serverLocation()))
                                  .stream()
                                  .toList())
                      .mobile()
                      .existence(() -> Sponge.server().player(uuid).isPresent())
                      .build()));
    }
    return node.build();
  }

  private DestinationNode<NavigationTarget> worlds(ServerPlayer player) {
    var currentKey = player.world().key();
    Map<String, DestinationNode.Builder<NavigationTarget>> namespaces = new HashMap<>();
    for (var world : Sponge.server().worldManager().worlds()) {
      var key = world.key();
      if (key.equals(currentKey)) {
        continue; // no point navigating to the world you're already in
      }
      String worldKey = key.asString();
      namespaces
          .computeIfAbsent(key.namespace(), namespace -> DestinationNode.builder())
          .leaf(
              key.value(),
              DestinationLeaf.of(
                  () ->
                      NavigationTarget.builder(
                              worldKey,
                              () -> regions.of(new WholeWorldRegion(worldKey)).stream().toList())
                          .build()));
    }
    DestinationNode.Builder<NavigationTarget> node = DestinationNode.builder();
    namespaces.forEach((namespace, worlds) -> node.subtree(namespace, worlds::build));
    return node.build();
  }

  /**
   * Locations split by scope, so a player's own {@code home} and a server-wide {@code home} are
   * both reachable ({@code location private home} / {@code location global home}) rather than one
   * silently shadowing the other. Neither level is strict, so plain {@code location home} still
   * works whenever only one of the two exists.
   */
  private DestinationNode<NavigationTarget> locations(ServerPlayer player) {
    return DestinationNode.<NavigationTarget>builder()
        .subtree(GLOBAL_TREE_KEY, () -> locations(locations.global()))
        .subtree(PRIVATE_TREE_KEY, () -> locations(locations.ownedBy(player.uniqueId())))
        .build();
  }

  /** Location names are unique within a scope (the table's primary key), so this cannot collide. */
  private DestinationNode<NavigationTarget> locations(List<Location> locations) {
    DestinationNode.Builder<NavigationTarget> node = DestinationNode.builder();
    for (Location location : locations) {
      node.leaf(
          location.name(),
          DestinationLeaf.of(
              () ->
                  stored(
                      location.name(),
                      location.world(),
                      location.x(),
                      location.y(),
                      location.z())));
    }
    return node.build();
  }

  /**
   * A block recorded by world key, with its coordinates in the hover detail. The world is looked up
   * on each search, so one that is not loaded is simply not searched.
   */
  private NavigationTarget stored(String name, String worldKey, int x, int y, int z) {
    return NavigationTarget.builder(
            name,
            () ->
                world(worldKey)
                    .map(world -> regions.block(ServerLocation.of(world, x, y, z)))
                    .stream()
                    .toList())
        .detail(DestinationDetails.coordinates(x, y, z, worldKey))
        .build();
  }

  private Validity<NavigationTarget> invalid(ServerPlayer player, Message0 reason) {
    return Validity.invalid(render(player, reason));
  }

  private Component render(ServerPlayer player, Message0 message) {
    return messages.render(player.locale(), message);
  }

  private static Optional<ServerWorld> world(String key) {
    return Sponge.server().worldManager().world(ResourceKey.resolve(key));
  }
}
