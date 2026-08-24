/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.paper.api.SingleCellWorldRegion;
import org.cobblestonemc.paper.api.WholeWorldRegion;
import org.cobblestonemc.paper.plugin.api.DestinationService;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.cobblestonemc.plugin.data.DeathLocation;
import org.cobblestonemc.plugin.data.DeathLocationDao;
import org.cobblestonemc.plugin.data.LocationDao;
import org.cobblestonemc.plugin.destination.SimpleMinecraftDestination;
import org.cobblestonemc.plugin.destination.SimplePlatformDestinationTree;
import org.joml.Vector3i;

public class CobblestoneDestinationSource implements DestinationService {
  public static final String PLAYER_TREE_KEY = "player";
  public static final String WORLD_TREE_KEY = "world";
  public static final String LOCATION_TREE_KEY = "location";
  public static final String GLOBAL_TREE_KEY = "global";
  public static final String PRIVATE_TREE_KEY = "private";
  public static final String DEATH_TREE_KEY = "death";

  private final LocationDao locations;
  private final DeathLocationDao deaths;
  private final BooleanSupplier trackDeaths;

  public CobblestoneDestinationSource(
      LocationDao locations, DeathLocationDao deaths, BooleanSupplier trackDeaths) {
    this.locations = locations;
    this.deaths = deaths;
    this.trackDeaths = trackDeaths;
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
            LOCATION_TREE_KEY,
            () -> provideLocationDestinationTree(player)),
        deathLeaves(player));
  }

  /**
   * The player's last death, as a single {@code cobblestone death} leaf. Absent entirely when death
   * tracking is off or the player has not died since it was turned on, so {@code /navigate death}
   * reports an unknown destination rather than a route that cannot exist.
   */
  private Map<String, Supplier<MinecraftDestination<World, Vector3i>>> deathLeaves(Player player) {
    if (!trackDeaths.getAsBoolean()) {
      return Map.of();
    }
    return deaths
        .get(player.getUniqueId())
        .map(
            death ->
                Map.of(
                    DEATH_TREE_KEY,
                    (Supplier<MinecraftDestination<World, Vector3i>>) () -> toDestination(death)))
        .orElseGet(Map::of);
  }

  private static MinecraftDestination<World, Vector3i> toDestination(DeathLocation death) {
    NamespacedKey worldKey = NamespacedKey.fromString(death.world());
    World world = worldKey == null ? null : Bukkit.getWorld(worldKey);
    var bukkitLocation = new Location(world, death.x(), death.y(), death.z());
    WorldRegion<World, Vector3i> region = SingleCellWorldRegion.of(bukkitLocation);
    Destination<WorldRegion<World, Vector3i>> destination = () -> List.of(region);
    return new SimpleMinecraftDestination<>(destination, Component.text(DEATH_TREE_KEY), List.of());
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
   * Locations split by scope, so a player's own {@code home} and a server-wide {@code home} are
   * both reachable ({@code location private home} / {@code location global home}) rather than one
   * silently shadowing the other. Neither level is strict, so plain {@code location home} still
   * works whenever only one of the two exists.
   */
  private PlatformDestinationTree<World, Vector3i> provideLocationDestinationTree(Player player) {
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> globalLeaves =
        leaves(locations.global());
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> privateLeaves =
        leaves(locations.ownedBy(player.getUniqueId()));
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
  private static Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves(
      List<org.cobblestonemc.plugin.data.Location> locations) {
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves = new LinkedHashMap<>();
    for (var location : locations) {
      leaves.put(location.name(), toDestination(location));
    }
    return leaves;
  }

  private static Supplier<MinecraftDestination<World, Vector3i>> toDestination(
      org.cobblestonemc.plugin.data.Location location) {
    NamespacedKey worldKey = NamespacedKey.fromString(location.world());
    World world = worldKey == null ? null : Bukkit.getWorld(worldKey);
    var bukkitLocation = new Location(world, location.x(), location.y(), location.z());
    WorldRegion<World, Vector3i> region = SingleCellWorldRegion.of(bukkitLocation);
    Destination<WorldRegion<World, Vector3i>> destination = () -> List.of(region);
    return () ->
        new SimpleMinecraftDestination<>(destination, Component.text(location.name()), List.of());
  }
}
