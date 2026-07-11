/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.CellRegion;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.api.OdysseyApi;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.api.SingleDestination;
import net.whimxiqal.odyssey.api.Transition;
import net.whimxiqal.odyssey.core.Heuristics;
import net.whimxiqal.odyssey.core.OdysseyApiImpl;
import net.whimxiqal.odyssey.minecraft.ChunkProvider;
import net.whimxiqal.odyssey.minecraft.ChunkProviderSettings;
import net.whimxiqal.odyssey.minecraft.MinecraftModes;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftMode;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.api.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.OdysseyPlayer;
import net.whimxiqal.odyssey.minecraft.api.TransitionProvider;
import net.whimxiqal.odyssey.paper.api.PaperOdysseyApi;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The Paper {@link PaperOdysseyApi} implementation: wires the platform seams (scheduler, chunk
 * provider, world/player wrappers) to the generic core and exposes native-typed navigation.
 *
 * <p>A globally-cheapest per-block cost floor feeds the admissible heuristic (the fastest mode's
 * per-block cost is a valid lower bound for every agent). Uses an admissible {@link Heuristics}.
 */
public final class PaperOdysseyApiImpl implements PaperOdysseyApi {

  private static final double CHEAPEST_COST_PER_BLOCK = 0.08;

  private final PaperScheduler scheduler;
  private final PaperWorlds worlds;
  private final OdysseyApi core;
  private final List<TransitionProvider> providers = new CopyOnWriteArrayList<>();

  /**
   * Creates the API for a plugin.
   *
   * @param plugin the owning plugin
   */
  public PaperOdysseyApiImpl(Plugin plugin) {
    int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    this.scheduler = new PaperScheduler(plugin, workerThreads);
    PaperPlatformApi platform = new PaperPlatformApi(plugin, scheduler);
    ChunkProvider chunkProvider = new ChunkProvider(platform, ChunkProviderSettings.defaults());
    this.worlds = new PaperWorlds(chunkProvider);
    this.core = new OdysseyApiImpl(scheduler, Heuristics.euclidean(CHEAPEST_COST_PER_BLOCK));
  }

  @Override
  public OdysseyApi core() {
    return core;
  }

  @Override
  public void registerTransitionProvider(TransitionProvider provider) {
    providers.add(provider);
  }

  @Override
  public SearchHandle<MinecraftStepType, MinecraftInstruction, MinecraftWorld> navigatePlayer(
      Player player, Location destination) {
    MinecraftWorld world = worlds.wrap(destination.getWorld());
    Cell cell = new Cell(destination.getBlockX(), destination.getBlockY(), destination.getBlockZ());
    Destination<MinecraftWorld> target = new SingleDestination<>(new CellRegion<>(cell, world));
    return navigatePlayer(player, target);
  }

  @Override
  public SearchHandle<MinecraftStepType, MinecraftInstruction, MinecraftWorld> navigatePlayer(
      Player player, Destination<MinecraftWorld> destination) {
    OdysseyPlayer agent = worlds.wrap(player);
    Location location = player.getLocation();
    MinecraftWorld originWorld = worlds.wrap(location.getWorld());
    Position<MinecraftWorld> origin = new Position<>(
        new Cell(location.getBlockX(), location.getBlockY(), location.getBlockZ()), originWorld);
    List<MinecraftMode<OdysseyPlayer>> modes = MinecraftModes.forPlayer(agent, Set.of());

    CompletableFuture<SearchHandle<MinecraftStepType, MinecraftInstruction, MinecraftWorld>> handleFuture =
        gatherTransitions(agent).thenApply(transitions ->
            core.<OdysseyPlayer, MinecraftStepType, MinecraftInstruction, MinecraftWorld>navigate(
                agent, origin, destination, modes, transitions, SearchSettings.defaults()));
    return new DeferredSearchHandle(handleFuture);
  }

  /** Stops the search worker pool; call on plugin disable. */
  public void shutdown() {
    scheduler.shutdown();
  }

  private CompletableFuture<List<Transition<MinecraftStepType, MinecraftInstruction, MinecraftWorld>>>
      gatherTransitions(OdysseyPlayer player) {
    if (providers.isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }
    List<CompletableFuture<List<? extends Transition<MinecraftStepType, MinecraftInstruction, MinecraftWorld>>>>
        futures = new ArrayList<>();
    for (TransitionProvider provider : providers) {
      futures.add(provider.compute(player));
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> {
      List<Transition<MinecraftStepType, MinecraftInstruction, MinecraftWorld>> all = new ArrayList<>();
      for (CompletableFuture<List<? extends Transition<MinecraftStepType, MinecraftInstruction, MinecraftWorld>>>
          future : futures) {
        all.addAll(future.join());
      }
      return all;
    });
  }
}
