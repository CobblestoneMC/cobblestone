/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.cobblestonemc.paper.PaperRegions;
import org.cobblestonemc.paper.PaperTerrain;
import org.cobblestonemc.paper.api.WholeWorldRegion;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.cobblestonemc.plugin.api.Validity;
import org.cobblestonemc.plugin.data.DeathLocationDao;
import org.cobblestonemc.plugin.data.LocationDao;
import org.cobblestonemc.plugin.destination.DestinationDetails;
import org.cobblestonemc.plugin.destination.DestinationNode;
import org.cobblestonemc.plugin.destination.DestinationProvider;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Message0;
import org.cobblestonemc.plugin.message.Message1;
import org.cobblestonemc.plugin.message.Message3;
import org.cobblestonemc.plugin.message.Messages;

/**
 * Cobblestone's own destinations: saved locations, other players, worlds, and places such as the
 * player's last death or the block they are looking at. Written in Cobblestone's own terms ({@link
 * NavigationTarget}) rather than adapted from the integration API.
 */
public class BuiltInDestinations implements DestinationProvider<Player> {
  public static final String PLAYER_TREE_KEY = "player";
  public static final String WORLD_TREE_KEY = "world";
  public static final String LOCATION_TREE_KEY = "location";
  public static final String GLOBAL_TREE_KEY = "global";
  public static final String PRIVATE_TREE_KEY = "private";
  public static final String COORDINATES_TREE_KEY = "coordinates";
  public static final String DEATH_KEY = "death";
  public static final String TARGET_KEY = "target";
  public static final String BLOCK_KEY = "block";
  public static final String SPAWN_KEY = "spawn";
  public static final String RESPAWN_KEY = "respawn";

  /** How far away, in blocks, the block a player is looking at may be for {@code target}. */
  static final int TARGET_RANGE = 256;

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
    return root.leaf(TARGET_KEY, DestinationLeaf.ofValidity(() -> target(player)))
        .leaf(SPAWN_KEY, spawn(player))
        .leaf(RESPAWN_KEY, DestinationLeaf.ofValidity(() -> respawn(player)))
        .subtree(PLAYER_TREE_KEY, () -> players(player))
        .subtree(WORLD_TREE_KEY, () -> worlds(player))
        .subtree(LOCATION_TREE_KEY, () -> locations(player))
        .subtree(COORDINATES_TREE_KEY, () -> coordinates(player))
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

  /**
   * Where the player is looking: the open space in front of the block their crosshair is on, or the
   * ground beneath it if that space is in mid-air (the side of a wall, say). Water stops the look
   * like any block, so looking out to sea means its surface (unless the player is underwater);
   * plants and other blocks without a collision shape are looked through. Invalid if no block is
   * within {@link #TARGET_RANGE}.
   */
  private Validity<NavigationTarget> target(Player player) {
    Location eye = player.getEyeLocation();
    World world = eye.getWorld();
    // From underwater, look through the water rather than at it.
    FluidCollisionMode fluids =
        eye.getBlock().isLiquid() ? FluidCollisionMode.NEVER : FluidCollisionMode.ALWAYS;
    RayTraceResult hit = world.rayTraceBlocks(eye, eye.getDirection(), TARGET_RANGE, fluids, true);
    Block block = hit == null ? null : hit.getHitBlock();
    if (block == null) {
      return Validity.invalid(render(player, CobblestoneMessages.NAVIGATE_NO_TARGET, TARGET_RANGE));
    }
    BlockFace face = hit.getHitBlockFace();
    Block front = block.getRelative(face == null ? BlockFace.UP : face);
    int y = PaperTerrain.landingY(world, front.getX(), front.getY(), front.getZ());
    return Validity.valid(atBlock(TARGET_KEY, new Location(world, front.getX(), y, front.getZ())));
  }

  /**
   * {@code coordinates block <x> <y> <z> [world]}. Strict, so {@code coordinates} must be typed: a
   * bare {@code block} would mean nothing.
   */
  private DestinationNode<NavigationTarget> coordinates(Player player) {
    return DestinationNode.<NavigationTarget>builder()
        .strict()
        .leaf(BLOCK_KEY, coordinateBlock(player))
        .build();
  }

  /** One block, in the player's world unless named; invalid above or below the world. */
  private DestinationLeaf<NavigationTarget> coordinateBlock(Player player) {
    DestinationOption<Integer> x = DestinationOption.integer("x", () -> blockX(player));
    DestinationOption<Integer> y = DestinationOption.integer("y", () -> blockY(player));
    DestinationOption<Integer> z = DestinationOption.integer("z", () -> blockZ(player));
    DestinationOption<World> world = worldOption();
    return DestinationLeaf.withOptions(
        List.of(x, y, z, world),
        values -> {
          World target = values.find(world).orElseGet(player::getWorld);
          int blockY = values.get(y);
          if (blockY < target.getMinHeight() || blockY >= target.getMaxHeight()) {
            return Validity.invalid(
                render(
                    player,
                    CobblestoneMessages.NAVIGATE_OUTSIDE_WORLD,
                    target.getMinHeight(),
                    target.getMaxHeight() - 1,
                    target.getName()));
          }
          return Validity.valid(
              atBlock(BLOCK_KEY, new Location(target, values.get(x), blockY, values.get(z))));
        });
  }

  /** {@code spawn [world]}: the world's spawn point, in the player's world unless named. */
  private DestinationLeaf<NavigationTarget> spawn(Player player) {
    DestinationOption<World> world = worldOption();
    return DestinationLeaf.withOptions(
        List.of(world),
        values -> {
          World target = values.find(world).orElseGet(player::getWorld);
          return Validity.valid(atBlock(SPAWN_KEY, target.getSpawnLocation()));
        });
  }

  /**
   * Where the player will respawn — their bed, a charged respawn anchor, or wherever a plugin or
   * {@code /spawnpoint} set it. Read without validating it (that would load its chunk), so an
   * obstructed bed still counts. Invalid if the player has none.
   */
  private Validity<NavigationTarget> respawn(Player player) {
    Location respawn = player.getRespawnLocation(false);
    if (respawn == null || respawn.getWorld() == null) {
      return invalid(player, CobblestoneMessages.NAVIGATE_NO_RESPAWN);
    }
    return Validity.valid(atBlock(RESPAWN_KEY, respawn));
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

  /** A stationary destination at one block, with its coordinates in the hover detail. */
  private NavigationTarget atBlock(String name, Location location) {
    return NavigationTarget.builder(name, () -> regions.block(location).stream().toList())
        .detail(
            DestinationDetails.coordinates(
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                location.getWorld().getKey().asString()))
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

  private Component render(Player player, Message1 message, Object arg) {
    return messages.render(player.locale(), message, arg);
  }

  private Component render(Player player, Message3 message, Object arg1, Object arg2, Object arg3) {
    return messages.render(player.locale(), message, arg1, arg2, arg3);
  }

  /** An optional trailing world, by name or namespaced key. */
  private static DestinationOption<World> worldOption() {
    return DestinationOption.optional(
        "world",
        partial -> Bukkit.getWorlds().stream().map(World::getName).toList(),
        token -> {
          World byName = Bukkit.getWorld(token);
          if (byName != null) {
            return Optional.of(byName);
          }
          NamespacedKey key = NamespacedKey.fromString(token.toLowerCase(Locale.ROOT));
          return Optional.ofNullable(key == null ? null : Bukkit.getWorld(key));
        });
  }

  private static int blockX(Player player) {
    return player.getLocation().getBlockX();
  }

  private static int blockY(Player player) {
    return player.getLocation().getBlockY();
  }

  private static int blockZ(Player player) {
    return player.getLocation().getBlockZ();
  }
}
