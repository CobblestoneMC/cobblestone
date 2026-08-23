/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.CellRegion;
import org.cobblestonemc.CobblestoneApi;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.HeuristicStrategy;
import org.cobblestonemc.Heuristics;
import org.cobblestonemc.ModesProvider;
import org.cobblestonemc.Position;
import org.cobblestonemc.Restriction;
import org.cobblestonemc.SingleDestination;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.api.SearchHandle;
import org.cobblestonemc.minecraft.BreakChecker;
import org.cobblestonemc.minecraft.ChunkProvider;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftSearchSettings;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.minecraft.modes.MinecraftModes;
import org.cobblestonemc.minecraft.registry.OwnedRegistry;
import org.cobblestonemc.paper.api.BoxWorldRegion;
import org.cobblestonemc.paper.api.NavigationService;
import org.cobblestonemc.paper.api.PassChecker;
import org.cobblestonemc.paper.api.SearchModificationRegistrar;
import org.cobblestonemc.paper.api.SearchModificationService;
import org.cobblestonemc.paper.api.Transition;
import org.joml.Vector3i;

public final class PaperNavigationServiceImpl
    implements NavigationService, SearchModificationRegistrar, WorldWrapper {

  // The true global-minimum per-block cost (flying, MovementCosts.FLY = 0.08). Used as the
  // admissible Tier-1 bound and the running-average's cold-start estimate.
  private static final double CHEAPEST_COST_PER_BLOCK = 0.08;

  private final CobblestoneLogger logger;
  private final PaperScheduler scheduler;
  private final ChunkProvider chunkProvider;
  private final CobblestoneApi core;
  private final HeuristicStrategy heuristic;
  private final Map<String, MinecraftWorld> worldCache = new ConcurrentHashMap<>();
  private final OwnedRegistry<SearchModificationService> searchModifiers = new OwnedRegistry<>();

  /**
   * Creates the API for a plugin.
   *
   * @param plugin the owning plugin
   */
  public PaperNavigationServiceImpl(
      Plugin plugin, CobblestoneLogger logger, ChunkProviderSettings chunkSettings) {
    this.logger = logger;
    int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    this.scheduler = new PaperScheduler(plugin, workerThreads);
    PaperPlatformApi platform = new PaperPlatformApi(plugin, scheduler);
    this.chunkProvider = new ChunkProvider(platform, chunkSettings);
    this.core = CobblestoneApi.load();
    this.heuristic = Heuristics.runningAverage(CHEAPEST_COST_PER_BLOCK);
  }

  @Override
  public SearchHandle<Location, MinecraftStepPayload> navigatePlayer(
      Player player, Location destination, MinecraftSearchSettings settings) {
    DomainRegion<MinecraftWorld> region =
        new CellRegion<>(PaperConversions.cell(destination), wrap(destination.getWorld()));
    return search(player, new SingleDestination<>(region), settings);
  }

  @Override
  public SearchHandle<Location, MinecraftStepPayload> navigatePlayerToRegion(
      Player player, Location location1, Location location2, MinecraftSearchSettings settings) {
    BoxWorldRegion region = BoxWorldRegion.of(location1, location2);
    return search(player, new SingleDestination<>(PaperConversions.region(region, this)), settings);
  }

  /**
   * Begins a search toward a plugin-provided {@link Destination} (e.g. a resolved location), which
   * may span several regions/endpoints. Used by the {@code /navigate} command; not on the public
   * native façade because it speaks Paper's {@link WorldRegion} type.
   *
   * @param player the navigating player
   * @param destination the goal, as one or more world regions
   * @param settings the search limits and knobs
   * @return a handle to the in-flight search, yielding native-located steps
   */
  public SearchHandle<Location, MinecraftStepPayload> navigatePlayerToDestination(
      Player player,
      Destination<WorldRegion<World, Vector3i>> destination,
      MinecraftSearchSettings settings) {
    List<DomainRegion<MinecraftWorld>> regions = new ArrayList<>();
    for (WorldRegion<World, Vector3i> region : destination.regions()) {
      if (region.world() == null) {
        continue; // world unloaded or target gone; skip rather than NPE
      }
      regions.add(PaperConversions.region(region, this));
    }
    return search(player, () -> regions, settings);
  }

  /**
   * Converts a native {@link Location} to a core {@link Position}, for scheduling a trip on the
   * location's owning thread.
   *
   * @param location the location
   * @return the position
   */
  public Position<MinecraftWorld> position(Location location) {
    return new Position<>(PaperConversions.cell(location), wrap(location.getWorld()));
  }

  /**
   * Returns the platform scheduler, so the plugin can tick trips on it (Folia-safe region tasks).
   *
   * @return the scheduler
   */
  public MinecraftScheduler<Entity> scheduler() {
    return scheduler;
  }

  /** Stops the search worker pool; call on plugin disable. */
  public void shutdown() {
    scheduler.shutdown();
  }

  @Override
  public void register(Plugin owner, SearchModificationService service) {
    searchModifiers.register(owner.getName(), service);
  }

  /**
   * Drops every search modifier a departing owner registered (called when that plugin disables).
   *
   * @param owner the departing owner's name
   * @return how many modifiers were removed
   */
  public void purgeOwner(String owner) {
    searchModifiers.purge(owner);
  }

  private SearchHandle<Location, MinecraftStepPayload> search(
      Player player,
      Destination<DomainRegion<MinecraftWorld>> destination,
      MinecraftSearchSettings settings) {
    CobblestonePlayer agent = new PaperPlayer(player);
    Location origin = player.getLocation();
    Position<MinecraftWorld> originPosition =
        new Position<>(PaperConversions.cell(origin), wrap(origin.getWorld()));
    // One snapshot of the registered modifiers drives all three influences on this search.
    Collection<SearchModificationService> modifiers = searchModifiers.map().values();
    BreakChecker<CobblestonePlayer> breakChecker = buildBreakChecker(modifiers, player);
    List<Restriction<CobblestonePlayer, MinecraftWorld>> restrictions =
        buildRestrictions(modifiers, player);
    // Read the pearl count now, on the calling (server) thread; the provider closes over it and is
    // invoked per leg on worker threads, where it must not touch the Bukkit player.
    ModesProvider<CobblestonePlayer, MinecraftStepPayload, MinecraftWorld> modes =
        MinecraftModes.providerFor(
            agent, settings.excludedModes(), breakChecker, countEnderPearls(player));

    CompletableFuture<SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload>> handleFuture =
        gatherTransitions(
                modifiers, player, settings.excludedWorlds(), settings.excludedDimensions())
            .thenApply(
                gathered ->
                    core.navigate(
                        logger,
                        scheduler,
                        agent,
                        originPosition,
                        destination,
                        modes,
                        gathered,
                        restrictions,
                        heuristic,
                        settings.settings()));
    return new PaperSearchHandle(handleFuture);
  }

  private CompletableFuture<
          List<org.cobblestonemc.Transition<MinecraftStepPayload, MinecraftWorld>>>
      gatherTransitions(
          Collection<SearchModificationService> modifiers,
          Player player,
          Set<String> excludedWorlds,
          Set<String> excludedDimensions) {
    if (modifiers.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }
    List<CompletableFuture<List<Transition>>> futures = new ArrayList<>();
    for (SearchModificationService modifier : modifiers) {
      futures.add(modifier.computeTransitions(player));
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            ignored -> {
              List<org.cobblestonemc.Transition<MinecraftStepPayload, MinecraftWorld>> all =
                  new ArrayList<>();
              for (CompletableFuture<List<Transition>> future : futures) {
                for (Transition transition : future.join()) {
                  PaperTransitionAdapter wrapped = new PaperTransitionAdapter(transition, this);
                  // A world is reachable only through a transition, so excluding a world/dimension
                  // means
                  // dropping any transition that crosses into (or out of) it.
                  if (worldAllowed(wrapped.origin().domain(), excludedWorlds, excludedDimensions)
                      && worldAllowed(
                          wrapped.destination().domain(), excludedWorlds, excludedDimensions)) {
                    all.add(wrapped);
                  }
                }
              }
              return all;
            });
  }

  /**
   * Composes every modifier's break checker into one; a block is breakable only if all permit it.
   */
  private BreakChecker<CobblestonePlayer> buildBreakChecker(
      Collection<SearchModificationService> modifiers, Player player) {
    List<org.cobblestonemc.paper.api.BreakChecker> checkers = new ArrayList<>();
    for (SearchModificationService modifier : modifiers) {
      org.cobblestonemc.paper.api.BreakChecker checker = modifier.computeBreakChecker(player);
      if (checker != org.cobblestonemc.paper.api.BreakChecker.ALLOW) {
        checkers.add(checker);
      }
    }
    if (checkers.isEmpty()) {
      return null; // no constraint: the mining mode attaches no restriction future at all
    }
    UUID playerId = player.getUniqueId();
    return (agent, cell, world, block) -> {
      Player online = Bukkit.getPlayer(playerId);
      World bukkitWorld = bukkitWorld(world.key());
      if (online == null || bukkitWorld == null) {
        return CompletableFuture.completedFuture(true); // cannot evaluate; do not block mining
      }
      Location location = new Location(bukkitWorld, cell.x(), cell.y(), cell.z());
      BlockData data = block instanceof PaperBlock paperBlock ? paperBlock.data() : null;
      List<CompletableFuture<Boolean>> results = new ArrayList<>(checkers.size());
      for (org.cobblestonemc.paper.api.BreakChecker checker : checkers) {
        results.add(checker.breakable(online, location, data));
      }
      return allTrue(results);
    };
  }

  /** One composite passability restriction; a cell is impassable if any modifier bars entry. */
  private List<Restriction<CobblestonePlayer, MinecraftWorld>> buildRestrictions(
      Collection<SearchModificationService> modifiers, Player player) {
    List<PassChecker> checkers = new ArrayList<>();
    for (SearchModificationService modifier : modifiers) {
      PassChecker checker = modifier.computePassChecker(player);
      if (checker != PassChecker.ALLOW) {
        checkers.add(checker);
      }
    }
    if (checkers.isEmpty()) {
      return List.of(); // no constraint: Tier-2 skips restriction filtering entirely
    }
    UUID playerId = player.getUniqueId();
    Restriction<CobblestonePlayer, MinecraftWorld> restriction =
        (agent, cell, domain) -> {
          Player online = Bukkit.getPlayer(playerId);
          World bukkitWorld = bukkitWorld(domain.key());
          if (online == null || bukkitWorld == null) {
            return FutureOr.of(false); // cannot evaluate; do not bar entry
          }
          Location location = new Location(bukkitWorld, cell.x(), cell.y(), cell.z());
          List<CompletableFuture<Boolean>> results = new ArrayList<>(checkers.size());
          for (PassChecker checker : checkers) {
            results.add(checker.passable(online, location));
          }
          return FutureOr.from(anyFalse(results));
        };
    return List.of(restriction);
  }

  /** Counts the ender pearls in the player's inventory (read on the server thread). */
  private static int countEnderPearls(Player player) {
    int count = 0;
    for (ItemStack item : player.getInventory().getContents()) {
      if (item != null && item.getType() == Material.ENDER_PEARL) {
        count += item.getAmount();
      }
    }
    return count;
  }

  private static World bukkitWorld(String key) {
    NamespacedKey namespacedKey = NamespacedKey.fromString(key);
    return namespacedKey == null ? null : Bukkit.getWorld(namespacedKey);
  }

  /**
   * A future of whether every input is {@code true} (AND). Already-complete inputs stay immediate.
   */
  private static CompletableFuture<Boolean> allTrue(List<CompletableFuture<Boolean>> futures) {
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            ignored -> {
              for (CompletableFuture<Boolean> future : futures) {
                if (!Boolean.TRUE.equals(future.getNow(Boolean.FALSE))) {
                  return false;
                }
              }
              return true;
            });
  }

  /** A future of whether any input is {@code false} — i.e. some checker bars the action. */
  private static CompletableFuture<Boolean> anyFalse(List<CompletableFuture<Boolean>> futures) {
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            ignored -> {
              for (CompletableFuture<Boolean> future : futures) {
                if (!Boolean.TRUE.equals(future.getNow(Boolean.TRUE))) {
                  return true;
                }
              }
              return false;
            });
  }

  private static boolean worldAllowed(
      MinecraftWorld world, Set<String> excludedWorlds, Set<String> excludedDimensions) {
    return !excludedWorlds.contains(world.key())
        && !excludedDimensions.contains(world.environment().name().toLowerCase(Locale.ROOT));
  }

  @Override
  public MinecraftWorld wrap(World world) {
    return worldCache.computeIfAbsent(
        world.getKey().asString(), key -> new PaperWorld(world, chunkProvider));
  }
}
