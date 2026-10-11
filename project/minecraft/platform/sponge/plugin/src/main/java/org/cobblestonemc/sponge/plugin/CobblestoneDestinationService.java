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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.cobblestonemc.plugin.data.DeathLocation;
import org.cobblestonemc.plugin.data.DeathLocationDao;
import org.cobblestonemc.plugin.data.ExpiringCache;
import org.cobblestonemc.plugin.data.Location;
import org.cobblestonemc.plugin.data.LocationDao;
import org.cobblestonemc.plugin.data.MountDao;
import org.cobblestonemc.plugin.data.MountLocation;
import org.cobblestonemc.plugin.destination.DestinationDetails;
import org.cobblestonemc.plugin.destination.LazyMinecraftDestination;
import org.cobblestonemc.plugin.destination.SimpleMinecraftDestination;
import org.cobblestonemc.plugin.destination.SimplePlatformDestinationTree;
import org.cobblestonemc.plugin.destination.TemplateDestination;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Message0;
import org.cobblestonemc.plugin.message.Message1;
import org.cobblestonemc.plugin.message.Message3;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.sponge.SpongeTerrain;
import org.cobblestonemc.sponge.api.SingleCellWorldRegion;
import org.cobblestonemc.sponge.api.SurfaceWorldRegion;
import org.cobblestonemc.sponge.api.WholeWorldRegion;
import org.cobblestonemc.sponge.plugin.api.DestinationService;
import org.cobblestonemc.sponge.plugin.api.DestinationTree;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.util.RespawnLocation;
import org.spongepowered.api.util.blockray.RayTrace;
import org.spongepowered.api.util.blockray.RayTraceResult;
import org.spongepowered.api.world.LocatableBlock;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3d;
import org.spongepowered.math.vector.Vector3i;

public final class CobblestoneDestinationService implements DestinationService {

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

  /** How far away, in blocks, the block a player is looking at may be for {@code target}. */
  static final int TARGET_RANGE = 256;

  private final LocationDao locations;
  private final DeathLocationDao deaths;
  private final MountDao mounts;
  private final SpongePetListener pets;
  private final BooleanSupplier trackDeaths;
  private final Messages messages;
  private final ExpiringCache<UUID, List<Pet>> petsByOwner = new ExpiringCache<>();

  public CobblestoneDestinationService(
      LocationDao locations,
      DeathLocationDao deaths,
      MountDao mounts,
      SpongePetListener pets,
      BooleanSupplier trackDeaths,
      Messages messages) {
    this.locations = locations;
    this.deaths = deaths;
    this.mounts = mounts;
    this.pets = pets;
    this.trackDeaths = trackDeaths;
    this.messages = messages;
  }

  @Override
  public PlatformDestinationTree<ServerWorld, Vector3i> provide(ServerPlayer player) {
    Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> leaves =
        new LinkedHashMap<>();
    // With death tracking off there is never a death to go back to, so the key is not offered.
    if (trackDeaths.getAsBoolean()) {
      leaves.put(DEATH_KEY, () -> death(player));
    }
    leaves.put(SURFACE_KEY, () -> surface(player));
    leaves.put(TARGET_KEY, () -> target(player));
    leaves.put(SPAWN_KEY, () -> spawn(player));
    leaves.put(RESPAWN_KEY, () -> respawn(player));
    leaves.put(MOUNT_KEY, () -> mount(player));
    return new SimplePlatformDestinationTree<>(
        false,
        Map.of(
            PLAYER_TREE_KEY,
            () -> providePlayerDestinationTree(player),
            WORLD_TREE_KEY,
            () -> provideWorldDestinationTree(player),
            LOCATION_TREE_KEY,
            () -> provideLocationDestinationTree(player),
            PET_TREE_KEY,
            () -> providePetDestinationTree(player),
            COORDINATES_TREE_KEY,
            () -> provideCoordinatesDestinationTree(player)),
        leaves);
  }

  /** The player's last death; invalid if none is on record. */
  private MinecraftDestination<ServerWorld, Vector3i> death(ServerPlayer player) {
    return lazy(
        DEATH_KEY,
        () ->
            deaths
                .get(player.uniqueId())
                .map(CobblestoneDestinationService::toDestination)
                .orElseGet(
                    () ->
                        invalid(DEATH_KEY, render(player, CobblestoneMessages.NAVIGATE_NO_DEATH))));
  }

  private static MinecraftDestination<ServerWorld, Vector3i> toDestination(DeathLocation death) {
    Destination<WorldRegion<ServerWorld, Vector3i>> destination =
        () ->
            world(death.world())
                .<List<WorldRegion<ServerWorld, Vector3i>>>map(
                    world ->
                        List.of(
                            SingleCellWorldRegion.of(
                                ServerLocation.of(world, death.x(), death.y(), death.z()))))
                .orElseGet(List::of);
    return new SimpleMinecraftDestination<ServerWorld, Vector3i>(
            destination, Component.text(DEATH_KEY), List.of())
        .withDetail(DestinationDetails.coordinates(death.x(), death.y(), death.z(), death.world()));
  }

  /**
   * Anywhere open to the sky, in the player's current world; invalid if the player is already out
   * in the open.
   */
  private MinecraftDestination<ServerWorld, Vector3i> surface(ServerPlayer player) {
    return lazy(
        SURFACE_KEY,
        () -> {
          ServerLocation at = player.serverLocation();
          ServerWorld world = at.world();
          if (SpongeTerrain.isOpenToSky(world, at.blockX(), at.blockY(), at.blockZ())) {
            return invalid(
                SURFACE_KEY, render(player, CobblestoneMessages.NAVIGATE_ALREADY_ON_SURFACE));
          }
          WorldRegion<ServerWorld, Vector3i> region = SurfaceWorldRegion.anywhere(world);
          return new SimpleMinecraftDestination<>(
              () -> List.of(region), Component.text(SURFACE_KEY), List.of());
        });
  }

  /**
   * Where the player is looking: the open space in front of the block their crosshair is on, or the
   * ground beneath it if that space is in mid-air (the side of a wall, say). Water stops the look
   * like any block, so looking out to sea means its surface (unless the player is underwater);
   * plants and other passable blocks are looked through. The look never reaches into an unloaded
   * chunk. Invalid if no block is within {@link #TARGET_RANGE}.
   */
  private MinecraftDestination<ServerWorld, Vector3i> target(ServerPlayer player) {
    return lazy(
        TARGET_KEY,
        () -> {
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
            return invalid(
                TARGET_KEY, render(player, CobblestoneMessages.NAVIGATE_NO_TARGET, TARGET_RANGE));
          }
          // The hit is on the face the look came through; just short of it, back towards the eye,
          // is the open block in front of that face.
          Vector3d at = hit.get().hitPosition();
          Vector3d front = at.add(eye.sub(at).normalize().mul(0.01));
          int y = SpongeTerrain.landingY(world, front.floorX(), front.floorY(), front.floorZ());
          return atBlock(ServerLocation.of(world, front.floorX(), y, front.floorZ()), TARGET_KEY);
        });
  }

  /**
   * {@code coordinates block <x> <y> <z> [world]} and {@code coordinates surface <x> <z> [world]}.
   * Strict, so {@code coordinates} must be typed: {@code surface} alone means the nearest open sky,
   * and a bare {@code block} would mean nothing.
   */
  private PlatformDestinationTree<ServerWorld, Vector3i> provideCoordinatesDestinationTree(
      ServerPlayer player) {
    Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> leaves =
        new LinkedHashMap<>();
    leaves.put(BLOCK_KEY, () -> coordinateBlock(player));
    leaves.put(SURFACE_KEY, () -> coordinateSurface(player));
    return new SimplePlatformDestinationTree<>(true, Map.of(), leaves);
  }

  /** One block, in the player's world unless named; invalid above or below the world. */
  private MinecraftDestination<ServerWorld, Vector3i> coordinateBlock(ServerPlayer player) {
    DestinationOption<Integer> x =
        DestinationOption.integer("x", () -> player.serverLocation().blockX());
    DestinationOption<Integer> y =
        DestinationOption.integer("y", () -> player.serverLocation().blockY());
    DestinationOption<Integer> z =
        DestinationOption.integer("z", () -> player.serverLocation().blockZ());
    DestinationOption<ServerWorld> world = worldOption();
    return new TemplateDestination<>(
        Component.text(BLOCK_KEY),
        List.of(),
        List.of(x, y, z, world),
        values -> {
          ServerWorld target = values.find(world).orElseGet(player::world);
          int blockY = values.get(y);
          if (blockY < target.min().y() || blockY > target.max().y()) {
            return invalid(
                BLOCK_KEY,
                render(
                    player,
                    CobblestoneMessages.NAVIGATE_OUTSIDE_WORLD,
                    target.min().y(),
                    target.max().y(),
                    target.key().asString()));
          }
          return atBlock(
              ServerLocation.of(target, values.get(x), blockY, values.get(z)), BLOCK_KEY);
        });
  }

  /** Wherever the column at x, z is open to the sky, in the player's world unless named. */
  private static MinecraftDestination<ServerWorld, Vector3i> coordinateSurface(
      ServerPlayer player) {
    DestinationOption<Integer> x =
        DestinationOption.integer("x", () -> player.serverLocation().blockX());
    DestinationOption<Integer> z =
        DestinationOption.integer("z", () -> player.serverLocation().blockZ());
    DestinationOption<ServerWorld> world = worldOption();
    return new TemplateDestination<>(
        Component.text(SURFACE_KEY),
        List.of(),
        List.of(x, z, world),
        values -> {
          ServerWorld target = values.find(world).orElseGet(player::world);
          WorldRegion<ServerWorld, Vector3i> region =
              SurfaceWorldRegion.column(target, values.get(x), values.get(z));
          return new SimpleMinecraftDestination<>(
              () -> List.of(region), Component.text(SURFACE_KEY), List.of());
        });
  }

  /** {@code spawn [world]}: the world's spawn point, in the player's world unless named. */
  private static MinecraftDestination<ServerWorld, Vector3i> spawn(ServerPlayer player) {
    DestinationOption<ServerWorld> world = worldOption();
    return new TemplateDestination<>(
        Component.text(SPAWN_KEY),
        List.of(),
        List.of(world),
        values -> {
          ServerWorld target = values.find(world).orElseGet(player::world);
          return atBlock(ServerLocation.of(target, target.properties().spawnPosition()), SPAWN_KEY);
        });
  }

  /**
   * Where the player will respawn — their bed, a charged respawn anchor, or wherever a plugin or
   * {@code /spawnpoint} set it. Not validated, so an obstructed bed still counts. Invalid if the
   * player has none, or its world is not loaded.
   */
  private MinecraftDestination<ServerWorld, Vector3i> respawn(ServerPlayer player) {
    return lazy(
        RESPAWN_KEY,
        () ->
            player
                .get(Keys.RESPAWN_LOCATIONS)
                .flatMap(respawns -> respawns.values().stream().findFirst())
                .flatMap(RespawnLocation::asLocation)
                .map(location -> atBlock(location, RESPAWN_KEY))
                .orElseGet(
                    () ->
                        invalid(
                            RESPAWN_KEY, render(player, CobblestoneMessages.NAVIGATE_NO_RESPAWN))));
  }

  /**
   * The mount the player last rode: followed live while it is loaded, and otherwise where it was
   * last seen. A trip to it ends if it dies. Invalid if the player has not ridden one since mount
   * tracking began, or it has died since.
   */
  private MinecraftDestination<ServerWorld, Vector3i> mount(ServerPlayer player) {
    return lazy(
        MOUNT_KEY,
        () ->
            mounts
                .get(player.uniqueId())
                .map(CobblestoneDestinationService::toDestination)
                .orElseGet(
                    () ->
                        invalid(MOUNT_KEY, render(player, CobblestoneMessages.NAVIGATE_NO_MOUNT))));
  }

  private static MinecraftDestination<ServerWorld, Vector3i> toDestination(MountLocation mount) {
    // Follows the mount while it is loaded, so live trips track it; otherwise heads to where it
    // was last seen.
    SpongeTrackedEntity tracked = new SpongeTrackedEntity(mount.mount());
    Destination<WorldRegion<ServerWorld, Vector3i>> destination =
        () ->
            tracked
                .location()
                .or(() -> recorded(mount))
                .<List<WorldRegion<ServerWorld, Vector3i>>>map(
                    location -> List.of(SingleCellWorldRegion.of(location)))
                .orElseGet(List::of);
    return new SimpleMinecraftDestination<>(destination, Component.text(MOUNT_KEY), List.of(), true)
        .withExistence(tracked::alive);
  }

  private static Optional<ServerLocation> recorded(MountLocation mount) {
    return world(mount.world())
        .map(world -> ServerLocation.of(world, mount.x(), mount.y(), mount.z()));
  }

  /** A stationary destination at one block, with its coordinates in the hover detail. */
  private static MinecraftDestination<ServerWorld, Vector3i> atBlock(
      ServerLocation location, String name) {
    WorldRegion<ServerWorld, Vector3i> region = SingleCellWorldRegion.of(location);
    return new SimpleMinecraftDestination<ServerWorld, Vector3i>(
            () -> List.of(region), Component.text(name), List.of())
        .withDetail(
            DestinationDetails.coordinates(
                location.blockX(),
                location.blockY(),
                location.blockZ(),
                location.worldKey().asString()));
  }

  /**
   * A destination worked out only when the command runs, on the main thread; see {@link
   * LazyMinecraftDestination}.
   */
  private static MinecraftDestination<ServerWorld, Vector3i> lazy(
      String name, Supplier<MinecraftDestination<ServerWorld, Vector3i>> factory) {
    return new LazyMinecraftDestination<>(Component.text(name), List.of(), factory);
  }

  private static MinecraftDestination<ServerWorld, Vector3i> invalid(
      String name, Component reason) {
    return SimpleMinecraftDestination.invalid(Component.text(name), List.of(), reason);
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

  /**
   * The player's named pets that are currently loaded, keyed by name (spaces become {@code _}).
   * Pets that share a name are numbered {@code Name}, {@code Name-2}, … oldest first, by when they
   * were tamed; those tamed before Cobblestone was installed come after, in a fixed order.
   *
   * <p>Finding them means scanning every loaded entity, and this level is visited on every
   * tab-completion keystroke, so the scan is cached per player for a second. Each pet's location is
   * still looked up live when a search is made.
   */
  private PlatformDestinationTree<ServerWorld, Vector3i> providePetDestinationTree(
      ServerPlayer player) {
    List<Pet> found = petsByOwner.get(player.uniqueId(), this::scanPets);
    Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> leaves =
        new LinkedHashMap<>();
    Set<String> taken = new HashSet<>();
    for (Pet pet : found) {
      String key = pet.name();
      for (int n = 2; !taken.add(key.toLowerCase(Locale.ROOT)); n++) {
        key = pet.name() + "-" + n;
      }
      String displayName = key;
      UUID uuid = pet.uuid();
      leaves.put(
          key,
          () -> {
            // Follows the pet, so live trips track it, and ends the trip if it dies.
            SpongeTrackedEntity tracked = new SpongeTrackedEntity(uuid);
            Destination<WorldRegion<ServerWorld, Vector3i>> destination =
                () ->
                    tracked
                        .location()
                        .<List<WorldRegion<ServerWorld, Vector3i>>>map(
                            location -> List.of(SingleCellWorldRegion.of(location)))
                        .orElseGet(List::of);
            return new SimpleMinecraftDestination<>(
                    destination, Component.text(displayName), List.of(), true)
                .withExistence(tracked::alive);
          });
    }
    return new SimplePlatformDestinationTree<>(false, Map.of(), leaves);
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
        found.add(new Pet(entity.uniqueId(), name, tamedAt));
      }
    }
    found.sort(
        Comparator.comparing(Pet::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparingLong(Pet::tamedAt)
            .thenComparing(Pet::uuid));
    return List.copyOf(found);
  }

  private record Pet(UUID uuid, String name, long tamedAt) {}

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
            // Ends the trip if they log off.
            return new SimpleMinecraftDestination<>(
                    destination, Component.text(name), List.of(), true)
                .withExistence(() -> Sponge.server().player(uuid).isPresent());
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
        () ->
            world(location.world())
                .<List<WorldRegion<ServerWorld, Vector3i>>>map(
                    world ->
                        List.of(
                            SingleCellWorldRegion.of(
                                ServerLocation.of(
                                    world, location.x(), location.y(), location.z()))))
                .orElseGet(List::of);
    return new SimpleMinecraftDestination<ServerWorld, Vector3i>(
            destination, Component.text(location.name()), List.of())
        .withDetail(
            DestinationDetails.coordinates(
                location.x(), location.y(), location.z(), location.world()));
  }
}
