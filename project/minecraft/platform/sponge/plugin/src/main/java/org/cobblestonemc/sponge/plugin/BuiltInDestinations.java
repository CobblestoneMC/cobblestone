/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cobblestonemc.plugin.api.DestinationChild;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationNode;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.api.Validity;
import org.cobblestonemc.plugin.config.MenuIcons;
import org.cobblestonemc.plugin.data.DeathLocationDao;
import org.cobblestonemc.plugin.data.ExpiringCache;
import org.cobblestonemc.plugin.data.Location;
import org.cobblestonemc.plugin.data.LocationDao;
import org.cobblestonemc.plugin.data.MountDao;
import org.cobblestonemc.plugin.data.MountLocation;
import org.cobblestonemc.plugin.destination.DestinationDetails;
import org.cobblestonemc.plugin.destination.DestinationProvider;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Message0;
import org.cobblestonemc.plugin.message.Message1;
import org.cobblestonemc.plugin.message.Message3;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.sponge.SpongeRegions;
import org.cobblestonemc.sponge.SpongeTerrain;
import org.cobblestonemc.sponge.api.WholeWorldRegion;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.registry.RegistryTypes;
import org.spongepowered.api.util.RespawnLocation;
import org.spongepowered.api.util.blockray.RayTrace;
import org.spongepowered.api.util.blockray.RayTraceResult;
import org.spongepowered.api.world.DefaultWorldKeys;
import org.spongepowered.api.world.LocatableBlock;
import org.spongepowered.api.world.WorldTypes;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3d;

/**
 * Cobblestone's own destinations: saved locations, other players, worlds, pets, and places such as
 * the player's last death or the surface above them. Written in Cobblestone's own terms ({@link
 * NavigationTarget}) rather than an integration's, so they can use regions an integration's region
 * type cannot express — like the surface, whose extent depends on the blocks.
 */
public final class BuiltInDestinations implements DestinationProvider<ServerPlayer> {

  public static final String PLAYER_TREE_KEY = "player";
  public static final String WORLD_TREE_KEY = "world";
  public static final String LOCATION_TREE_KEY = "location";
  public static final String GLOBAL_TREE_KEY = "global";
  public static final String PRIVATE_TREE_KEY = "private";
  public static final String PET_TREE_KEY = "pet";
  public static final String COORDINATES_TREE_KEY = "coordinates";
  public static final String DEATH_KEY = "death";
  public static final String SURFACE_KEY = "surface";
  public static final String TARGET_KEY = "target";
  public static final String BLOCK_KEY = "block";
  public static final String SPAWN_KEY = "spawn";
  public static final String RESPAWN_KEY = "respawn";
  public static final String MOUNT_KEY = "mount";

  /** Another name for {@code mount}, for the many players whose mount is a horse. */
  public static final String HORSE_ALIAS = "horse";

  /** How far away, in blocks, the block a player is looking at may be for {@code target}. */
  static final int TARGET_RANGE = 256;

  private final LocationDao locations;
  private final DeathLocationDao deaths;
  private final MountDao mounts;
  private final SpongePetListener pets;
  private final SpongeRegions regions;
  private final Predicate<ServerLocation> generated;
  private final BooleanSupplier trackDeaths;
  private final Messages messages;
  private final Function<String, Icon> icons;
  private final ExpiringCache<UUID, List<Pet>> petsByOwner = new ExpiringCache<>();

  public BuiltInDestinations(
      LocationDao locations,
      DeathLocationDao deaths,
      MountDao mounts,
      SpongePetListener pets,
      SpongeRegions regions,
      Predicate<ServerLocation> generated,
      BooleanSupplier trackDeaths,
      Messages messages,
      Function<String, Icon> icons) {
    this.locations = locations;
    this.deaths = deaths;
    this.mounts = mounts;
    this.pets = pets;
    this.regions = regions;
    this.generated = generated;
    this.trackDeaths = trackDeaths;
    this.messages = messages;
    this.icons = icons;
  }

  @Override
  public DestinationNode<NavigationTarget> provide(ServerPlayer player) {
    DestinationNode.Builder<NavigationTarget> root = DestinationNode.builder();
    // With death tracking off there is never a death to go back to, so the key is not offered.
    if (trackDeaths.getAsBoolean()) {
      root.destination(DEATH_KEY, DestinationLeaf.ofValidity(() -> death(player)));
    }
    root.destination(SURFACE_KEY, DestinationLeaf.ofValidity(() -> surface(player)))
        .destination(TARGET_KEY, DestinationLeaf.ofValidity(() -> target(player)))
        .destination(SPAWN_KEY, DestinationLeaf.of(this::spawn))
        .destination(RESPAWN_KEY, DestinationLeaf.ofValidity(() -> respawn(player)))
        .destination(MOUNT_KEY, DestinationLeaf.ofValidity(() -> mount(player)))
        .alias(HORSE_ALIAS, MOUNT_KEY)
        .children(PLAYER_TREE_KEY, () -> players(player))
        .children(WORLD_TREE_KEY, () -> worlds(player))
        .children(LOCATION_TREE_KEY, () -> locations(player))
        .children(PET_TREE_KEY, () -> pets(player))
        .children(COORDINATES_TREE_KEY, () -> coordinates(player));
    return MenuIcons.withIcons(root.build(), icons);
  }

  @Override
  public Display display(ServerPlayer player) {
    return icon(MenuIcons.COBBLESTONE);
  }

  /** A display of just the configured item of that name. */
  private Display icon(String name) {
    return Display.of(icons.apply(name));
  }

  /** An entry that is a destination, shown with the given item. */
  private static DestinationChild<NavigationTarget> shown(
      Display display, DestinationLeaf<NavigationTarget> destination) {
    return DestinationChild.<NavigationTarget>builder()
        .destination(destination)
        .display(display)
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

  /**
   * Anywhere open to the sky, in the player's current world; invalid if the player is already out
   * in the open.
   */
  private Validity<NavigationTarget> surface(ServerPlayer player) {
    ServerLocation at = player.serverLocation();
    ServerWorld world = at.world();
    if (SpongeTerrain.isOpenToSky(world, at.blockX(), at.blockY(), at.blockZ())) {
      return invalid(player, CobblestoneMessages.NAVIGATE_ALREADY_ON_SURFACE);
    }
    return Validity.valid(NavigationTarget.builder(SURFACE_KEY, regions.surface(world)).build());
  }

  /**
   * Where the player is looking: the open space in front of the block their crosshair is on, or the
   * ground beneath it if that space is in mid-air (the side of a wall, say). Water stops the look
   * like any block, so looking out to sea means its surface (unless the player is underwater);
   * plants and other passable blocks are looked through. The look never reaches into an unloaded
   * chunk. Invalid if no block is within {@link #TARGET_RANGE}.
   */
  private Validity<NavigationTarget> target(ServerPlayer player) {
    ServerWorld world = player.world();
    Vector3d eye = player.get(Keys.EYE_POSITION).orElseGet(player::position);
    // From underwater, look through the water rather than at it.
    boolean underwater =
        !world.block(eye.floorX(), eye.floorY(), eye.floorZ()).fluidState().isEmpty();
    Optional<RayTraceResult<LocatableBlock>> hit =
        RayTrace.block()
            .world(world)
            .sourceEyePosition(player)
            .direction(player)
            .limit(TARGET_RANGE)
            .continueWhileLocation(
                location -> world.isChunkLoadedAtBlock(location.blockPosition(), false))
            .select(
                block ->
                    !SpongeTerrain.isPassable(block.blockState())
                        && !(underwater && !block.blockState().fluidState().isEmpty()))
            .execute();
    if (hit.isEmpty()) {
      return Validity.invalid(render(player, CobblestoneMessages.NAVIGATE_NO_TARGET, TARGET_RANGE));
    }
    // The hit is on the face the look came through; just short of it, back towards the eye, is
    // the open block in front of that face.
    Vector3d at = hit.get().hitPosition();
    Vector3d front = at.add(eye.sub(at).normalize().mul(0.01));
    int y = SpongeTerrain.landingY(world, front.floorX(), front.floorY(), front.floorZ());
    return Validity.valid(
        atBlock(TARGET_KEY, ServerLocation.of(world, front.floorX(), y, front.floorZ())));
  }

  /**
   * {@code coordinates block <x> <y> <z> [world]} and {@code coordinates surface <x> <z> [world]}.
   * Strict, so {@code coordinates} must be typed: {@code surface} alone means the nearest open sky,
   * and a bare {@code block} would mean nothing.
   */
  private DestinationNode<NavigationTarget> coordinates(ServerPlayer player) {
    return MenuIcons.withIcons(
        DestinationNode.<NavigationTarget>builder()
            .strict()
            .destination(BLOCK_KEY, coordinateBlock(player))
            .destination(SURFACE_KEY, coordinateSurface(player))
            .build(),
        icons);
  }

  /**
   * One block, in the player's world unless named; invalid above or below the world, or in terrain
   * that has never been generated.
   */
  private DestinationLeaf<NavigationTarget> coordinateBlock(ServerPlayer player) {
    DestinationOption<Integer> x =
        DestinationOption.integer("x", () -> player.serverLocation().blockX());
    DestinationOption<Integer> y =
        DestinationOption.integer("y", () -> player.serverLocation().blockY());
    DestinationOption<Integer> z =
        DestinationOption.integer("z", () -> player.serverLocation().blockZ());
    DestinationOption<ServerWorld> world = worldOption();
    return DestinationLeaf.withOptions(
        List.of(x, y, z, world),
        values -> {
          ServerWorld target = values.find(world).orElseGet(player::world);
          int blockY = values.get(y);
          if (blockY < target.min().y() || blockY > target.max().y()) {
            return Validity.invalid(
                render(
                    player,
                    CobblestoneMessages.NAVIGATE_OUTSIDE_WORLD,
                    target.min().y(),
                    target.max().y(),
                    target.key().asString()));
          }
          ServerLocation block = ServerLocation.of(target, values.get(x), blockY, values.get(z));
          if (!generated.test(block)) {
            return invalid(player, CobblestoneMessages.NAVIGATE_UNGENERATED);
          }
          return Validity.valid(atBlock(BLOCK_KEY, block));
        });
  }

  /**
   * Wherever the column at x, z is open to the sky, in the player's world unless named; invalid in
   * terrain that has never been generated.
   */
  private DestinationLeaf<NavigationTarget> coordinateSurface(ServerPlayer player) {
    DestinationOption<Integer> x =
        DestinationOption.integer("x", () -> player.serverLocation().blockX());
    DestinationOption<Integer> z =
        DestinationOption.integer("z", () -> player.serverLocation().blockZ());
    DestinationOption<ServerWorld> world = worldOption();
    return DestinationLeaf.withOptions(
        List.of(x, z, world),
        values -> {
          ServerWorld target = values.find(world).orElseGet(player::world);
          int columnX = values.get(x);
          int columnZ = values.get(z);
          if (!generated.test(ServerLocation.of(target, columnX, target.min().y(), columnZ))) {
            return invalid(player, CobblestoneMessages.NAVIGATE_UNGENERATED);
          }
          return Validity.valid(
              NavigationTarget.builder(SURFACE_KEY, regions.surface(target, columnX, columnZ))
                  .build());
        });
  }

  /**
   * The world spawn: where a player with no respawn point of their own respawns. There is one per
   * server, in its default world (normally the overworld), which is always loaded.
   */
  private NavigationTarget spawn() {
    ServerWorld world =
        Sponge.server().worldManager().world(DefaultWorldKeys.DEFAULT).orElseThrow();
    return atBlock(SPAWN_KEY, ServerLocation.of(world, world.properties().spawnPosition()));
  }

  /**
   * Where the player will respawn — their bed, a charged respawn anchor, or wherever a plugin or
   * {@code /spawnpoint} set it. Not validated, so an obstructed bed still counts. Invalid if the
   * player has none, or its world is not loaded.
   */
  private Validity<NavigationTarget> respawn(ServerPlayer player) {
    return player
        .get(Keys.RESPAWN_LOCATIONS)
        .flatMap(respawns -> respawns.values().stream().findFirst())
        .flatMap(RespawnLocation::asLocation)
        .map(location -> atBlock(RESPAWN_KEY, location))
        .map(Validity::valid)
        .orElseGet(() -> invalid(player, CobblestoneMessages.NAVIGATE_NO_RESPAWN));
  }

  /**
   * The mount the player last rode: followed live while it is loaded, and otherwise where it was
   * last seen. A trip to it ends if it dies. Invalid if the player has not ridden one since mount
   * tracking began, or it has died since.
   */
  private Validity<NavigationTarget> mount(ServerPlayer player) {
    return mounts
        .get(player.uniqueId())
        .map(this::mount)
        .map(Validity::valid)
        .orElseGet(() -> invalid(player, CobblestoneMessages.NAVIGATE_NO_MOUNT));
  }

  private NavigationTarget mount(MountLocation mount) {
    // Follows the mount while it is loaded, so live trips track it; otherwise heads to where it
    // was last seen.
    SpongeTrackedEntity tracked = new SpongeTrackedEntity(mount.mount());
    return NavigationTarget.builder(
            MOUNT_KEY,
            () ->
                tracked
                    .location()
                    .or(
                        () ->
                            world(mount.world())
                                .map(
                                    world ->
                                        ServerLocation.of(world, mount.x(), mount.y(), mount.z())))
                    .map(regions::block)
                    .stream()
                    .toList())
        .mobile()
        .existence(tracked::alive)
        .build();
  }

  /**
   * The player's named pets that are currently loaded, keyed by name (spaces become {@code _}).
   * Pets that share a name are numbered {@code Name}, {@code Name-2}, … oldest first, by when they
   * were tamed; those tamed before Cobblestone was installed come after, in a fixed order.
   *
   * <p>Finding them means scanning every loaded entity, and this level is visited on every
   * tab-completion keystroke, so the scan is cached per player for a second. Each pet's location is
   * still looked up live when a search is made.
   */
  private DestinationNode<NavigationTarget> pets(ServerPlayer player) {
    List<Pet> found = petsByOwner.get(player.uniqueId(), this::scanPets);
    DestinationNode.Builder<NavigationTarget> node = DestinationNode.builder();
    Set<String> taken = new HashSet<>();
    for (Pet pet : found) {
      String key = pet.name();
      for (int n = 2; !taken.add(key.toLowerCase(Locale.ROOT)); n++) {
        key = pet.name() + "-" + n;
      }
      String name = key;
      node.child(
          key,
          shown(
              Display.of(Icon.item(pet.type() + "_spawn_egg")),
              DestinationLeaf.of(
                  () -> {
                    // Follows the pet, so live trips track it, and ends the trip if it dies.
                    SpongeTrackedEntity tracked = new SpongeTrackedEntity(pet.uuid());
                    return NavigationTarget.builder(
                            name, () -> tracked.location().map(regions::block).stream().toList())
                        .mobile()
                        .existence(tracked::alive)
                        .build();
                  })));
    }
    return node.build();
  }

  /** Every loaded, named pet the player owns, by name, then oldest first. */
  private List<Pet> scanPets(UUID owner) {
    List<Pet> found = new ArrayList<>();
    for (ServerWorld world : Sponge.server().worldManager().worlds()) {
      for (Entity entity : world.entities()) {
        Optional<Component> customName = entity.get(Keys.CUSTOM_NAME);
        if (customName.isEmpty() || !entity.get(Keys.OWNER).map(owner::equals).orElse(false)) {
          continue;
        }
        String name =
            PlainTextComponentSerializer.plainText()
                .serialize(customName.get())
                .trim()
                .replaceAll("\\s+", "_");
        if (name.isEmpty()) {
          continue;
        }
        long tamedAt = pets.tamedAt(entity).orElse(Long.MAX_VALUE);
        found.add(
            new Pet(
                entity.uniqueId(),
                name,
                tamedAt,
                entity.type().key(RegistryTypes.ENTITY_TYPE).asString()));
      }
    }
    found.sort(
        Comparator.comparing(Pet::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparingLong(Pet::tamedAt)
            .thenComparing(Pet::uuid));
    return List.copyOf(found);
  }

  /** A pet, and its kind (an entity type key). */
  private record Pet(UUID uuid, String name, long tamedAt, String type) {}

  private DestinationNode<NavigationTarget> players(ServerPlayer player) {
    UUID self = player.uniqueId();
    DestinationNode.Builder<NavigationTarget> node = DestinationNode.builder();
    for (ServerPlayer other : Sponge.server().onlinePlayers()) {
      if (other.uniqueId().equals(self)) {
        continue;
      }
      UUID uuid = other.uniqueId();
      String name = other.name();
      node.child(
          name,
          shown(
              Display.of(Icon.playerHead(uuid)),
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
                          .build())));
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
      String kind = MenuIcons.WORLD_OVERWORLD;
      if (world.worldType().equals(WorldTypes.THE_NETHER.get())) {
        kind = MenuIcons.WORLD_NETHER;
      } else if (world.worldType().equals(WorldTypes.THE_END.get())) {
        kind = MenuIcons.WORLD_END;
      }
      namespaces
          .computeIfAbsent(key.namespace(), namespace -> DestinationNode.builder())
          .child(
              key.value(),
              shown(
                  icon(kind),
                  DestinationLeaf.of(
                      () ->
                          NavigationTarget.builder(
                                  worldKey,
                                  () ->
                                      regions.of(new WholeWorldRegion(worldKey)).stream().toList())
                              .build())));
    }
    DestinationNode.Builder<NavigationTarget> node = DestinationNode.builder();
    namespaces.forEach(
        (namespace, worlds) ->
            node.child(
                namespace,
                DestinationChild.<NavigationTarget>builder()
                    .children(worlds::build)
                    .display(icon(MenuIcons.WORLD))
                    .build()));
    return node.build();
  }

  /**
   * Locations split by scope, so a player's own {@code home} and a server-wide {@code home} are
   * both reachable ({@code location private home} / {@code location global home}) rather than one
   * silently shadowing the other. Neither level is strict, so plain {@code location home} still
   * works whenever only one of the two exists.
   */
  private DestinationNode<NavigationTarget> locations(ServerPlayer player) {
    return MenuIcons.withIcons(
        DestinationNode.<NavigationTarget>builder()
            .children(GLOBAL_TREE_KEY, () -> locations(locations.global()))
            .children(PRIVATE_TREE_KEY, () -> locations(locations.ownedBy(player.uniqueId())))
            .build(),
        icons);
  }

  /** Location names are unique within a scope (the table's primary key), so this cannot collide. */
  private DestinationNode<NavigationTarget> locations(List<Location> locations) {
    DestinationNode.Builder<NavigationTarget> node = DestinationNode.builder();
    for (Location location : locations) {
      node.child(
          location.name(),
          shown(
              icon(MenuIcons.SAVED_LOCATION),
              DestinationLeaf.of(
                  () ->
                      stored(
                          location.name(),
                          location.world(),
                          location.x(),
                          location.y(),
                          location.z()))));
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

  /** A stationary destination at one block, with its coordinates in the hover detail. */
  private NavigationTarget atBlock(String name, ServerLocation location) {
    return NavigationTarget.builder(name, () -> List.of(regions.block(location)))
        .detail(
            DestinationDetails.coordinates(
                location.blockX(),
                location.blockY(),
                location.blockZ(),
                location.worldKey().asString()))
        .build();
  }

  private Validity<NavigationTarget> invalid(ServerPlayer player, Message0 reason) {
    return Validity.invalid(render(player, reason));
  }

  private Component render(ServerPlayer player, Message0 message) {
    return messages.render(player.locale(), message);
  }

  private Component render(ServerPlayer player, Message1 message, Object arg) {
    return messages.render(player.locale(), message, arg);
  }

  private Component render(
      ServerPlayer player, Message3 message, Object arg1, Object arg2, Object arg3) {
    return messages.render(player.locale(), message, arg1, arg2, arg3);
  }

  /** An optional trailing world, by namespaced key ({@code minecraft:} may be left off). */
  private static DestinationOption<ServerWorld> worldOption() {
    return DestinationOption.optional(
        "world",
        partial ->
            Sponge.server().worldManager().worlds().stream()
                .map(world -> world.key().asString())
                .toList(),
        token -> {
          try {
            return world(token.toLowerCase(Locale.ROOT));
          } catch (RuntimeException e) {
            // Not a valid key at all; the platform's key parser decides what it throws.
            return Optional.empty();
          }
        });
  }

  private static Optional<ServerWorld> world(String key) {
    return Sponge.server().worldManager().world(ResourceKey.resolve(key));
  }
}
