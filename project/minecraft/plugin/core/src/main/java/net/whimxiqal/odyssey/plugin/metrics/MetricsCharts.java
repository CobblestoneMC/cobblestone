/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.metrics;

import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.bstats.charts.CustomChart;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

/**
 * Odyssey's bStats chart set, defined once and shared across platforms. Each platform constructs
 * its own platform-specific {@code Metrics} (bstats-bukkit / bstats-sponge) and passes that
 * object's {@code addCustomChart} in here, so the chart ids, plugin id, and which gauge feeds which
 * chart all live in one place. The {@code org.bstats.charts.*} types are platform-neutral
 * (bstats-base).
 *
 * <p>Reports the storage backend (categorical) plus live gauges: active trips, currently-active
 * searches, and searches started in the trailing hour (the searches-per-hour rate — an integer,
 * since bStats charts are integer-only). The active-searches gauge is retained mostly for a future
 * Prometheus/Grafana export where a raw instantaneous count is useful.
 */
public final class MetricsCharts {

  /**
   * Odyssey's registered bStats service id; a non-positive value keeps metrics off (a guard for
   * local forks that have not registered their own).
   */
  public static final int BSTATS_PLUGIN_ID = 33218;

  private MetricsCharts() {}

  /**
   * Adds Odyssey's charts to a platform's metrics object.
   *
   * @param adder the platform metrics' {@code addCustomChart}
   * @param backend the configured storage backend name (categorical)
   * @param activeTrips gauge of active trips across all players
   * @param activeSearches gauge of in-flight searches across all players
   * @param searchesPerHour count of searches started in the trailing hour
   */
  public static void register(
      Consumer<CustomChart> adder,
      String backend,
      IntSupplier activeTrips,
      IntSupplier activeSearches,
      IntSupplier searchesPerHour) {
    adder.accept(new SimplePie("data_backend", () -> backend));
    adder.accept(new SingleLineChart("active_trips", activeTrips::getAsInt));
    adder.accept(new SingleLineChart("active_searches", activeSearches::getAsInt));
    adder.accept(new SingleLineChart("searches_per_hour", searchesPerHour::getAsInt));
  }
}
