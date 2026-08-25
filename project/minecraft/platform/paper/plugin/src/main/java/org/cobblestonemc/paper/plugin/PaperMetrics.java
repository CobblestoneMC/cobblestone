/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import org.bstats.bukkit.Metrics;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.cobblestonemc.plugin.metrics.MetricsCharts;
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.trip.TripManager;

/**
 * Anonymous usage metrics via bStats (shaded + relocated, design/10). Builds the Bukkit {@link
 * Metrics} object and hands its {@code addCustomChart} to the shared {@link MetricsCharts}, which
 * defines the chart set both platforms report. bStats is opt-out via {@code
 * plugins/bStats/config.yml} and Cobblestone's own {@code metrics.enabled}.
 */
final class PaperMetrics {

  private static final int BSTATS_PLUGIN_ID = 33624;

  private final Metrics metrics;

  PaperMetrics(
      JavaPlugin plugin,
      String backend,
      TripManager<Entity, PaperTripAgent, Location> trips,
      SearchRegistry<Location> searches) {
    if (BSTATS_PLUGIN_ID <= 0) {
      plugin.getLogger().info("bStats metrics not started: no bStats plugin id is configured yet.");
      this.metrics = null;
      return;
    }
    this.metrics = new Metrics(plugin, BSTATS_PLUGIN_ID);
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
