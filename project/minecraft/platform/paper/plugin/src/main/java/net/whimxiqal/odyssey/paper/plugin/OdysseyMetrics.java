/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import net.whimxiqal.odyssey.plugin.trip.TripManager;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Anonymous usage metrics via bStats (shaded + relocated, design/10). Reports the storage backend
 * (categorical) plus live gauges: active trips, currently-active searches, and searches started in
 * the trailing hour (the searches-per-hour rate — an integer, since bStats charts are
 * integer-only). The active-searches gauge is retained mostly for a future Prometheus/Grafana
 * export where a raw instantaneous count is useful. bStats is opt-out via {@code
 * plugins/bStats/config.yml} and Odyssey's own {@code metrics.enabled}.
 *
 * <p>{@link #BSTATS_PLUGIN_ID} is Odyssey's registered bStats service id; a non-positive value
 * keeps metrics off (a guard for local forks that have not registered their own).
 */
final class OdysseyMetrics {

  private static final int BSTATS_PLUGIN_ID = 33218;

  private final Metrics metrics;

  OdysseyMetrics(
      JavaPlugin plugin,
      String backend,
      TripManager<Entity, PaperTripAgent, Location> trips,
      SearchRegistry searches) {
    if (BSTATS_PLUGIN_ID <= 0) {
      plugin.getLogger().info("bStats metrics not started: no bStats plugin id is configured yet.");
      this.metrics = null;
      return;
    }
    this.metrics = new Metrics(plugin, BSTATS_PLUGIN_ID);
    metrics.addCustomChart(new SimplePie("data_backend", () -> backend));
    metrics.addCustomChart(new SingleLineChart("active_trips", trips::activeCount));
    metrics.addCustomChart(new SingleLineChart("active_searches", searches::active));
    metrics.addCustomChart(new SingleLineChart("searches_per_hour", searches::searchesLastHour));
  }

  /** Stops metrics reporting; call on plugin disable. */
  void shutdown() {
    if (metrics != null) {
      metrics.shutdown();
    }
  }
}
