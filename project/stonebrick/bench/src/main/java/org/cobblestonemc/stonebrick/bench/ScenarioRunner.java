/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.cobblestonemc.Cell;
import org.cobblestonemc.CobblestoneApi;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.HeuristicStrategy;
import org.cobblestonemc.Heuristics;
import org.cobblestonemc.Position;
import org.cobblestonemc.SearchObserver;
import org.cobblestonemc.SingleDestination;
import org.cobblestonemc.SolveHeuristic;
import org.cobblestonemc.Transition;
import org.cobblestonemc.api.FailureReason;
import org.cobblestonemc.api.NavigationResult;
import org.cobblestonemc.api.SearchHandle;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.minecraft.ChunkProvider;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftHeuristics;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftSearchSettings;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.cobblestonemc.minecraft.movement.MinecraftMovementBehavior;
import org.cobblestonemc.minecraft.regions.MinecraftAgent;
import org.cobblestonemc.minecraft.regions.MinecraftTerrain;
import org.cobblestonemc.minecraft.regions.RegionClass;
import org.cobblestonemc.regions.RegionFiles;
import org.cobblestonemc.regions.RegionHeuristic;
import org.cobblestonemc.regions.RegionSearch;
import org.cobblestonemc.regions.RegionSpec;
import org.cobblestonemc.regions.RegionStore;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.cobblestonemc.stonebrick.format.CorpusLayout;
import org.cobblestonemc.stonebrick.platform.Capture;
import org.cobblestonemc.stonebrick.platform.DeterministicScheduler;
import org.cobblestonemc.stonebrick.platform.StonebrickPlatformApi;
import org.cobblestonemc.stonebrick.platform.StonebrickPlayer;
import org.jetbrains.annotations.Nullable;

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
   * @param loadout what the traveler can do about it
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

      Counting counting = new Counting(observer, scenario.destination());
      SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload> handle =
          start(scenario, loadout, platform, world, player, scheduler, counting);

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

      lastPath =
          result
                  instanceof
                  NavigationResult.Success<Position<MinecraftWorld>, MinecraftStepPayload> s
              ? s.path()
              : null;
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
          platform.regionReads(),
          platform.ioStats().coldReads(),
          scheduler.millis(),
          platform.ioStats().totalDelayMicros(),
          realMillis,
          runtime.totalMemory() - runtime.freeMemory(),
          missing,
          platform.missing().chunks(),
          configuration,
          result instanceof NavigationResult.Success<?, ?> ? 0.0 : counting.closest());
    }
  }

  private SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload> start(
      Scenario scenario,
      Loadout loadout,
      StonebrickPlatformApi platform,
      MinecraftWorld world,
      StonebrickPlayer player,
      DeterministicScheduler scheduler,
      SearchObserver observer) {
    // The exclusions come from the loadout, not from a constant. They used to be empty here, which
    // with a null break checker meant every scenario ran as a player free to tunnel through
    // anything -- one point in the space, and not the representative one.
    Set<MinecraftStepType> excluded = loadout.excludedModes();
    // A chunk cache of the search's own, as in production: how it hits and misses is part of what
    // the benchmark measures.
    ChunkProvider chunks = new ChunkProvider(platform, platform.settings());
    MinecraftMovementBehavior behavior =
        MinecraftMovementBehavior.forPlayer(chunks, player, excluded);

    DomainRegion<MinecraftWorld> target =
        new DomainRegion.Impl<>(
            world,
            scenario::arrives,
            scenario::nearestArrival,
            () -> "the destination of " + scenario.id());

    SearchSettings.Builder builder =
        SearchSettings.builder()
            .heuristicWeight(scenario.settings().heuristicWeight())
            .maxCellsVisited(scenario.settings().maxCellsVisited())
            .maxWallClockMillis(scenario.settings().maxWallClockMillis());
    if (scenario.heuristic().production() != null) {
      builder.heuristic(scenario.heuristic().production());
    }
    SearchSettings settings = builder.build();
    return CobblestoneApi.load()
        .navigate(
            logger,
            scheduler,
            new Position<>(scenario.origin(), world),
            new SingleDestination<>(target),
            behavior,
            warps(scenario, world),
            List.of(),
            legs.counting(
                heuristicFor(
                    scenario,
                    behavior,
                    chunks,
                    player,
                    new MinecraftSearchSettings(settings, excluded, Set.of(), Set.of()),
                    platform)),
            settings,
            observer);
  }

  /** Teleports usable from anywhere in the world, as Essentials offers them. */
  private static List<Transition<MinecraftStepPayload, MinecraftWorld>> warps(
      Scenario scenario, MinecraftWorld world) {
    List<Transition<MinecraftStepPayload, MinecraftWorld>> warps = new ArrayList<>();
    for (Cell warp : scenario.warps()) {
      warps.add(
          new Warp(
              new DomainRegion<>() {
                @Override
                public MinecraftWorld domain() {
                  return world;
                }

                @Override
                public boolean contains(Cell cell) {
                  return true;
                }

                @Override
                public Cell nearestBoundaryCell(Cell from) {
                  return from;
                }
              },
              new Position<>(warp, world)));
    }
    return warps;
  }

  /** A command teleport: {@code TELEPORT_COST_SECONDS} in the Essentials integration. */
  private record Warp(DomainRegion<MinecraftWorld> origin, Position<MinecraftWorld> destination)
      implements Transition<MinecraftStepPayload, MinecraftWorld> {

    @Override
    public double cost() {
      return 3.0;
    }

    @Override
    public MinecraftStepPayload payload() {
      return MinecraftStepPayload.command("warp");
    }
  }

  /** Counts the legs Tier 2 solved in the last run: one new solve of the heuristic each. */
  private final LegCounter legs = new LegCounter();

  /**
   * Returns how many legs the last run's Tier 2 solved, which is more than one only when Tier 1 had
   * routes to choose between.
   *
   * @return the legs solved
   */
  public int lastLegs() {
    return legs.count;
  }

  /** Wraps a heuristic to count its solves. */
  private static final class LegCounter {
    private int count;

    HeuristicStrategy counting(HeuristicStrategy delegate) {
      count = 0;
      return new HeuristicStrategy() {
        @Override
        public double estimate(Cell from, DomainRegion<?> target, TraversalState state) {
          return delegate.estimate(from, target, state);
        }

        @Override
        public SolveHeuristic newSolve(int windowWidth, DomainRegion<?> target) {
          count++;
          return delegate.newSolve(windowWidth, target);
        }
      };
    }
  }

  /** The path the last run found, or {@code null} if it found none. */
  private org.cobblestonemc.api.@Nullable Path<Position<MinecraftWorld>, MinecraftStepPayload>
      lastPath;

  /**
   * Returns the path the last run found, step costs and all.
   *
   * @return the path, or {@code null} if the last run found none
   */
  public org.cobblestonemc.api.@Nullable Path<Position<MinecraftWorld>, MinecraftStepPayload>
      lastPath() {
    return lastPath;
  }

  /**
   * The terrain source the last run used, or {@code null} if it did not run on regions.
   *
   * <p>Kept for the sweep's report of how many distinct chunk columns the region layer read. The
   * reads themselves are {@link RunResult#regionChunkReads()}, gated like the search's own.
   */
  private @Nullable SimulatedDiskChunks lastRegionColumns;

  /**
   * Returns how many chunk columns the last run's region layer read.
   *
   * @return the column count, or 0 if the last run read none
   */
  public int lastRegionColumns() {
    return lastRegionColumns == null ? 0 : lastRegionColumns.chunkColumns();
  }

  /** The solves the last run's region heuristic started, one per leg. */
  private final List<RegionHeuristic<MinecraftChunk, RegionClass>.Solve> lastRegionSolves =
      new ArrayList<>();

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
  public @Nullable String lastRegionBreakdown() {
    if (lastRegionColumns == null || lastRegionStore == null) {
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
    for (RegionHeuristic<MinecraftChunk, RegionClass>.Solve solve : lastRegionSolves) {
      RegionSearch<RegionClass> search = solve.search();
      if (search == null) {
        continue; // a solve that was never asked anything
      }
      queries += search.queries();
      searchNanos += search.searchNanos();
      expansions += search.expansions();
      placeNanos += search.graph().placeNanos();
      seamNanos += search.graph().seamNanos();
      bytes += search.graph().footprintBytes();
      regions += search.graph().regions();
      portals += search.graph().portals();
    }
    RegionStore.Stats store = lastRegionStore.stats();
    long fetchNanos = lastRegionColumns.fetchNanos();

    return String.format(
        Locale.ROOT,
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
        store.unpinnedBytes() / 1024);
  }

  /**
   * Writes the last run's built sections to its cache on disk, if it has one.
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
   * Sets a directory to keep built sections in across runs, as a server keeps them across restarts;
   * {@code null} to build every section afresh each run.
   *
   * @param directory the cache directory, or {@code null}
   */
  public void regionCache(@Nullable Path directory) {
    if (lastRegionStore != null) {
      lastRegionStore.close().join(); // released, so the directory can be deleted
    }
    this.regionCache = directory;
  }

  private @Nullable RegionStore<MinecraftChunk, RegionClass> lastRegionStore;

  private @Nullable Path regionCache;

  /**
   * The detour a region may hold before the fill splits it, from {@code -Dregions.detour}; a tuning
   * knob being measured, not a part of any route.
   */
  static final double REGION_DETOUR =
      Double.parseDouble(
          System.getProperty("regions.detour", String.valueOf(RegionSpec.DEFAULT_DETOUR_RATIO)));

  /**
   * Builds the estimate the scenario asked for.
   *
   * <p>Chosen per scenario rather than globally so that two estimates can be compared over
   * identical terrain, with identical settings, in one run of the suite — which is the only way a
   * difference in expanded nodes means the heuristic rather than the world.
   *
   * <p>Every production estimate but REGIONS is built by {@link MinecraftHeuristics}, the same
   * factory Paper and Sponge use. REGIONS is built here with the factory's parameters, but over the
   * bench's own terrain source and store, so that a run can report what the region layer read,
   * built and kept.
   */
  private HeuristicStrategy heuristicFor(
      Scenario scenario,
      MinecraftMovementBehavior behavior,
      ChunkProvider chunks,
      StonebrickPlayer player,
      MinecraftSearchSettings settings,
      StonebrickPlatformApi platform) {
    // Cleared first, so a run without the region layer does not report the last one's reads.
    lastRegionColumns = null;
    lastRegionSolves.clear();
    lastRegionStore = null;
    if (scenario.heuristic().production() == null) {
      return Heuristics.zero();
    }
    if (scenario.heuristic() != Scenario.Heuristic.REGIONS) {
      return MinecraftHeuristics.forPlayer(
          behavior, chunks, player, scenario.origin(), settings, null);
    }
    SimulatedDiskChunks columns = new SimulatedDiskChunks(platform, scenario.world());
    lastRegionColumns = columns;
    // One store for the run, on this thread: a cold start, or a restart over a cache on disk.
    MinecraftWorld world = platform.world(scenario.world());
    RegionStore<MinecraftChunk, RegionClass> store =
        new RegionStore<>(
            new RegionSpec<>(
                MinecraftTerrain.of(world.environment()), world.bounds(), REGION_DETOUR),
            Long.MAX_VALUE,
            regionCache == null
                ? null
                : regionCache.resolve(
                    RegionFiles.directoryName(scenario.capture() + "-" + scenario.world())),
            Runnable::run,
            Runnable::run);
    lastRegionStore = store;
    RegionHeuristic<MinecraftChunk, RegionClass> regions =
        new RegionHeuristic<>(
            region -> columns,
            region -> store,
            region ->
                MinecraftAgent.forPlayer(player, settings.excludedModes(), world.environment()),
            behavior.cheapestCostPerBlock());
    // Production's heuristic, with each solve kept so the run can report what its search did.
    return new HeuristicStrategy() {
      @Override
      public double estimate(Cell from, DomainRegion<?> target, TraversalState state) {
        return regions.estimate(from, target, state);
      }

      @Override
      public SolveHeuristic newSolve(int windowWidth, DomainRegion<?> target) {
        RegionHeuristic<MinecraftChunk, RegionClass>.Solve solve =
            regions.newSolve(windowWidth, target);
        lastRegionSolves.add(solve);
        return solve;
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
    Map<String, String> configuration = new LinkedHashMap<>();
    configuration.put("heuristic", scenario.heuristic().name());
    configuration.put(
        "heuristicWeight",
        String.format(Locale.ROOT, "%.2f", scenario.settings().heuristicWeight()));
    configuration.put("maxCellsVisited", String.valueOf(scenario.settings().maxCellsVisited()));
    configuration.put("io", scenario.io().toString());
    configuration.put(
        "capture",
        scenario.capture()
            + "@"
            + CaptureDirectory.at(captureRoot).readMeta().getOrDefault("capturedAt", "unknown"));
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
              : failure.reason().name().toLowerCase(Locale.ROOT);
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
    private final Cell destination;
    final AtomicLong opened = new AtomicLong();
    final AtomicLong closed = new AtomicLong();

    /** The least squared straight-line distance to the destination of any cell settled. */
    private volatile long closestSquared = Long.MAX_VALUE;

    Counting(SearchObserver delegate, Cell destination) {
      this.delegate = delegate;
      this.destination = destination;
    }

    /** How near the search came: from the nearest cell it settled to the destination, in blocks. */
    double closest() {
      long squared = closestSquared;
      return squared == Long.MAX_VALUE ? Double.NaN : Math.sqrt(squared);
    }

    @Override
    public void opened(Cell cell, double cost, double estimatedTotalCost) {
      opened.incrementAndGet();
      delegate.opened(cell, cost, estimatedTotalCost);
    }

    @Override
    public void closed(Cell cell, double cost, double estimatedTotalCost) {
      closed.incrementAndGet();
      long dx = cell.x() - destination.x();
      long dy = cell.y() - destination.y();
      long dz = cell.z() - destination.z();
      long squared = dx * dx + dy * dy + dz * dz;
      if (squared < closestSquared) {
        closestSquared = squared;
      }
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
