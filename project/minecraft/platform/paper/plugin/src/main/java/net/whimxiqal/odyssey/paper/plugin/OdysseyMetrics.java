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
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Anonymous usage metrics via bStats (shaded + relocated, design/10). Reports the storage backend and
 * portal-discovery setting (categorical) plus live gauges for active trips and searches. bStats is
 * opt-out via {@code plugins/bStats/config.yml} and Odyssey's own {@code metrics.enabled}.
 *
 * <p>The {@link #BSTATS_PLUGIN_ID} must be set to the id assigned when the plugin is registered at
 * bstats.org; until then metrics stay off so no data is sent under someone else's id.
 */
final class OdysseyMetrics {

  // TODO: replace with the real service id from bstats.org once the plugin is registered there.
  private static final int BSTATS_PLUGIN_ID = 0;

  private final Metrics metrics;

  OdysseyMetrics(
      JavaPlugin plugin,
      String backend,
      boolean portalDiscovery,
      TripManager<Location> trips,
      SearchRegistry searches) {
    if (BSTATS_PLUGIN_ID <= 0) {
      plugin.getLogger().info("bStats metrics not started: no bStats plugin id is configured yet.");
      this.metrics = null;
      return;
    }
    this.metrics = new Metrics(plugin, BSTATS_PLUGIN_ID);
    metrics.addCustomChart(new SimplePie("data_backend", () -> backend));
    metrics.addCustomChart(
        new SimplePie("portal_discovery", () -> portalDiscovery ? "enabled" : "disabled"));
    metrics.addCustomChart(new SingleLineChart("active_trips", trips::activeCount));
    metrics.addCustomChart(new SingleLineChart("active_searches", searches::active));
  }

  /** Stops metrics reporting; call on plugin disable. */
  void shutdown() {
    if (metrics != null) {
      metrics.shutdown();
    }
  }
}
