/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.cobblestonemc.paper.PaperRegions;
import org.cobblestonemc.paper.api.WholeWorldRegion;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.Validity;
import org.cobblestonemc.plugin.data.DeathLocationDao;
import org.cobblestonemc.plugin.data.LocationDao;
import org.cobblestonemc.plugin.destination.DestinationDetails;
import org.cobblestonemc.plugin.destination.DestinationNode;
import org.cobblestonemc.plugin.destination.DestinationProvider;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Message0;
import org.cobblestonemc.plugin.message.Messages;

/**
 * Cobblestone's own destinations: saved locations, other players, worlds, and the player's last
 * death. Written in Cobblestone's own terms ({@link NavigationTarget}) rather than adapted from the
 * integration API.
 */
public class BuiltInDestinations implements DestinationProvider<Player> {
  public static final String PLAYER_TREE_KEY = "player";
  public static final String WORLD_TREE_KEY = "world";
  public static final String LOCATION_TREE_KEY = "location";
  public static final String GLOBAL_TREE_KEY = "global";
  public static final String PRIVATE_TREE_KEY = "private";
  public static final String DEATH_KEY = "death";

  private final LocationDao locations;
  private final DeathLocationDao deaths;
  private final PaperRegions regions;
  private final BooleanSupplier trackDeaths;
  private final Messages messages;

  public BuiltInDestinations(
      LocationDao locations,
      DeathLocationDao deaths,
      PaperRegions regions,
      BooleanSupplier trackDeaths,
      Messages messages) {
    this.locations = locations;
    this.deaths = deaths;
    this.regions = regions;
    this.trackDeaths = trackDeaths;
    this.messages = messages;
  }

  @Override
  public DestinationNode<NavigationTarget> provide(Player player) {
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
  private Validity<NavigationTarget> death(Player player) {
    return deaths
        .get(player.getUniqueId())
        .map(death -> stored(DEATH_KEY, death.world(), death.x(), death.y(), death.z()))
        .map(Validity::valid)
        .orElseGet(() -> invalid(player, CobblestoneMessages.NAVIGATE_NO_DEATH));
  }

  private DestinationNode<NavigationTarget> players(Player player) {
    UUID self = player.getUniqueId();
    DestinationNode.Builder<NavigationTarget> node = DestinationNode.builder();
    for (Player other : Bukkit.getOnlinePlayers()) {
      if (other.getUniqueId().equals(self)) {
        continue;
      }
      UUID uuid = other.getUniqueId();
      String name = other.getName();
      node.leaf(
          name,
          DestinationLeaf.of(
              () ->
                  // Re-resolves the player (and their current location) on each search, so live
                  // trips track them, and ends the trip if they log off.
                  NavigationTarget.builder(
                          name,
                          () ->
                              Optional.ofNullable(Bukkit.getPlayer(uuid))
                                  .flatMap(target -> regions.block(target.getLocation()))
                                  .stream()
                                  .toList())
                      .mobile()
                      .existence(() -> Bukkit.getPlayer(uuid) != null)
                      .build()));
    }
    return node.build();
  }

  private DestinationNode<NavigationTarget> worlds(Player player) {
    String currentKey = player.getWorld().getKey().asString();
    DestinationNode.Builder<NavigationTarget> node = DestinationNode.builder();
    for (World world : Bukkit.getWorlds()) {
      String worldKey = world.getKey().asString();
      if (worldKey.equals(currentKey)) {
        continue; // no point navigating to the world you're already in
      }
      String name = world.getName();
      node.leaf(
          name,
          DestinationLeaf.of(
              () ->
                  NavigationTarget.builder(
                          name, () -> regions.of(new WholeWorldRegion(worldKey)).stream().toList())
                      .build()));
    }
    return node.build();
  }

  /**
   * Locations split by scope, so a player's own {@code home} and a server-wide {@code home} are
   * both reachable ({@code location private home} / {@code location global home}) rather than one
   * silently shadowing the other. Neither level is strict, so plain {@code location home} still
   * works whenever only one of the two exists.
   */
  private DestinationNode<NavigationTarget> locations(Player player) {
    return DestinationNode.<NavigationTarget>builder()
        .subtree(GLOBAL_TREE_KEY, () -> locations(locations.global()))
        .subtree(PRIVATE_TREE_KEY, () -> locations(locations.ownedBy(player.getUniqueId())))
        .build();
  }

  /** Location names are unique within a scope (the table's primary key), so this cannot collide. */
  private DestinationNode<NavigationTarget> locations(
      List<org.cobblestonemc.plugin.data.Location> locations) {
    DestinationNode.Builder<NavigationTarget> node = DestinationNode.builder();
    for (var location : locations) {
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
            name, () -> regions.block(new Location(world(worldKey), x, y, z)).stream().toList())
        .detail(DestinationDetails.coordinates(x, y, z, worldKey))
        .build();
  }

  /** The loaded world with the given namespaced key, or {@code null}. */
  private static World world(String key) {
    NamespacedKey worldKey = NamespacedKey.fromString(key);
    return worldKey == null ? null : Bukkit.getWorld(worldKey);
  }

  private Validity<NavigationTarget> invalid(Player player, Message0 reason) {
    return Validity.invalid(render(player, reason));
  }

  private Component render(Player player, Message0 message) {
    return messages.render(player.locale(), message);
  }
}
