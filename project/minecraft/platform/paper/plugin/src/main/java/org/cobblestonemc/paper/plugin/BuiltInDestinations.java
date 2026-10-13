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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
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
 * player's last death or the surface above them. Written in Cobblestone's own terms ({@link
 * NavigationTarget}) rather than an integration's, so they can use regions an integration's region
 * type cannot express — like the surface, whose extent depends on the blocks.
 */
public class BuiltInDestinations implements DestinationProvider<Player> {
  public static final String PLAYER_TREE_KEY = "player";
  public static final String WORLD_TREE_KEY = "world";
  public static final String LOCATION_TREE_KEY = "location";
  public static final String GLOBAL_TREE_KEY = "global";
  public static final String PRIVATE_TREE_KEY = "private";
  public static final String COORDINATES_TREE_KEY = "coordinates";
  public static final String DEATH_KEY = "death";
  public static final String SURFACE_KEY = "surface";
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
    return root.leaf(SURFACE_KEY, DestinationLeaf.ofValidity(() -> surface(player)))
        .leaf(TARGET_KEY, DestinationLeaf.ofValidity(() -> target(player)))
        .leaf(SPAWN_KEY, DestinationLeaf.of(this::spawn))
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
   * Anywhere open to the sky, in the player's current world; invalid if the player is already out
   * in the open.
   */
  private Validity<NavigationTarget> surface(Player player) {
    Location at = player.getLocation();
    World world = at.getWorld();
    if (PaperTerrain.isOpenToSky(world, at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
      return invalid(player, CobblestoneMessages.NAVIGATE_ALREADY_ON_SURFACE);
    }
    return Validity.valid(
        NavigationTarget.builder(SURFACE_KEY, () -> List.of(regions.surface(world))).build());
  }

  /**
   * Where the player is looking: the open space in front of the block their crosshair is on, or the
   * ground beneath it if that space is in mid-air (the side of a wall, say). Water stops the look
   * like any block, so looking out to sea means its surface (unless the player is underwater);
   * plants and other passable blocks are looked through. The look never reaches into an unloaded
   * chunk, nor (on Folia) one another region owns. Invalid if no block is within {@link
   * #TARGET_RANGE}.
   */
  private Validity<NavigationTarget> target(Player player) {
    Location eye = player.getEyeLocation();
    World world = eye.getWorld();
    // From underwater, look through the water rather than at it.
    boolean underwater = eye.getBlock().isLiquid();
    int[] front = openBlockBefore(world, eye.toVector(), eye.getDirection(), underwater);
    if (front == null) {
      return Validity.invalid(render(player, CobblestoneMessages.NAVIGATE_NO_TARGET, TARGET_RANGE));
    }
    int y = PaperTerrain.landingY(world, front[0], front[1], front[2]);
    return Validity.valid(atBlock(TARGET_KEY, new Location(world, front[0], y, front[2])));
  }

  /**
   * Walks a look block by block, in the order it passes through them, and returns the block just
   * before the first one that stops it ({@code {x, y, z}}), or {@code null} if nothing does within
   * {@link #TARGET_RANGE}. Bukkit's own ray trace would load every chunk on the way, so this stops
   * instead at the first chunk that is not loaded or (on Folia) not this region's to read.
   */
  private static int[] openBlockBefore(
      World world, Vector from, Vector direction, boolean underwater) {
    int x = from.getBlockX();
    int y = from.getBlockY();
    int z = from.getBlockZ();
    int stepX = (int) Math.signum(direction.getX());
    int stepY = (int) Math.signum(direction.getY());
    int stepZ = (int) Math.signum(direction.getZ());
    // How far along the look each axis's next block boundary is, and the distance between them.
    double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.getX());
    double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.getY());
    double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.getZ());
    double nextX = next(from.getX(), x, stepX, deltaX);
    double nextY = next(from.getY(), y, stepY, deltaY);
    double nextZ = next(from.getZ(), z, stepZ, deltaZ);
    int[] last = {x, y, z};
    double travelled = 0;
    while (travelled <= TARGET_RANGE) {
      if (y >= world.getMinHeight() && y < world.getMaxHeight()) {
        if (!Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4)
            || !world.isChunkLoaded(x >> 4, z >> 4)) {
          return null;
        }
        if (stopsLook(world.getBlockData(x, y, z), underwater)) {
          return last;
        }
      }
      last = new int[] {x, y, z};
      if (nextX <= nextY && nextX <= nextZ) {
        x += stepX;
        travelled = nextX;
        nextX += deltaX;
      } else if (nextY <= nextZ) {
        y += stepY;
        travelled = nextY;
        nextY += deltaY;
      } else {
        z += stepZ;
        travelled = nextZ;
        nextZ += deltaZ;
      }
    }
    return null;
  }

  /**
   * How far along the look the next block boundary on one axis is, or infinitely far if the look
   * never crosses one (rather than the 0 &times; &infin; = NaN of a look starting on a boundary,
   * which no comparison would ever pick).
   */
  private static double next(double position, int block, int step, double delta) {
    if (step == 0) {
      return Double.POSITIVE_INFINITY;
    }
    return (step > 0 ? block + 1 - position : position - block) * delta;
  }

  /** Whether a block stops a look: anything not passable, but not water or lava from within. */
  private static boolean stopsLook(BlockData data, boolean underwater) {
    Material material = data.getMaterial();
    return !PaperTerrain.isPassable(data)
        && !(underwater && (material == Material.WATER || material == Material.LAVA));
  }

  /**
   * {@code coordinates block <x> <y> <z> [world]} and {@code coordinates surface <x> <z> [world]}.
   * Strict, so {@code coordinates} must be typed: {@code surface} alone means the nearest open sky,
   * and a bare {@code block} would mean nothing.
   */
  private DestinationNode<NavigationTarget> coordinates(Player player) {
    return DestinationNode.<NavigationTarget>builder()
        .strict()
        .leaf(BLOCK_KEY, coordinateBlock(player))
        .leaf(SURFACE_KEY, coordinateSurface(player))
        .build();
  }

  /**
   * One block, in the player's world unless named; invalid above or below the world, or in terrain
   * that has never been generated.
   */
  private DestinationLeaf<NavigationTarget> coordinateBlock(Player player) {
    DestinationOption<Integer> x =
        DestinationOption.integer("x", () -> player.getLocation().getBlockX());
    DestinationOption<Integer> y =
        DestinationOption.integer("y", () -> player.getLocation().getBlockY());
    DestinationOption<Integer> z =
        DestinationOption.integer("z", () -> player.getLocation().getBlockZ());
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
          int blockX = values.get(x);
          int blockZ = values.get(z);
          if (!target.isChunkLoaded(blockX >> 4, blockZ >> 4)
              && !target.isChunkGenerated(blockX >> 4, blockZ >> 4)) {
            return invalid(player, CobblestoneMessages.NAVIGATE_UNGENERATED);
          }
          return Validity.valid(atBlock(BLOCK_KEY, new Location(target, blockX, blockY, blockZ)));
        });
  }

  /**
   * Wherever the column at x, z is open to the sky, in the player's world unless named; invalid in
   * terrain that has never been generated.
   */
  private DestinationLeaf<NavigationTarget> coordinateSurface(Player player) {
    DestinationOption<Integer> x =
        DestinationOption.integer("x", () -> player.getLocation().getBlockX());
    DestinationOption<Integer> z =
        DestinationOption.integer("z", () -> player.getLocation().getBlockZ());
    DestinationOption<World> world = worldOption();
    return DestinationLeaf.withOptions(
        List.of(x, z, world),
        values -> {
          World target = values.find(world).orElseGet(player::getWorld);
          int columnX = values.get(x);
          int columnZ = values.get(z);
          if (!target.isChunkLoaded(columnX >> 4, columnZ >> 4)
              && !target.isChunkGenerated(columnX >> 4, columnZ >> 4)) {
            return invalid(player, CobblestoneMessages.NAVIGATE_UNGENERATED);
          }
          return Validity.valid(
              NavigationTarget.builder(
                      SURFACE_KEY, () -> List.of(regions.surface(target, columnX, columnZ)))
                  .build());
        });
  }

  /**
   * The world spawn: where a player with no respawn point of their own respawns. There is one per
   * server, in its respawn world (normally the overworld).
   */
  private NavigationTarget spawn() {
    return atBlock(SPAWN_KEY, Bukkit.getServer().getRespawnWorld().getSpawnLocation());
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
}
