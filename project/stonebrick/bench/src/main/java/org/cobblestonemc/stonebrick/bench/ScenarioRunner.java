/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.cobblestonemc.Cell;
import org.cobblestonemc.CobblestoneApi;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.Heuristics;
import org.cobblestonemc.ModesProvider;
import org.cobblestonemc.Position;
import org.cobblestonemc.SearchObserver;
import org.cobblestonemc.SingleDestination;
import org.cobblestonemc.api.FailureReason;
import org.cobblestonemc.api.NavigationResult;
import org.cobblestonemc.api.SearchHandle;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.cobblestonemc.minecraft.modes.MinecraftModes;
import org.cobblestonemc.stonebrick.format.CorpusLayout;
import org.cobblestonemc.stonebrick.platform.Capture;
import org.cobblestonemc.stonebrick.platform.DeterministicScheduler;
import org.cobblestonemc.stonebrick.platform.StonebrickPlatformApi;
import org.cobblestonemc.stonebrick.platform.StonebrickPlayer;
import org.cobblestonemc.stonebrick.platform.StonebrickSolve;

/**
 * Runs one scenario, once, and reports what it cost.
 *
 * <p>Every run is single-threaded against a virtual clock, so a scenario produces the same numbers
 * on any machine at any speed. That is what makes a committed baseline meaningful: if the numbers
 * moved on their own, a baseline would just be a record of which machine last touched it.
 */
public final class ScenarioRunner {

  /**
   * A ceiling on scheduled tasks, so a search that never settles fails rather than hangs.
   *
   * <p>Generous: a long solve legitimately runs millions of tasks. This is a backstop against a
   * livelock, not a budget — {@code maxCellsVisited} and the deadline are the budget, and they are
   * the scenario's to set.
   */
  private static final long MAX_TASKS = 200_000_000L;

  private final Path corpusRoot;
  private final CobblestoneLogger logger;

  /**
   * Creates a runner over a corpus.
   *
   * @param corpusRoot the directory holding captures
   * @param logger where the search logs
   */
  public ScenarioRunner(Path corpusRoot, CobblestoneLogger logger) {
    this.corpusRoot = corpusRoot;
    this.logger = logger;
  }

  /**
   * Runs a scenario.
   *
   * @param scenario the scenario
   * @param observer receives what the search did; pass {@link SearchObserver#none()} when timing
   * @return what it cost
   * @throws IOException if the capture cannot be loaded
   */
  public RunResult run(Scenario scenario, SearchObserver observer) throws IOException {
    Capture capture = Capture.load(CorpusLayout.at(corpusRoot).capture(scenario.capture()).root());
    try (DeterministicScheduler scheduler = new DeterministicScheduler()) {
      StonebrickPlatformApi platform =
          new StonebrickPlatformApi(
              capture,
              scheduler,
              ChunkProviderSettings.defaults(ChunkLoadPolicy.ALLOW_LOAD),
              scenario.io());

      platform.presentAsUngenerated(scenario.ungenerated());

      MinecraftWorld world = platform.world(scenario.world());
      if (world == null) {
        throw new IOException(
            "capture '"
                + scenario.capture()
                + "' holds no world '"
                + scenario.world()
                + "'; it has "
                + capture.worldKeys());
      }

      StonebrickPlayer player =
          StonebrickPlayer.builder()
              .canFly(scenario.agent().canFly())
              .canGlide(scenario.agent().canGlide())
              .hasBoat(scenario.agent().hasBoat())
              .inBoat(scenario.agent().inBoat())
              .permissions(scenario.agent().permissions())
              .build();

      Counting counting = new Counting(observer);
      SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload> handle =
          start(scenario, world, player, scheduler, counting);

      long startedAt = System.nanoTime();
      boolean settled = scheduler.drainUntil(() -> handle.future().isDone(), MAX_TASKS);
      long realMillis = (System.nanoTime() - startedAt) / 1_000_000;

      if (!settled) {
        handle.cancel();
        throw new IOException(
            scenario.id() + ": the solve never settled within " + MAX_TASKS + " tasks");
      }

      NavigationResult<Position<MinecraftWorld>, MinecraftStepPayload> result;
      try {
        result = handle.future().get();
      } catch (Exception e) {
        throw new IOException(scenario.id() + ": " + e.getMessage(), e);
      }

      String missing =
          platform.missing().isEmpty()
                  || scenario.onMissingCapture() == Scenario.MissingCapturePolicy.WALL
              ? null
              : platform.missing().report(scenario.capture());

      Runtime runtime = Runtime.getRuntime();
      return new RunResult(
          scenario.id(),
          outcomeOf(result),
          result
                  instanceof
                  NavigationResult.Success<Position<MinecraftWorld>, MinecraftStepPayload> s
              ? s.path().cost()
              : 0.0,
          result
                  instanceof
                  NavigationResult.Success<Position<MinecraftWorld>, MinecraftStepPayload> s
              ? s.path().duration()
              : 0.0,
          result
                  instanceof
                  NavigationResult.Success<Position<MinecraftWorld>, MinecraftStepPayload> s
              ? s.path().steps().size()
              : 0,
          counting.opened.get(),
          counting.closed.get(),
          platform.ioStats().reads(),
          platform.ioStats().coldReads(),
          scheduler.millis(),
          platform.ioStats().totalDelayMicros(),
          realMillis,
          runtime.totalMemory() - runtime.freeMemory(),
          missing,
          platform.missing().chunks());
    }
  }

  private SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload> start(
      Scenario scenario,
      MinecraftWorld world,
      StonebrickPlayer player,
      DeterministicScheduler scheduler,
      SearchObserver observer) {
    Set<MinecraftStepType> excluded = Set.of();
    ModesProvider<CobblestonePlayer, MinecraftStepPayload, MinecraftWorld> modes =
        MinecraftModes.providerFor(player, excluded, null, 0);
    @SuppressWarnings("unchecked")
    ModesProvider<StonebrickPlayer, MinecraftStepPayload, MinecraftWorld> cast =
        (ModesProvider<StonebrickPlayer, MinecraftStepPayload, MinecraftWorld>) (Object) modes;

    Cell goal = scenario.destination();
    int radius = scenario.destinationRadius();
    DomainRegion<MinecraftWorld> target =
        radius <= 0
            ? StonebrickSolve.at(world, goal)
            : StonebrickSolve.box(
                world,
                new Cell(goal.x() - radius, goal.y() - radius, goal.z() - radius),
                new Cell(goal.x() + radius, goal.y() + radius, goal.z() + radius));

    return CobblestoneApi.load()
        .navigate(
            logger,
            scheduler,
            player,
            new Position<>(scenario.origin(), world),
            new SingleDestination<>(target),
            cast,
            List.of(),
            List.of(),
            Heuristics.runningAverage(MinecraftModes.cheapestCostPerBlock(player, excluded)),
            SearchSettings.builder()
                .heuristicWeight(scenario.settings().heuristicWeight())
                .maxCellsVisited(scenario.settings().maxCellsVisited())
                .maxWallClockMillis(scenario.settings().maxWallClockMillis())
                .build(),
            observer);
  }

  private static String outcomeOf(
      NavigationResult<Position<MinecraftWorld>, MinecraftStepPayload> result) {
    return switch (result) {
      case NavigationResult.Success<Position<MinecraftWorld>, MinecraftStepPayload> ignored ->
          "success";
      case NavigationResult.Failure<Position<MinecraftWorld>, MinecraftStepPayload> failure ->
          failure.reason() == FailureReason.NO_ROUTE
              ? "no_route"
              : failure.reason().name().toLowerCase(java.util.Locale.ROOT);
      case NavigationResult.Error<Position<MinecraftWorld>, MinecraftStepPayload> ignored ->
          "error";
    };
  }

  /**
   * Counts opens and expansions, and passes everything through to whatever else is watching.
   *
   * <p>Wrapping rather than replacing so that a recording run still produces the same counters as a
   * timed one — a recording that reported different numbers from the benchmark it was explaining
   * would be worse than no recording.
   */
  private static final class Counting implements SearchObserver {

    private final SearchObserver delegate;
    final AtomicLong opened = new AtomicLong();
    final AtomicLong closed = new AtomicLong();

    Counting(SearchObserver delegate) {
      this.delegate = delegate;
    }

    @Override
    public void opened(Cell cell, double cost, double estimatedTotalCost) {
      opened.incrementAndGet();
      delegate.opened(cell, cost, estimatedTotalCost);
    }

    @Override
    public void closed(Cell cell, double cost, double estimatedTotalCost) {
      closed.incrementAndGet();
      delegate.closed(cell, cost, estimatedTotalCost);
    }

    @Override
    public void parked() {
      delegate.parked();
    }

    @Override
    public void resumed() {
      delegate.resumed();
    }

    @Override
    public void removed(Cell cell, RemovalReason reason) {
      delegate.removed(cell, reason);
    }

    @Override
    public void solved(List<Cell> cells) {
      delegate.solved(cells);
    }

    @Override
    public void failed(String outcome) {
      delegate.failed(outcome);
    }
  }
}
