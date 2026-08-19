/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin;

import net.whimxiqal.odyssey.plugin.metrics.MetricsCharts;
import net.whimxiqal.odyssey.plugin.search.SearchRegistry;
import net.whimxiqal.odyssey.plugin.trip.TripManager;
import org.bstats.sponge.Metrics;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;

/**
 * Anonymous usage metrics via bStats (shaded + relocated, design/10). Builds the Sponge {@link
 * Metrics} via the injected {@link Metrics.Factory} and hands its {@code addCustomChart} to the
 * shared {@link MetricsCharts}, which defines the chart set both platforms report. Opt-out is via
 * the Sponge server metrics config and Odyssey's own {@code metrics.enabled}.
 */
final class SpongeMetrics {

  /** The bStats service id for Odyssey's Sponge plugin (distinct from the Bukkit registration). */
  private static final int SPONGE_BSTATS_ID = 33513;

  private final Metrics metrics;

  SpongeMetrics(
      Metrics.Factory factory,
      ConstructPluginEvent event,
      String backend,
      TripManager<?, ?, ?> trips,
      SearchRegistry<?> searches) {
    this.metrics = factory.make(SPONGE_BSTATS_ID);
    // We create the Metrics while handling ConstructPluginEvent, so bStats' own @Listener for it
    // has
    // already been dispatched; drive its startup with the in-flight event ourselves.
    metrics.startup(event);
    MetricsCharts.register(
        metrics::addCustomChart,
        backend,
        trips::activeCount,
        searches::active,
        searches::searchesLastHour);
  }

  /** Stops metrics reporting; call on plugin disable. */
  void shutdown() {
    metrics.shutdown();
  }
}
