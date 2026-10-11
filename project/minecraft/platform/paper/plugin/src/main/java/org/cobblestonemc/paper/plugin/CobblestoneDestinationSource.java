/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.ArrayList;
import java.util.Comparator;
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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataType;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.paper.api.SingleCellWorldRegion;
import org.cobblestonemc.paper.api.SurfaceWorldRegion;
import org.cobblestonemc.paper.api.WholeWorldRegion;
import org.cobblestonemc.paper.plugin.api.DestinationService;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.cobblestonemc.plugin.data.DeathLocation;
import org.cobblestonemc.plugin.data.DeathLocationDao;
import org.cobblestonemc.plugin.data.ExpiringCache;
import org.cobblestonemc.plugin.data.LocationDao;
import org.cobblestonemc.plugin.data.MountDao;
import org.cobblestonemc.plugin.data.MountLocation;
import org.cobblestonemc.plugin.destination.DestinationDetails;
import org.cobblestonemc.plugin.destination.SimpleMinecraftDestination;
import org.cobblestonemc.plugin.destination.SimplePlatformDestinationTree;
import org.cobblestonemc.plugin.destination.TemplateDestination;
import org.joml.Vector3i;

public class CobblestoneDestinationSource implements DestinationService {
  public static final String PLAYER_TREE_KEY = "player";
  public static final String WORLD_TREE_KEY = "world";
  public static final String LOCATION_TREE_KEY = "location";
  public static final String GLOBAL_TREE_KEY = "global";
  public static final String PRIVATE_TREE_KEY = "private";
  public static final String PET_TREE_KEY = "pet";
  public static final String DEATH_KEY = "death";
  public static final String SURFACE_KEY = "surface";
  public static final String COORDINATES_KEY = "coordinates";
  public static final String COORDINATE_SURFACE_KEY = "coordinate-surface";
  public static final String SPAWN_KEY = "spawn";
  public static final String RESPAWN_KEY = "respawn";
  public static final String MOUNT_KEY = "mount";

  /**
   * When a pet was tamed (epoch millis), stamped on the entity by {@link PetListener} so pets that
   * share a name are numbered oldest first. Pets tamed before Cobblestone was installed have none.
   */
  static final NamespacedKey TAMED_AT = new NamespacedKey("cobblestone", "tamed_at");

  private final LocationDao locations;
  private final DeathLocationDao deaths;
  private final MountDao mounts;
  private final BooleanSupplier trackDeaths;
  private final ExpiringCache<UUID, List<Pet>> petsByOwner = new ExpiringCache<>();

  public CobblestoneDestinationSource(
      LocationDao locations,
      DeathLocationDao deaths,
      MountDao mounts,
      BooleanSupplier trackDeaths) {
    this.locations = locations;
    this.deaths = deaths;
    this.mounts = mounts;
    this.trackDeaths = trackDeaths;
  }

  @Override
  public PlatformDestinationTree<World, Vector3i> provide(Player player) {
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves = new LinkedHashMap<>();
    deathLeaf(player).ifPresent(leaf -> leaves.put(DEATH_KEY, leaf));
    leaves.put(SURFACE_KEY, () -> surface(player));
    leaves.put(COORDINATES_KEY, () -> coordinates(player));
    leaves.put(COORDINATE_SURFACE_KEY, () -> coordinateSurface(player));
    leaves.put(SPAWN_KEY, () -> spawn(player));
    respawnLeaf(player).ifPresent(leaf -> leaves.put(RESPAWN_KEY, leaf));
    mountLeaf(player).ifPresent(leaf -> leaves.put(MOUNT_KEY, leaf));
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
            () -> providePetDestinationTree(player)),
        leaves);
  }

  /**
   * The player's last death, as a single {@code cobblestone death} leaf. Absent entirely when death
   * tracking is off or the player has not died since it was turned on, so {@code /navigate death}
   * reports an unknown destination rather than a route that cannot exist.
   */
  private Optional<Supplier<MinecraftDestination<World, Vector3i>>> deathLeaf(Player player) {
    if (!trackDeaths.getAsBoolean()) {
      return Optional.empty();
    }
    return deaths.get(player.getUniqueId()).map(death -> () -> toDestination(death));
  }

  private static MinecraftDestination<World, Vector3i> toDestination(DeathLocation death) {
    NamespacedKey worldKey = NamespacedKey.fromString(death.world());
    World world = worldKey == null ? null : Bukkit.getWorld(worldKey);
    var bukkitLocation = new Location(world, death.x(), death.y(), death.z());
    WorldRegion<World, Vector3i> region = SingleCellWorldRegion.of(bukkitLocation);
    Destination<WorldRegion<World, Vector3i>> destination = () -> List.of(region);
    return new SimpleMinecraftDestination<World, Vector3i>(
            destination, Component.text(DEATH_KEY), List.of())
        .withDetail(DestinationDetails.coordinates(death.x(), death.y(), death.z(), death.world()));
  }

  /** Anywhere open to the sky, in the player's current world. */
  private static MinecraftDestination<World, Vector3i> surface(Player player) {
    WorldRegion<World, Vector3i> region = SurfaceWorldRegion.anywhere(player.getWorld());
    return new SimpleMinecraftDestination<>(
        () -> List.of(region), Component.text(SURFACE_KEY), List.of());
  }

  /** {@code coordinates <x> <y> <z> [world]}: one block, in the player's world unless named. */
  private static MinecraftDestination<World, Vector3i> coordinates(Player player) {
    DestinationOption<Integer> x = DestinationOption.integer("x", () -> blockX(player));
    DestinationOption<Integer> y = DestinationOption.integer("y", () -> blockY(player));
    DestinationOption<Integer> z = DestinationOption.integer("z", () -> blockZ(player));
    DestinationOption<World> world = worldOption();
    return new TemplateDestination<>(
        Component.text(COORDINATES_KEY),
        List.of(),
        List.of(x, y, z, world),
        values -> {
          World target = values.find(world).orElseGet(player::getWorld);
          Location location = new Location(target, values.get(x), values.get(y), values.get(z));
          WorldRegion<World, Vector3i> region = SingleCellWorldRegion.of(location);
          return new SimpleMinecraftDestination<World, Vector3i>(
                  () -> List.of(region), Component.text(COORDINATES_KEY), List.of())
              .withDetail(
                  DestinationDetails.coordinates(
                      location.getBlockX(),
                      location.getBlockY(),
                      location.getBlockZ(),
                      target.getKey().asString()));
        });
  }

  /** {@code coordinate-surface <x> <z> [world]}: wherever that column is open to the sky. */
  private static MinecraftDestination<World, Vector3i> coordinateSurface(Player player) {
    DestinationOption<Integer> x = DestinationOption.integer("x", () -> blockX(player));
    DestinationOption<Integer> z = DestinationOption.integer("z", () -> blockZ(player));
    DestinationOption<World> world = worldOption();
    return new TemplateDestination<>(
        Component.text(COORDINATE_SURFACE_KEY),
        List.of(),
        List.of(x, z, world),
        values -> {
          World target = values.find(world).orElseGet(player::getWorld);
          WorldRegion<World, Vector3i> region =
              SurfaceWorldRegion.column(target, values.get(x), values.get(z));
          return new SimpleMinecraftDestination<>(
              () -> List.of(region), Component.text(COORDINATE_SURFACE_KEY), List.of());
        });
  }

  /** {@code spawn [world]}: the world's spawn point, in the player's world unless named. */
  private static MinecraftDestination<World, Vector3i> spawn(Player player) {
    DestinationOption<World> world = worldOption();
    return new TemplateDestination<>(
        Component.text(SPAWN_KEY),
        List.of(),
        List.of(world),
        values -> {
          World target = values.find(world).orElseGet(player::getWorld);
          return atBlock(target.getSpawnLocation(), SPAWN_KEY);
        });
  }

  /**
   * Where the player will respawn — their bed, a charged respawn anchor, or wherever a plugin or
   * {@code /spawnpoint} set it. Read without validating it (that would load its chunk), so an
   * obstructed bed still shows here. Absent if the player has none.
   */
  private static Optional<Supplier<MinecraftDestination<World, Vector3i>>> respawnLeaf(
      Player player) {
    Location respawn = player.getRespawnLocation(false);
    if (respawn == null || respawn.getWorld() == null) {
      return Optional.empty();
    }
    return Optional.of(() -> atBlock(respawn, RESPAWN_KEY));
  }

  /**
   * The mount the player last rode: the live entity while it is loaded, and otherwise where it was
   * last seen. Absent if the player has not ridden one since mount tracking began.
   */
  private Optional<Supplier<MinecraftDestination<World, Vector3i>>> mountLeaf(Player player) {
    return mounts
        .get(player.getUniqueId())
        .map(
            mount ->
                () -> {
                  Destination<WorldRegion<World, Vector3i>> destination =
                      () -> List.of(SingleCellWorldRegion.of(mountLocation(mount)));
                  return new SimpleMinecraftDestination<>(
                      destination, Component.text(MOUNT_KEY), List.of());
                });
  }

  private static Location mountLocation(MountLocation mount) {
    Entity live = Bukkit.getEntity(mount.mount());
    if (live != null && live.isValid()) {
      return live.getLocation();
    }
    NamespacedKey worldKey = NamespacedKey.fromString(mount.world());
    World world = worldKey == null ? null : Bukkit.getWorld(worldKey);
    return new Location(world, mount.x(), mount.y(), mount.z());
  }

  /** A stationary destination at one block, with its coordinates in the hover detail. */
  private static MinecraftDestination<World, Vector3i> atBlock(Location location, String name) {
    WorldRegion<World, Vector3i> region = SingleCellWorldRegion.of(location);
    return new SimpleMinecraftDestination<World, Vector3i>(
            () -> List.of(region), Component.text(name), List.of())
        .withDetail(
            DestinationDetails.coordinates(
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                location.getWorld().getKey().asString()));
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

  /**
   * The player's named pets that are currently loaded, keyed by name (spaces become {@code _}).
   * Pets that share a name are numbered {@code Name}, {@code Name-2}, … oldest first, by when they
   * were tamed; those tamed before Cobblestone was installed come after, in a fixed order.
   *
   * <p>Finding them means scanning every loaded entity, and this level is visited on every
   * tab-completion keystroke, so the scan is cached per player for a second. Each pet's location is
   * still looked up live when a search is made.
   */
  private PlatformDestinationTree<World, Vector3i> providePetDestinationTree(Player player) {
    List<Pet> pets = petsByOwner.get(player.getUniqueId(), CobblestoneDestinationSource::scanPets);
    Map<String, Supplier<MinecraftDestination<World, Vector3i>>> leaves = new LinkedHashMap<>();
    Set<String> taken = new HashSet<>();
    for (Pet pet : pets) {
      String key = pet.name();
      for (int n = 2; !taken.add(key.toLowerCase(Locale.ROOT)); n++) {
        key = pet.name() + "-" + n;
      }
      String displayName = key;
      UUID uuid = pet.uuid();
      leaves.put(
          key,
          () -> {
            // Re-resolves the pet on each query, so live trips follow it.
            Destination<WorldRegion<World, Vector3i>> destination =
                () -> {
                  Entity target = Bukkit.getEntity(uuid);
                  return target == null || !target.isValid()
                      ? List.of()
                      : List.of(SingleCellWorldRegion.of(target.getLocation()));
                };
            return new SimpleMinecraftDestination<>(
                destination, Component.text(displayName), List.of(), true);
          });
    }
    return new SimplePlatformDestinationTree<>(false, Map.of(), leaves);
  }

  /** Every loaded, named pet the player owns, by name, then oldest first. */
  private static List<Pet> scanPets(UUID owner) {
    List<Pet> pets = new ArrayList<>();
    for (World world : Bukkit.getWorlds()) {
      for (Tameable tameable : world.getEntitiesByClass(Tameable.class)) {
        Component customName = tameable.customName();
        if (customName == null || !owner.equals(tameable.getOwnerUniqueId())) {
          continue;
        }
        String name =
            PlainTextComponentSerializer.plainText()
                .serialize(customName)
                .trim()
                .replaceAll("\\s+", "_");
        if (name.isEmpty()) {
          continue;
        }
        Long tamedAt = tameable.getPersistentDataContainer().get(TAMED_AT, PersistentDataType.LONG);
        pets.add(new Pet(tameable.getUniqueId(), name, tamedAt == null ? Long.MAX_VALUE : tamedAt));
      }
    }
    pets.sort(
        Comparator.comparing(Pet::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparingLong(Pet::tamedAt)
            .thenComparing(Pet::uuid));
    return List.copyOf(pets);
  }

  private record Pet(UUID uuid, String name, long tamedAt) {}

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
        new SimpleMinecraftDestination<World, Vector3i>(
                destination, Component.text(location.name()), List.of())
            .withDetail(
                DestinationDetails.coordinates(
                    location.x(), location.y(), location.z(), location.world()));
  }
}
