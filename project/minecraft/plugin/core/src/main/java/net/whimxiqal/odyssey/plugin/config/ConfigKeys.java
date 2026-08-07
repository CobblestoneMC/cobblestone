/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.config;

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

  /** How often, in server ticks, each trip re-renders its trail. Requires a restart. */
  public final ConfigKey<Integer> tripsTickPeriodTicks;

  /** How often, in server ticks, a live trip re-runs its search and hot-swaps the path. Mutable. */
  public final ConfigKey<Integer> tripsLiveIntervalTicks;

  /** The most concurrent searches (manual + live) one player may run. Requires a restart. */
  public final ConfigKey<Integer> searchMaxConcurrentPerPlayer;

  /** How many cells of the trail ahead the {@code trail} navigator renders. Mutable. */
  public final ConfigKey<Integer> trailBufferCells;

  /**
   * Registers every foundational key on the given manager.
   *
   * @param manager the config manager to populate (before {@link ConfigManager#load()})
   */
  public ConfigKeys(ConfigManager manager) {
    this.localeDefault = manager.register(
        "locale.default", "en", Codec.ofString(), false);
    this.messagesShowPrefix = manager.register(
        "messages.show_prefix", true, Codec.ofBoolean(), true);
    this.dataBackend = manager.register(
        "data.backend", DataBackend.SQLITE, Codec.ofEnum(DataBackend.class), false);
    this.dataFile = manager.register(
        "data.file", "odyssey", Codec.ofString(), false);
    this.tripsMaxActivePerPlayer = manager.register(
        "trips.max_active_per_player", 3, Codec.ofInt(), true);
    this.tripsTickPeriodTicks = manager.register(
        "trips.tick_period_ticks", 5, Codec.ofInt(), false);
    this.tripsLiveIntervalTicks = manager.register(
        "trips.live_interval_ticks", 100, Codec.ofInt(), true);
    this.searchMaxConcurrentPerPlayer = manager.register(
        "search.max_concurrent_per_player", 1, Codec.ofInt(), false);
    this.trailBufferCells = manager.register(
        "navigators.trail.buffer_cells", 100, Codec.ofInt(), true);
  }
}
