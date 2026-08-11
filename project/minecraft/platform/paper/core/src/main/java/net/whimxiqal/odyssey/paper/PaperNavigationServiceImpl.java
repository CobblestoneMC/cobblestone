/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.whimxiqal.odyssey.CellRegion;
import net.whimxiqal.odyssey.DomainRegion;
import net.whimxiqal.odyssey.FutureOr;
import net.whimxiqal.odyssey.HeuristicStrategy;
import net.whimxiqal.odyssey.Heuristics;
import net.whimxiqal.odyssey.OdysseyApi;
import net.whimxiqal.odyssey.OdysseyLogger;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.Restriction;
import net.whimxiqal.odyssey.SingleDestination;
import net.whimxiqal.odyssey.Transition;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.minecraft.BreakChecker;
import net.whimxiqal.odyssey.minecraft.ChunkProvider;
import net.whimxiqal.odyssey.minecraft.ChunkProviderSettings;
import net.whimxiqal.odyssey.minecraft.MinecraftMode;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.OdysseyPlayer;
import net.whimxiqal.odyssey.minecraft.api.MinecraftSearchSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.minecraft.modes.MinecraftModes;
import net.whimxiqal.odyssey.paper.api.BoxWorldRegion;
import net.whimxiqal.odyssey.paper.api.PaperBreakChecker;
import net.whimxiqal.odyssey.paper.api.PaperNavigationService;
import net.whimxiqal.odyssey.paper.api.PaperOdysseySearchModifier;
import net.whimxiqal.odyssey.paper.api.PaperPassChecker;
import net.whimxiqal.odyssey.paper.api.PaperTransition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.joml.Vector3i;

public final class PaperNavigationServiceImpl implements PaperNavigationService, WorldWrapper {

  // The true global-minimum per-block cost (flying, MovementCosts.FLY = 0.10). Used as the
  // admissible Tier-1 bound and the running-average's cold-start estimate.
  private static final double CHEAPEST_COST_PER_BLOCK = 0.10;

  private final OdysseyLogger logger;
  private final PaperScheduler scheduler;
  private final ChunkProvider chunkProvider;
  private final OdysseyApi core;
  private final HeuristicStrategy heuristic;
  private final Map<String, MinecraftWorld> worldCache = new ConcurrentHashMap<>();

  /**
   * Creates the API for a plugin.
   *
   * @param plugin the owning plugin
   */
  public PaperNavigationServiceImpl(Plugin plugin, OdysseyLogger logger) {
    this.logger = logger;
    int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    this.scheduler = new PaperScheduler(plugin, workerThreads);
    PaperPlatformApi platform = new PaperPlatformApi(plugin, scheduler);
    this.chunkProvider = new ChunkProvider(platform, ChunkProviderSettings.defaults());
    this.core = OdysseyApi.load();
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
   * Begins a search toward a plugin-provided {@link Destination} (e.g. a resolved waypoint), which
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
  public net.whimxiqal.odyssey.minecraft.MinecraftScheduler<Entity> scheduler() {
    return scheduler;
  }

  /** Stops the search worker pool; call on plugin disable. */
  public void shutdown() {
    scheduler.shutdown();
  }

  private SearchHandle<Location, MinecraftStepPayload> search(
      Player player,
      Destination<DomainRegion<MinecraftWorld>> destination,
      MinecraftSearchSettings settings) {
    OdysseyPlayer agent = new PaperPlayer(player);
    Location origin = player.getLocation();
    Position<MinecraftWorld> originPosition =
        new Position<>(PaperConversions.cell(origin), wrap(origin.getWorld()));
    // One snapshot of the registered modifiers drives all three influences on this search.
    List<PaperOdysseySearchModifier> modifiers =
        Bukkit.getServicesManager().getRegistrations(PaperOdysseySearchModifier.class).stream()
            .map(RegisteredServiceProvider::getProvider)
            .toList();
    BreakChecker<OdysseyPlayer> breakChecker = buildBreakChecker(modifiers, player);
    List<Restriction<OdysseyPlayer, MinecraftWorld>> restrictions =
        buildRestrictions(modifiers, player);
    List<MinecraftMode<OdysseyPlayer>> modes =
        MinecraftModes.forPlayer(agent, settings.excludedModes(), breakChecker);

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

  private CompletableFuture<List<Transition<MinecraftStepPayload, MinecraftWorld>>>
      gatherTransitions(
          List<PaperOdysseySearchModifier> modifiers,
          Player player,
          Set<String> excludedWorlds,
          Set<String> excludedDimensions) {
    if (modifiers.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }
    List<CompletableFuture<List<PaperTransition>>> futures = new ArrayList<>();
    for (PaperOdysseySearchModifier modifier : modifiers) {
      futures.add(modifier.computeTransitions(player));
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            ignored -> {
              List<Transition<MinecraftStepPayload, MinecraftWorld>> all = new ArrayList<>();
              for (CompletableFuture<List<PaperTransition>> future : futures) {
                for (PaperTransition transition : future.join()) {
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
  private BreakChecker<OdysseyPlayer> buildBreakChecker(
      List<PaperOdysseySearchModifier> modifiers, Player player) {
    List<PaperBreakChecker> checkers = new ArrayList<>();
    for (PaperOdysseySearchModifier modifier : modifiers) {
      PaperBreakChecker checker = modifier.computeBreakChecker(player);
      if (checker != PaperBreakChecker.ALLOW) {
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
      for (PaperBreakChecker checker : checkers) {
        results.add(checker.breakable(online, location, data));
      }
      return allTrue(results);
    };
  }

  /** One composite passability restriction; a cell is impassable if any modifier bars entry. */
  private List<Restriction<OdysseyPlayer, MinecraftWorld>> buildRestrictions(
      List<PaperOdysseySearchModifier> modifiers, Player player) {
    List<PaperPassChecker> checkers = new ArrayList<>();
    for (PaperOdysseySearchModifier modifier : modifiers) {
      PaperPassChecker checker = modifier.computePassChecker(player);
      if (checker != PaperPassChecker.ALLOW) {
        checkers.add(checker);
      }
    }
    if (checkers.isEmpty()) {
      return List.of(); // no constraint: Tier-2 skips restriction filtering entirely
    }
    UUID playerId = player.getUniqueId();
    Restriction<OdysseyPlayer, MinecraftWorld> restriction =
        (agent, cell, domain) -> {
          Player online = Bukkit.getPlayer(playerId);
          World bukkitWorld = bukkitWorld(domain.key());
          if (online == null || bukkitWorld == null) {
            return FutureOr.of(false); // cannot evaluate; do not bar entry
          }
          Location location = new Location(bukkitWorld, cell.x(), cell.y(), cell.z());
          List<CompletableFuture<Boolean>> results = new ArrayList<>(checkers.size());
          for (PaperPassChecker checker : checkers) {
            results.add(checker.passable(online, location));
          }
          return FutureOr.from(anyFalse(results));
        };
    return List.of(restriction);
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
