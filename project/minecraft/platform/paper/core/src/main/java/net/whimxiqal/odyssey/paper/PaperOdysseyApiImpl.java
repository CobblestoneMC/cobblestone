/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import net.whimxiqal.odyssey.core.BoxRegion;
import net.whimxiqal.odyssey.core.CellRegion;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.api.DomainRegion;
import net.whimxiqal.odyssey.api.HeuristicStrategy;
import net.whimxiqal.odyssey.api.OdysseyApi;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.core.SingleDestination;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.api.Transition;
import net.whimxiqal.odyssey.core.Heuristics;
import net.whimxiqal.odyssey.minecraft.ChunkProvider;
import net.whimxiqal.odyssey.minecraft.ChunkProviderSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftMode;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.api.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.OdysseyPlayer;
import net.whimxiqal.odyssey.minecraft.api.PlatformSingleCellTransition;
import net.whimxiqal.odyssey.minecraft.api.PlatformSingleCellTransitionProvider;
import net.whimxiqal.odyssey.minecraft.api.TransitionRegistry;
import net.whimxiqal.odyssey.minecraft.modes.MinecraftModes;
import net.whimxiqal.odyssey.paper.api.PaperOdysseyApi;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The Paper {@link PaperOdysseyApi} implementation: wires the platform seams (scheduler, chunk
 * provider, world/player wrappers) to the generic core and exposes navigation entirely in native
 * {@link Player}/{@link Location} terms.
 *
 * <p>The transition {@link TransitionRegistry} is supplied by (and owned by) the plugin layer; this
 * class only registers into and reads from it. A globally-cheapest per-block cost floor feeds the
 * admissible {@link Heuristics#euclidean(double)} heuristic.
 */
public final class PaperOdysseyApiImpl implements PaperOdysseyApi {

  private static final double CHEAPEST_COST_PER_BLOCK = 0.08;

  private final PaperScheduler scheduler;
  private final ChunkProvider chunkProvider;
  private final OdysseyApi core;
  private final HeuristicStrategy heuristic;
  private final TransitionRegistry<Player, Location> transitions;
  private final Map<String, MinecraftWorld> worldCache = new ConcurrentHashMap<>();

  /**
   * Creates the API for a plugin.
   *
   * @param plugin      the owning plugin
   * @param transitions the plugin-owned transition registry to register into and read from
   */
  public PaperOdysseyApiImpl(Plugin plugin, TransitionRegistry<Player, Location> transitions) {
    int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    this.scheduler = new PaperScheduler(plugin, workerThreads);
    PaperPlatformApi platform = new PaperPlatformApi(plugin, scheduler);
    this.chunkProvider = new ChunkProvider(platform, ChunkProviderSettings.defaults());
    this.core = OdysseyApi.load();
    this.heuristic = Heuristics.euclidean(CHEAPEST_COST_PER_BLOCK);
    this.transitions = transitions;
  }

  @Override
  public void registerTransitionProvider(PlatformSingleCellTransitionProvider<Player, Location> provider) {
    transitions.register(provider);
  }

  @Override
  public SearchHandle<Step<Location, MinecraftStepType, MinecraftInstruction>> navigatePlayer(
      Player player, Location destination, SearchSettings settings) {
    DomainRegion<MinecraftWorld> region = new CellRegion<>(
        PaperConversions.cell(destination), wrap(destination.getWorld()));
    return search(player, new SingleDestination<>(region), settings);
  }

  @Override
  public SearchHandle<Step<Location, MinecraftStepType, MinecraftInstruction>> navigatePlayerToRegion(
      Player player, Location location1, Location location2, SearchSettings settings) {
    DomainRegion<MinecraftWorld> region = new BoxRegion<>(
        wrap(location1.getWorld()), PaperConversions.cell(location1), PaperConversions.cell(location2));
    return search(player, new SingleDestination<>(region), settings);
  }

  /**
   * Stops the search worker pool; call on plugin disable.
   */
  public void shutdown() {
    scheduler.shutdown();
  }

  private SearchHandle<Step<Location, MinecraftStepType, MinecraftInstruction>> search(
      Player player, Destination<MinecraftWorld> destination, SearchSettings settings) {
    OdysseyPlayer agent = new PaperPlayer(player);
    Location origin = player.getLocation();
    Position<MinecraftWorld> originPosition = new Position<>(
        PaperConversions.cell(origin), wrap(origin.getWorld()));
    List<MinecraftMode<OdysseyPlayer>> modes = MinecraftModes.forPlayer(agent, Set.of());

    CompletableFuture<SearchHandle<Step<Position<MinecraftWorld>, MinecraftStepType, MinecraftInstruction>>>
        handleFuture = gatherTransitions(player).thenApply(gathered ->
        core.navigate(
            scheduler, agent, originPosition, destination, modes, gathered, heuristic, settings));
    return new PaperSearchHandle(handleFuture);
  }

  private CompletableFuture<List<Transition<MinecraftStepType, MinecraftInstruction, MinecraftWorld>>>
  gatherTransitions(Player player) {
    List<PlatformSingleCellTransitionProvider<Player, Location>> providers = transitions.providers();
    if (providers.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }
    List<CompletableFuture<List<? extends PlatformSingleCellTransition<Location>>>> futures = new ArrayList<>();
    for (PlatformSingleCellTransitionProvider<Player, Location> provider : providers) {
      futures.add(provider.compute(player));
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> {
      List<Transition<MinecraftStepType, MinecraftInstruction, MinecraftWorld>> all = new ArrayList<>();
      for (CompletableFuture<List<? extends PlatformSingleCellTransition<Location>>> future : futures) {
        for (PlatformSingleCellTransition<Location> transition : future.join()) {
          all.add(new PaperTransition(transition, this::wrap));
        }
      }
      return all;
    });
  }

  private MinecraftWorld wrap(World world) {
    return worldCache.computeIfAbsent(world.getKey().asString(), key -> new PaperWorld(world, chunkProvider));
  }
}
