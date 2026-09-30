/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.Cell;
import org.cobblestonemc.CobblestoneApi;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.HeuristicStrategy;
import org.cobblestonemc.Heuristics;
import org.cobblestonemc.ModesProvider;
import org.cobblestonemc.Position;
import org.cobblestonemc.Restriction;
import org.cobblestonemc.SingleDestination;
import org.cobblestonemc.Transition;
import org.cobblestonemc.api.NavigationResult;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.cobblestonemc.minecraft.modes.MinecraftModes;

/**
 * Runs one search over a capture — the single entry point a benchmark, a test, or the visualizer's
 * recorder uses.
 *
 * <p>It exists so that every consumer configures a search the same way. A benchmark whose harness
 * assembled modes slightly differently from the plugin's would be measuring a search nobody runs,
 * and the difference would be invisible in the numbers.
 */
public final class StonebrickSolve {

  private final StonebrickPlatformApi platform;
  private final StonebrickScheduler scheduler;
  private final CobblestoneLogger logger;

  /**
   * Creates a runner over a platform.
   *
   * @param platform the platform reading the capture
   * @param scheduler the scheduler the search runs on
   * @param logger the logger
   */
  public StonebrickSolve(
      StonebrickPlatformApi platform, StonebrickScheduler scheduler, CobblestoneLogger logger) {
    this.platform = platform;
    this.scheduler = scheduler;
    this.logger = logger;
  }

  /**
   * Builds a region covering exactly one cell.
   *
   * @param world the world
   * @param cell the cell
   * @return the region
   */
  public static DomainRegion<MinecraftWorld> at(MinecraftWorld world, Cell cell) {
    return new DomainRegion.Impl<>(world, cell::equals, from -> cell, () -> "cell " + cell);
  }

  /**
   * Builds a region covering an axis-aligned box, inclusive at both ends.
   *
   * @param world the world
   * @param min the lower corner
   * @param max the upper corner
   * @return the region
   */
  public static DomainRegion<MinecraftWorld> box(MinecraftWorld world, Cell min, Cell max) {
    return new DomainRegion.Impl<>(
        world,
        cell ->
            cell.x() >= min.x()
                && cell.x() <= max.x()
                && cell.y() >= min.y()
                && cell.y() <= max.y()
                && cell.z() >= min.z()
                && cell.z() <= max.z(),
        from ->
            new Cell(
                Math.clamp(from.x(), min.x(), max.x()),
                Math.clamp(from.y(), min.y(), max.y()),
                Math.clamp(from.z(), min.z(), max.z())),
        () -> "box " + min + ".." + max);
  }

  /**
   * Runs a search from a cell to a region.
   *
   * @param player the agent
   * @param origin where the agent starts
   * @param target the region sought
   * @param settings the search limits and knobs
   * @param transitions the transitions available, usually empty for a single-world scenario
   * @param restrictions the passability restrictions
   * @return the result, once the search finishes
   */
  public CompletableFuture<NavigationResult<Position<MinecraftWorld>, MinecraftStepPayload>> run(
      StonebrickPlayer player,
      Position<MinecraftWorld> origin,
      DomainRegion<MinecraftWorld> target,
      SearchSettings settings,
      List<? extends Transition<MinecraftStepPayload, MinecraftWorld>> transitions,
      List<? extends Restriction<StonebrickPlayer, MinecraftWorld>> restrictions) {
    ModesProvider<
            org.cobblestonemc.minecraft.CobblestonePlayer, MinecraftStepPayload, MinecraftWorld>
        modes = MinecraftModes.providerFor(player, Set.<MinecraftStepType>of(), null, 0);
    HeuristicStrategy heuristic =
        Heuristics.runningAverage(
            MinecraftModes.cheapestCostPerBlock(player, Set.<MinecraftStepType>of()));
    return CobblestoneApi.load()
        .navigate(
            logger,
            scheduler,
            player,
            origin,
            new SingleDestination<>(target),
            castModes(modes),
            transitions,
            restrictions,
            heuristic,
            settings)
        .future();
  }

  @SuppressWarnings("unchecked")
  private static ModesProvider<StonebrickPlayer, MinecraftStepPayload, MinecraftWorld> castModes(
      ModesProvider<
              org.cobblestonemc.minecraft.CobblestonePlayer, MinecraftStepPayload, MinecraftWorld>
          modes) {
    // A mode only ever reads its agent, so a provider built for the interface serves any
    // implementation of it. The cast is what the platform façades do for the same reason.
    return (ModesProvider<StonebrickPlayer, MinecraftStepPayload, MinecraftWorld>) (Object) modes;
  }

  /**
   * Returns the platform, for checking {@link MissingCaptureLog} after a run.
   *
   * @return the platform
   */
  public StonebrickPlatformApi platform() {
    return platform;
  }
}
