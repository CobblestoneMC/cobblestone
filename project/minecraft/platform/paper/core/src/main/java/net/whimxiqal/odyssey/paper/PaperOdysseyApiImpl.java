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

import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.PlatformTransition;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.paper.api.BoxWorldRegion;
import net.whimxiqal.odyssey.CellRegion;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.DomainRegion;
import net.whimxiqal.odyssey.HeuristicStrategy;
import net.whimxiqal.odyssey.OdysseyApi;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.SingleDestination;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.Transition;
import net.whimxiqal.odyssey.Heuristics;
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

  private static final double CHEAPEST_COST_PER_BLOCK = 0.08;

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
  public PaperOdysseyApiImpl(Plugin plugin) {
    int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    this.scheduler = new PaperScheduler(plugin, workerThreads);
    PaperPlatformApi platform = new PaperPlatformApi(plugin, scheduler);
    this.chunkProvider = new ChunkProvider(platform, ChunkProviderSettings.defaults());
    this.core = OdysseyApi.load();
    this.heuristic = Heuristics.euclidean(CHEAPEST_COST_PER_BLOCK);
  }

  @Override
  public SearchHandle<Step<Location, MinecraftStepPayload>> navigatePlayer(
      Player player, Location destination, SearchSettings settings) {
    DomainRegion<MinecraftWorld> region = new CellRegion<>(
        PaperConversions.cell(destination), wrap(destination.getWorld()));
    return search(player, new SingleDestination<>(region), settings);
  }

  @Override
  public SearchHandle<Step<Location, MinecraftStepPayload>> navigatePlayerToRegion(
      Player player, Location location1, Location location2, SearchSettings settings) {
    BoxWorldRegion region = BoxWorldRegion.of(location1, location2);
    return search(player, new SingleDestination<>(PaperConversions.region(region, this)), settings);
  }

  /**
   * Stops the search worker pool; call on plugin disable.
   */
  public void shutdown() {
    scheduler.shutdown();
  }

  private SearchHandle<Step<Location, MinecraftStepPayload>> search(
      Player player, Destination<DomainRegion<MinecraftWorld>> destination, SearchSettings settings) {
    OdysseyPlayer agent = new PaperPlayer(player);
    Location origin = player.getLocation();
    Position<MinecraftWorld> originPosition = new Position<>(
        PaperConversions.cell(origin), wrap(origin.getWorld()));
    List<MinecraftMode<OdysseyPlayer>> modes = MinecraftModes.forPlayer(agent, Set.of());

    CompletableFuture<SearchHandle<Step<Position<MinecraftWorld>, MinecraftStepPayload>>>
        handleFuture = gatherTransitions(player).thenApply(gathered ->
        core.navigate(
            scheduler, agent, originPosition, destination, modes, gathered, heuristic, settings));
    return new PaperSearchHandle(handleFuture);
  }

  private CompletableFuture<List<Transition<MinecraftStepPayload, MinecraftWorld>>>
  gatherTransitions(Player player) {
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
          all.add(new PaperTransition(transition, this));
        }
      }
      return all;
    });
  }

  @Override
  public MinecraftWorld wrap(World world) {
    return worldCache.computeIfAbsent(world.getKey().asString(), key -> new PaperWorld(world, chunkProvider));
  }
}
