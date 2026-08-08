/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.config;

import java.util.List;
import net.whimxiqal.odyssey.LogLevel;
import net.whimxiqal.odyssey.plugin.data.DataBackend;

/**
 * Odyssey's registered configuration parameters, grouped to mirror {@code config.yml}. More sections
 * (search, chunks, navigators, trips, data, metrics, …) are added as their subsystems land in later
 * sub-phases; the foundational keys needed to bootstrap the plugin and its messages live here.
 *
 * <p>Hold an instance for the lifetime of the plugin: construct it once with the plugin's
 * {@link ConfigManager} (which registers every key), then read keys via {@code manager.get(...)}.
 */
public final class ConfigKeys {

  /** The locale used for console/system messages (BCP-47 language tag). Immutable. */
  public final ConfigKey<String> localeDefault;

  /** The logging verbosity threshold. Mutable — applied on reload. */
  public final ConfigKey<LogLevel> loggingLevel;

  /** Cap on cells an A* solve may visit before giving up ({@code LIMIT_EXCEEDED}). Mutable. */
  public final ConfigKey<Integer> algorithmMaxCellsVisited;

  /** Wall-clock budget for a whole search, in seconds. Mutable. */
  public final ConfigKey<Integer> algorithmMaxWallClockSeconds;

  /** Tier-1 recalculation overshoot threshold (1.30 = re-plan at 30% over estimate). Mutable. */
  public final ConfigKey<Double> algorithmTier1RecalcThreshold;

  /** Window width for the running-average heuristic. Mutable. */
  public final ConfigKey<Integer> algorithmRunningAverageWidth;

  /** Whether the {@code [✦]} prefix badge precedes every player message. Mutable. */
  public final ConfigKey<Boolean> messagesShowPrefix;

  /** The persistence backend. Changing it requires a restart. */
  public final ConfigKey<DataBackend> dataBackend;

  /**
   * The database file name within the plugin's data folder (embedded backends only). Requires a
   * restart.
   */
  public final ConfigKey<String> dataFile;

  /** How many trips (guided paths) one player may run at once. Mutable. */
  public final ConfigKey<Integer> tripsMaxActivePerPlayer;

  /** How often, in server ticks, a live trip re-runs its search and hot-swaps the path. Mutable. */
  public final ConfigKey<Integer> tripsLiveIntervalTicks;

  /** Blocks a player may stray from the trail before the trip is auto-abandoned. Mutable. */
  public final ConfigKey<Integer> tripsAbandonDistance;

  /** The most concurrent searches (manual + live) one player may run. Requires a restart. */
  public final ConfigKey<Integer> searchMaxConcurrentPerPlayer;

  /** How many cells of the trail ahead the {@code trail} navigator renders. Mutable. */
  public final ConfigKey<Integer> trailBufferCells;

  /** Trail particle colors as hex {@code RRGGBB}, blended into a flowing gradient. Mutable. */
  public final ConfigKey<List<String>> trailColors;

  /** Particles rendered per trail cell each tick. Mutable. */
  public final ConfigKey<Integer> trailDensity;

  /** Whether Odyssey discovers vanilla portal links by watching players teleport. Mutable. */
  public final ConfigKey<Boolean> portalsDiscovery;

  /** The traversal cost, in seconds, assigned to a discovered portal transition. Mutable. */
  public final ConfigKey<Double> portalsCostSeconds;

  /** Whether anonymous bStats metrics are reported. Requires a restart. */
  public final ConfigKey<Boolean> metricsEnabled;

  /**
   * Registers every foundational key on the given manager.
   *
   * @param manager the config manager to populate (before {@link ConfigManager#load()})
   */
  public ConfigKeys(ConfigManager manager) {
    this.localeDefault = manager.register(
        "locale.default", "en", Codec.ofString(), false);
    this.loggingLevel = manager.register(
        "logging.level", LogLevel.INFO, Codec.ofEnum(LogLevel.class), true);
    this.algorithmMaxCellsVisited = manager.register(
        "search.algorithm.max_cells_visited", 10_000, Codec.ofInt(), true);
    this.algorithmMaxWallClockSeconds = manager.register(
        "search.algorithm.max_wall_clock_seconds", 60, Codec.ofInt(), true);
    this.algorithmTier1RecalcThreshold = manager.register(
        "search.algorithm.tier1_recalc_threshold", 1.30, Codec.ofDouble(), true);
    this.algorithmRunningAverageWidth = manager.register(
        "search.algorithm.running_average_width", 5, Codec.ofInt(), true);
    this.messagesShowPrefix = manager.register(
        "messages.show_prefix", true, Codec.ofBoolean(), true);
    this.dataBackend = manager.register(
        "data.backend", DataBackend.SQLITE, Codec.ofEnum(DataBackend.class), false);
    this.dataFile = manager.register(
        "data.file", "odyssey", Codec.ofString(), false);
    this.tripsMaxActivePerPlayer = manager.register(
        "trips.max_active_per_player", 3, Codec.ofInt(), true);
    this.tripsLiveIntervalTicks = manager.register(
        "trips.live_interval_ticks", 100, Codec.ofInt(), true);
    this.tripsAbandonDistance = manager.register(
        "trips.abandon_distance", 32, Codec.ofInt(), true);
    this.searchMaxConcurrentPerPlayer = manager.register(
        "search.max_concurrent_per_player", 1, Codec.ofInt(), false);
    this.trailBufferCells = manager.register(
        "navigators.trail.buffer_cells", 100, Codec.ofInt(), true);
    this.trailColors = manager.register(
        "navigators.trail.colors", List.of("55FFFF", "FFAA00", "FFFFFF"), Codec.ofStringList(), true);
    this.trailDensity = manager.register(
        "navigators.trail.density", 1, Codec.ofInt(), true);
    this.portalsDiscovery = manager.register(
        "portals.discovery", true, Codec.ofBoolean(), true);
    this.portalsCostSeconds = manager.register(
        "portals.cost_seconds", 5.0, Codec.ofDouble(), true);
    this.metricsEnabled = manager.register(
        "metrics.enabled", true, Codec.ofBoolean(), false);
  }
}
