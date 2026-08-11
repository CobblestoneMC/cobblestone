/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.paper.PaperOdysseyApiImpl;
import net.whimxiqal.odyssey.paper.api.OdysseySearchModifier;
import net.whimxiqal.odyssey.paper.api.PaperOdysseyApi;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestinationProvider;
import net.whimxiqal.odyssey.paper.plugin.api.PaperNavigatorFactory;
import net.whimxiqal.odyssey.plugin.config.ConfigKeys;
import net.whimxiqal.odyssey.plugin.config.ConfigManager;
import net.whimxiqal.odyssey.plugin.data.DataStore;
import net.whimxiqal.odyssey.plugin.data.DataStoreException;
import net.whimxiqal.odyssey.plugin.data.DataStores;
import net.whimxiqal.odyssey.plugin.message.Messages;
import net.whimxiqal.odyssey.plugin.trip.TripManager;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Odyssey Paper/Folia plugin entry point.
 *
 * <p>Phase 6a bootstrap: load config, build the message pipeline, construct the plugin-owned
 * transition registry and the native platform API, register the single {@link PaperOdysseyApi}
 * service, and wire the {@code /odyssey} command. Data store, listeners, waypoints, trips, portal
 * discovery, and the {@code /navigate} tree arrive in Phases 6b/6c.
 */
public final class OdysseyPaperPlugin extends JavaPlugin {

  private PaperOdysseyApiImpl platformApi;
  private DataStore dataStore;
  private TripManager<Entity, PaperTripAgent, Location> tripManager;
  private OdysseyMetrics metrics;
  private final SearchRegistry searchRegistry = new SearchRegistry();

  @Override
  public void onEnable() {
    JulOdysseyLogger logger = new JulOdysseyLogger(getLogger());

    Path configFile = getDataFolder().toPath().resolve("config.yml");
    ConfigManager config = new ConfigManager(configFile, "config.yml", logger);
    ConfigKeys keys = new ConfigKeys(config);
    config.load();
    logger.setLevel(config.get(keys.loggingLevel));

    Path databaseFile = getDataFolder().toPath().resolve(config.get(keys.dataFile));
    this.dataStore = DataStores.create(config.get(keys.dataBackend), databaseFile, logger);
    try {
      this.dataStore.init();
    } catch (DataStoreException e) {
      getLogger().severe("Failed to open Odyssey's data store; disabling. " + e.getMessage());
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    // The transition registry is owned by the plugin; the platform API only reads from / registers
    // into it (design/05). Both are reachable to other plugins via the registered plugin API.
    this.platformApi = new PaperOdysseyApiImpl(this, logger);
    getServer().getServicesManager()
        .register(PaperOdysseyApi.class, this.platformApi, this, ServicePriority.Normal);

    // Waypoints are surfaced to searches like any third-party provider: a Bukkit service Odyssey
    // discovers via the ServicesManager.
    getServer().getServicesManager().register(PaperDestinationProvider.class,
        new OdysseyDestinationProvider(dataStore.waypoints()), this, ServicePriority.Normal);

    Locale defaultLocale = Locale.forLanguageTag(config.get(keys.localeDefault));
    Messages messages = new Messages(defaultLocale, config.get(keys.messagesShowPrefix), logger);

    // Trips tick on the platform scheduler (Folia-safe region tasks); the default trail navigator is
    // registered as a service so it is discovered like any third-party navigator.
    this.tripManager = new TripManager<>(platformApi.scheduler(),
        config.get(keys.tripsMaxActivePerPlayer));
    getServer().getServicesManager().register(PaperNavigatorFactory.class,
        new PaperTrailNavigatorFactory(config, keys, messages),
        this, ServicePriority.Normal);

    // Discovered vanilla portals are surfaced to searches as an internal transition provider, and
    // learned from player teleports by the portal listener.
    getServer().getServicesManager().register(OdysseySearchModifier.class,
        new PortalTransitionProvider(dataStore.portalTransitions()), this, ServicePriority.Normal);
    getServer().getPluginManager().registerEvents(
        new PortalListener(dataStore.portalTransitions(), platformApi.scheduler(), logger,
            () -> config.get(keys.portalsCostSeconds), () -> config.get(keys.portalsDiscovery)), this);

    getServer().getPluginManager().registerEvents(
        new OdysseyListener(tripManager, searchRegistry), this);

    SearchGate searchGate = new SearchGate(config.get(keys.searchMaxConcurrentPerPlayer));
    long liveIntervalMillis = config.get(keys.tripsLiveIntervalTicks) * 50L; // 50 ms per tick
    Supplier<SearchSettings> searchSettings = () -> SearchSettings.builder()
        .maxCellsVisited(config.get(keys.algorithmMaxCellsVisited))
        .maxWallClockMillis(config.get(keys.algorithmMaxWallClockSeconds) * 1000L)
        .tier1RecalcThreshold(config.get(keys.algorithmTier1RecalcThreshold))
        .runningAverageWidth(config.get(keys.algorithmRunningAverageWidth))
        .heuristicWeight(config.get(keys.algorithmHeuristicWeight))
        .build();
    getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
      event.registrar().register(
          OdysseyCommand.build(config, keys, messages, logger, dataStore.waypoints(),
              dataStore.portalTransitions(), tripManager, searchRegistry),
          "Odyssey admin and utility commands",
          List.of("ody"));
      event.registrar().register(
          NavigateCommand.build(platformApi, tripManager, searchRegistry, searchGate,
              liveIntervalMillis, searchSettings, logger, messages),
          "Navigate to a destination",
          List.of("nav"));
    });

    if (config.get(keys.metricsEnabled)) {
      this.metrics = new OdysseyMetrics(this,
          config.get(keys.dataBackend).name().toLowerCase(Locale.ROOT),
          config.get(keys.portalsDiscovery), tripManager, searchRegistry);
    }

    getLogger().info("Odyssey enabled.");
  }

  @Override
  public void onDisable() {
    if (metrics != null) {
      metrics.shutdown();
    }
    if (tripManager != null) {
      tripManager.stopEverything();
    }
    if (platformApi != null) {
      // Cancels in-flight searches and stops the search worker pool.
      platformApi.shutdown();
    }
    getServer().getServicesManager().unregisterAll(this);
    if (dataStore != null) {
      dataStore.close();
    }
    getLogger().info("Odyssey disabled.");
  }
}
