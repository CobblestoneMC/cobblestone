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
   * @param scenario the route
   * @param loadout what the traveller can do about it
   * @param observer receives what the search did; pass {@link SearchObserver#none()} when timing
   * @return what it cost
   * @throws IOException if the capture cannot be loaded
   */
  public RunResult run(Scenario scenario, Loadout loadout, SearchObserver observer)
      throws IOException {
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
              .canFly(loadout.agent().canFly())
              .canGlide(loadout.agent().canGlide())
              .hasBoat(loadout.agent().hasBoat())
              .inBoat(loadout.agent().inBoat())
              .permissions(loadout.agent().permissions())
              .build();

      Counting counting = new Counting(observer);
      SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload> handle =
          start(scenario, loadout, world, player, scheduler, counting, capture);

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
          scenario.runId(loadout),
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
      Loadout loadout,
      MinecraftWorld world,
      StonebrickPlayer player,
      DeterministicScheduler scheduler,
      SearchObserver observer,
      Capture capture) {
    // The exclusions come from the loadout, not from a constant. They used to be empty here, which
    // with a null break checker meant every scenario ran as a player free to tunnel through
    // anything -- one point in the space, and not the representative one.
    Set<MinecraftStepType> excluded = loadout.excludedModes();
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
            heuristicFor(scenario, loadout, player, excluded, capture),
            SearchSettings.builder()
                .heuristicWeight(scenario.settings().heuristicWeight())
                .maxCellsVisited(scenario.settings().maxCellsVisited())
                .maxWallClockMillis(scenario.settings().maxWallClockMillis())
                .build(),
            observer);
  }

  /**
   * Builds the estimate the scenario asked for.
   *
   * <p>Chosen per scenario rather than globally so the two can be compared over identical terrain,
   * with identical settings, in one run of the suite — which is the only way a difference in
   * expanded nodes means the heuristic rather than the world.
   */
  /**
   * The profile source the last run used, or {@code null} if it did not run on the coarse layer.
   *
   * <p>Exposed rather than folded into {@link RunResult} because the coarse pass's reads are not
   * gated: they bypass the IO model entirely, and recording them as a deterministic metric would
   * imply a rigour they do not have. The sweep prints them so the cost is at least visible.
   */
  private @org.jetbrains.annotations.Nullable CaptureProfiles lastCoarseProfiles;

  public @org.jetbrains.annotations.Nullable CaptureProfiles lastCoarseProfiles() {
    return lastCoarseProfiles;
  }

  private org.cobblestonemc.HeuristicStrategy heuristicFor(
      Scenario scenario,
      Loadout loadout,
      StonebrickPlayer player,
      Set<MinecraftStepType> excluded,
      Capture capture) {
    if (scenario.heuristic() == Scenario.Heuristic.RUNNING_AVERAGE) {
      return Heuristics.runningAverage(MinecraftModes.cheapestCostPerBlock(player, excluded));
    }
    java.util.EnumSet<org.cobblestonemc.minecraft.lod.Medium> mediums =
        java.util.EnumSet.copyOf(org.cobblestonemc.minecraft.lod.CoarseCost.survival());
    // The coarse mediums have to track the loadout's exclusions too, or the estimate prices a
    // route through terrain the fine search is forbidden to cross.
    if (excluded.contains(MinecraftStepType.MINE)) {
      mediums.remove(org.cobblestonemc.minecraft.lod.Medium.MINE);
    }
    if (excluded.contains(MinecraftStepType.SWIM)) {
      mediums.remove(org.cobblestonemc.minecraft.lod.Medium.SWIM);
    }
    if (loadout.agent().canFly() && !excluded.contains(MinecraftStepType.FLY)) {
      mediums.add(org.cobblestonemc.minecraft.lod.Medium.FLY);
    }
    if ((loadout.agent().hasBoat() || loadout.agent().inBoat())
        && !excluded.contains(MinecraftStepType.BOAT)) {
      mediums.add(org.cobblestonemc.minecraft.lod.Medium.BOAT);
    }
    CaptureProfiles profiles = new CaptureProfiles(capture, scenario.world());
    lastCoarseProfiles = profiles;
    return new org.cobblestonemc.minecraft.lod.CoarseHeuristic(
        profiles, org.cobblestonemc.minecraft.lod.CoarseCost.forMediums(mediums));
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
