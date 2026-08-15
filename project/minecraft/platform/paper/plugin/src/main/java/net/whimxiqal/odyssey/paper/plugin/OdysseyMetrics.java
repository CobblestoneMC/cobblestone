/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import net.whimxiqal.odyssey.plugin.metrics.MetricsCharts;
import net.whimxiqal.odyssey.plugin.search.SearchRegistry;
import net.whimxiqal.odyssey.plugin.trip.TripManager;
import org.bstats.bukkit.Metrics;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Anonymous usage metrics via bStats (shaded + relocated, design/10). Builds the Bukkit {@link
 * Metrics} object and hands its {@code addCustomChart} to the shared {@link MetricsCharts}, which
 * defines the chart set both platforms report. bStats is opt-out via {@code
 * plugins/bStats/config.yml} and Odyssey's own {@code metrics.enabled}.
 */
final class OdysseyMetrics {

  private final Metrics metrics;

  OdysseyMetrics(
      JavaPlugin plugin,
      String backend,
      TripManager<Entity, PaperTripAgent, Location> trips,
      SearchRegistry<Location> searches) {
    if (MetricsCharts.BSTATS_PLUGIN_ID <= 0) {
      plugin.getLogger().info("bStats metrics not started: no bStats plugin id is configured yet.");
      this.metrics = null;
      return;
    }
    this.metrics = new Metrics(plugin, MetricsCharts.BSTATS_PLUGIN_ID);
    MetricsCharts.register(
        metrics::addCustomChart,
        backend,
        trips::activeCount,
        searches::active,
        searches::searchesLastHour);
  }

  /** Stops metrics reporting; call on plugin disable. */
  void shutdown() {
    if (metrics != null) {
      metrics.shutdown();
    }
  }
}
