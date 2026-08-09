/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import net.whimxiqal.odyssey.*;
import net.whimxiqal.odyssey.minecraft.api.*;
import net.whimxiqal.odyssey.paper.api.BoxWorldRegion;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.ChunkProvider;
import net.whimxiqal.odyssey.minecraft.ChunkProviderSettings;
import net.whimxiqal.odyssey.minecraft.MinecraftMode;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.OdysseyPlayer;
import net.whimxiqal.odyssey.minecraft.modes.MinecraftModes;
import net.whimxiqal.odyssey.paper.api.PaperOdysseyApi;
import net.whimxiqal.odyssey.paper.api.PaperTransitionProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.joml.Vector3i;

public final class PaperOdysseyApiImpl implements PaperOdysseyApi, WorldWrapper {

  // The true global-minimum per-block cost (flying, MovementCosts.FLY = 0.10). Used as the admissible
  // Tier-1 bound and the running-average's cold-start estimate.
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
   * @param plugin      the owning plugin
   */
  public PaperOdysseyApiImpl(Plugin plugin, OdysseyLogger logger) {
    this.logger = logger;
    int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    this.scheduler = new PaperScheduler(plugin, workerThreads);
    PaperPlatformApi platform = new PaperPlatformApi(plugin, scheduler);
    this.chunkProvider = new ChunkProvider(platform, ChunkProviderSettings.defaults());
    this.core = OdysseyApi.load();
    this.heuristic = Heuristics.runningAverage(CHEAPEST_COST_PER_BLOCK);
  }

  @Override
  public SearchHandle<Step<Location, MinecraftStepPayload>> navigatePlayer(
      Player player,
      Location destination,
      MinecraftSearchSettings settings) {
    DomainRegion<MinecraftWorld> region = new CellRegion<>(
        PaperConversions.cell(destination), wrap(destination.getWorld()));
    return search(player, new SingleDestination<>(region), settings);
  }

  @Override
  public SearchHandle<Step<Location, MinecraftStepPayload>> navigatePlayerToRegion(
      Player player,
      Location location1,
      Location location2,
      MinecraftSearchSettings settings) {
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
  public SearchHandle<Step<Location, MinecraftStepPayload>> navigatePlayerToDestination(
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
  public net.whimxiqal.odyssey.minecraft.MinecraftScheduler scheduler() {
    return scheduler;
  }

  /**
   * Stops the search worker pool; call on plugin disable.
   */
  public void shutdown() {
    scheduler.shutdown();
  }

  private SearchHandle<Step<Location, MinecraftStepPayload>> search(
      Player player, Destination<DomainRegion<MinecraftWorld>> destination,
      MinecraftSearchSettings settings) {
    OdysseyPlayer agent = new PaperPlayer(player);
    Location origin = player.getLocation();
    Position<MinecraftWorld> originPosition = new Position<>(
        PaperConversions.cell(origin), wrap(origin.getWorld()));
    List<MinecraftMode<OdysseyPlayer>> modes = MinecraftModes.forPlayer(agent, settings.excludedModes());

    CompletableFuture<SearchHandle<Step<Position<MinecraftWorld>, MinecraftStepPayload>>>
        handleFuture = gatherTransitions(player, settings.excludedWorlds(), settings.excludedDimensions()).thenApply(gathered ->
        core.navigate(
            logger, scheduler, agent, originPosition, destination, modes, gathered, heuristic, settings.settings()));
    return new PaperSearchHandle(handleFuture);
  }

  private CompletableFuture<List<Transition<MinecraftStepPayload, MinecraftWorld>>>
  gatherTransitions(Player player, Set<String> excludedWorlds, Set<String> excludedDimensions) {
    List<PaperTransitionProvider> providers = Bukkit.getServicesManager().getRegistrations(PaperTransitionProvider.class).stream().map(RegisteredServiceProvider::getProvider).toList();
    if (providers.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }
    List<CompletableFuture<List<? extends PlatformTransition<WorldRegion<World, Vector3i>, Location>>>> futures = new ArrayList<>();
    for (PaperTransitionProvider provider : providers) {
      futures.add(provider.compute(player));
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> {
      List<Transition<MinecraftStepPayload, MinecraftWorld>> all = new ArrayList<>();
      for (CompletableFuture<List<? extends PlatformTransition<WorldRegion<World, Vector3i>, Location>>> future : futures) {
        for (PlatformTransition<WorldRegion<World, Vector3i>, Location> transition : future.join()) {
          PaperTransition wrapped = new PaperTransition(transition, this);
          // A world is reachable only through a transition, so excluding a world/dimension means
          // dropping any transition that crosses into (or out of) it.
          if (worldAllowed(wrapped.origin().domain(), excludedWorlds, excludedDimensions)
              && worldAllowed(wrapped.destination().domain(), excludedWorlds, excludedDimensions)) {
            all.add(wrapped);
          }
        }
      }
      return all;
    });
  }

  private static boolean worldAllowed(
      MinecraftWorld world, Set<String> excludedWorlds, Set<String> excludedDimensions) {
    return !excludedWorlds.contains(world.key())
        && !excludedDimensions.contains(world.environment().name().toLowerCase(Locale.ROOT));
  }

  @Override
  public MinecraftWorld wrap(World world) {
    return worldCache.computeIfAbsent(world.getKey().asString(), key -> new PaperWorld(world, chunkProvider));
  }
}
