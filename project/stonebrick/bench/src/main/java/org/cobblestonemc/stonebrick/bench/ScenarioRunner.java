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
import java.util.Map;
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
    Path captureRoot = CorpusLayout.at(corpusRoot).capture(scenario.capture()).root();
    Capture capture = Capture.load(captureRoot);
    Map<String, String> configuration = configurationOf(scenario, captureRoot);
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

      StonebrickPlayer player = loadout.player();

      Counting counting = new Counting(observer);
      SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload> handle =
          start(scenario, loadout, world, player, scheduler, counting, platform);

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
          platform.profileReads(),
          platform.ioStats().coldReads(),
          scheduler.millis(),
          platform.ioStats().totalDelayMicros(),
          realMillis,
          runtime.totalMemory() - runtime.freeMemory(),
          missing,
          platform.missing().chunks(),
          configuration);
    }
  }

  private SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload> start(
      Scenario scenario,
      Loadout loadout,
      MinecraftWorld world,
      StonebrickPlayer player,
      DeterministicScheduler scheduler,
      SearchObserver observer,
      StonebrickPlatformApi platform) {
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
            heuristicFor(scenario, loadout, player, excluded, platform),
            SearchSettings.builder()
                .heuristicWeight(scenario.settings().heuristicWeight())
                .maxCellsVisited(scenario.settings().maxCellsVisited())
                .maxWallClockMillis(scenario.settings().maxWallClockMillis())
                .build(),
            observer);
  }

  /**
   * The column source the last run used, or {@code null} if it did not run on regions.
   *
   * <p>Exposed rather than folded into {@link RunResult} because the region layer's reads are not
   * gated, and recording them as a deterministic metric would imply a rigour they do not have. The
   * sweep prints them so the cost is at least visible.
   */
  private @org.jetbrains.annotations.Nullable CaptureChunkColumns lastRegionColumns;

  /**
   * Returns how many chunk columns the last run's region layer read.
   *
   * @return the column count, or 0 if the last run read none
   */
  public int lastRegionColumns() {
    return lastRegionColumns == null ? 0 : lastRegionColumns.chunkColumns();
  }

  private org.cobblestonemc.minecraft.lod.@org.jetbrains.annotations.Nullable RegionHeuristic
      lastRegionHeuristic;

  /**
   * Returns where the last run's region layer spent its time and memory, or {@code null} if it did
   * not run on regions.
   *
   * <p>Wall-clock, so it varies from machine to machine and run to run; it is reported, never
   * gated. The parts nest: reading a column happens inside building the graph, and building it
   * happens inside answering a query, so each is subtracted from the one around it.
   *
   * @return the breakdown, as {@code key=value} pairs
   */
  public @org.jetbrains.annotations.Nullable String lastRegionBreakdown() {
    if (lastRegionColumns == null || lastRegionHeuristic == null) {
      return null;
    }
    long queries = 0;
    long searchNanos = 0;
    long placeNanos = 0;
    long seamNanos = 0;
    long bytes = 0;
    long regions = 0;
    long portals = 0;
    long expansions = 0;
    for (org.cobblestonemc.minecraft.lod.RegionSearch search : lastRegionHeuristic.searches()) {
      queries += search.queries();
      searchNanos += search.searchNanos();
      expansions += search.expansions();
      placeNanos += search.graph().placeNanos();
      seamNanos += search.graph().seamNanos();
      bytes += search.graph().footprintBytes();
      regions += search.graph().regions();
      portals += search.graph().portals();
    }
    org.cobblestonemc.minecraft.lod.RegionStore.Stats store = lastRegionStore.stats();
    long fetchNanos = lastRegionColumns.fetchNanos();

    return String.format(
        java.util.Locale.ROOT,
        "readMs=%.1f buildMs=%.1f diskMs=%.1f placeMs=%.1f seamMs=%.1f searchMs=%.1f queries=%d"
            + " settled=%d built=%d fromDisk=%d diskKB=%d regions=%d portals=%d graphKB=%d"
            + " storeKB=%d",
        fetchNanos / 1e6,
        store.buildNanos() / 1e6,
        store.diskNanos() / 1e6,
        placeNanos / 1e6,
        seamNanos / 1e6,
        searchNanos / 1e6,
        queries,
        expansions,
        store.builds(),
        store.diskLoads(),
        store.diskBytesRead() / 1024,
        regions,
        portals,
        bytes / 1024,
        store.bytesInMemory() / 1024);
  }

  /**
   * Writes the last run's built columns to its cache on disk, if it has one.
   *
   * <p>What a server's periodic flush does; a warm sweep calls it between the run that fills the
   * cache and the run it measures.
   */
  public void flushRegionCache() {
    if (lastRegionStore != null) {
      lastRegionStore.flush().join();
      lastRegionStore.close().join();
    }
  }

  /**
   * Sets a directory to keep built columns in across runs, as a server keeps them across restarts;
   * {@code null} to build every column afresh each run.
   *
   * @param directory the cache directory, or {@code null}
   */
  public void regionCache(@org.jetbrains.annotations.Nullable Path directory) {
    if (lastRegionStore != null) {
      lastRegionStore.close().join(); // released, so the directory can be deleted
    }
    this.regionCache = directory;
  }

  private org.cobblestonemc.minecraft.lod.@org.jetbrains.annotations.Nullable RegionStore
      lastRegionStore;

  private @org.jetbrains.annotations.Nullable Path regionCache;

  /**
   * The detour a region may hold before the fill splits it, from {@code -Dregions.detour}; a tuning
   * knob being measured, not a part of any route.
   */
  static final double REGION_DETOUR =
      Double.parseDouble(
          System.getProperty(
              "regions.detour",
              String.valueOf(org.cobblestonemc.minecraft.lod.RegionGraph.DEFAULT_DETOUR_RATIO)));

  /**
   * Builds the estimate the scenario asked for.
   *
   * <p>Chosen per scenario rather than globally so heuristics can be compared over identical
   * terrain, with identical settings, in one run of the suite — which is the only way a difference
   * in expanded nodes means the heuristic rather than the world.
   */
  private org.cobblestonemc.HeuristicStrategy heuristicFor(
      Scenario scenario,
      Loadout loadout,
      StonebrickPlayer player,
      Set<MinecraftStepType> excluded,
      StonebrickPlatformApi platform) {
    // Cleared first, so a run without the region layer does not report the last one's reads.
    lastRegionColumns = null;
    lastRegionHeuristic = null;
    lastRegionStore = null;
    return switch (scenario.heuristic()) {
      case ZERO -> Heuristics.zero();
      case RUNNING_AVERAGE ->
          Heuristics.runningAverage(MinecraftModes.cheapestCostPerBlock(player, excluded));
      case REGIONS -> {
        CaptureChunkColumns columns = new CaptureChunkColumns(platform, scenario.world());
        lastRegionColumns = columns;
        // One store for the run, on this thread: a cold start, or a restart over a cache on disk.
        MinecraftWorld world = platform.world(scenario.world());
        org.cobblestonemc.minecraft.lod.RegionColumnBuilder shape =
            new org.cobblestonemc.minecraft.lod.RegionColumnBuilder(
                world.minY(), world.maxY(), REGION_DETOUR);
        lastRegionStore =
            new org.cobblestonemc.minecraft.lod.RegionStore(
                world.minY(),
                world.maxY(),
                REGION_DETOUR,
                Long.MAX_VALUE,
                regionCache == null
                    ? null
                    : new org.cobblestonemc.minecraft.lod.RegionFiles(
                        regionCache.resolve(
                            org.cobblestonemc.minecraft.lod.RegionFiles.directoryName(
                                scenario.capture() + "-" + scenario.world())),
                        shape.minY(),
                        shape.sections(),
                        REGION_DETOUR),
                Runnable::run,
                Runnable::run);
        org.cobblestonemc.minecraft.lod.RegionStore store = lastRegionStore;
        lastRegionHeuristic =
            new org.cobblestonemc.minecraft.lod.RegionHeuristic(
                region -> columns,
                region -> store,
                org.cobblestonemc.minecraft.lod.RegionCost.forPlayer(player, excluded),
                REGION_DETOUR);
        yield lastRegionHeuristic;
      }
    };
  }

  /**
   * Returns what a run measured, for a baseline to be stamped with.
   *
   * <p>The capture is identified by when it was taken. Terrain from a seed is <em>not</em>
   * reproducible: two captures of the same region from the same seed and Paper build differ in
   * their decorations, by enough to move a route's cost by several percent and its expansions by
   * half. A baseline is therefore a fact about one capture, and comparing it against another would
   * report the terrain's differences as the algorithm's.
   */
  private static Map<String, String> configurationOf(Scenario scenario, Path captureRoot)
      throws IOException {
    Map<String, String> configuration = new java.util.LinkedHashMap<>();
    configuration.put("heuristic", scenario.heuristic().name());
    configuration.put(
        "heuristicWeight",
        String.format(java.util.Locale.ROOT, "%.2f", scenario.settings().heuristicWeight()));
    configuration.put("maxCellsVisited", String.valueOf(scenario.settings().maxCellsVisited()));
    configuration.put("io", scenario.io().toString());
    configuration.put(
        "capture",
        scenario.capture()
            + "@"
            + org.cobblestonemc.stonebrick.format.CaptureDirectory.at(captureRoot)
                .readMeta()
                .getOrDefault("capturedAt", "unknown"));
    return configuration;
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
