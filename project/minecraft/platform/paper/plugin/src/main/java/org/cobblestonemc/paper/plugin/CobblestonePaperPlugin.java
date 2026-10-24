/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.paper.PaperNavigationServiceImpl;
import org.cobblestonemc.paper.api.NavigationService;
import org.cobblestonemc.paper.api.SearchModificationRegistrar;
import org.cobblestonemc.paper.hologram.PaperHolograms;
import org.cobblestonemc.paper.plugin.api.IntegrationRegistrar;
import org.cobblestonemc.paper.plugin.api.TrailNavigatorSettings;
import org.cobblestonemc.paper.plugin.api.TripService;
import org.cobblestonemc.plugin.config.ConfigKeys;
import org.cobblestonemc.plugin.config.ConfigManager;
import org.cobblestonemc.plugin.config.MenuIcons;
import org.cobblestonemc.plugin.data.DataStore;
import org.cobblestonemc.plugin.data.DataStoreException;
import org.cobblestonemc.plugin.data.DataStores;
import org.cobblestonemc.plugin.hologram.TrailLabelManager;
import org.cobblestonemc.plugin.hud.TripHud;
import org.cobblestonemc.plugin.menu.MenuSettings;
import org.cobblestonemc.plugin.menu.RecentDestinations;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.search.SearchGate;
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.trip.TripManager;

/**
 * The Cobblestone Paper/Folia plugin entry point.
 *
 * <p>Phase 6a bootstrap: load config, build the message pipeline, construct the plugin-owned
 * transition registry and the native platform API, register the single {@link NavigationService}
 * service, and wire the {@code /cobblestone} command. Data store, listeners, locations, trips,
 * portal discovery, and the {@code /navigate} tree arrive in Phases 6b/6c.
 */
public final class CobblestonePaperPlugin extends JavaPlugin {

  private PaperNavigationServiceImpl platformApi;
  private DataStore dataStore;
  private TripManager<Entity, PaperTripAgent, Location> tripManager;
  private TripHud<Entity, PaperTripAgent, Location> tripHud;
  private PaperHolograms holograms;
  private PaperMetrics metrics;
  private PaperMountListener mountListener;
  private final SearchRegistry<Location> searchRegistry = new SearchRegistry<>();

  @Override
  public void onEnable() {
    JulCobblestoneLogger logger = new JulCobblestoneLogger(getLogger());

    Path configFile = getDataFolder().toPath().resolve("config.yml");
    ConfigManager config = new ConfigManager(configFile, logger);
    ConfigKeys keys = new ConfigKeys(config, PaperConfigKeys.platform());
    config.load();

    Path databaseFile = getDataFolder().toPath().resolve(config.get(keys.dataFile));
    this.dataStore = DataStores.create(config.get(keys.dataBackend), databaseFile, logger);
    try {
      this.dataStore.init();
    } catch (DataStoreException e) {
      getLogger().severe("Failed to open Cobblestone's data store; disabling. " + e.getMessage());
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    // The transition registry is owned by the plugin; the platform API only reads from / registers
    // into it. Both are reachable to other plugins via the registered plugin API.
    this.platformApi =
        new PaperNavigationServiceImpl(
            this,
            logger,
            new ChunkProviderSettings(
                config.get(keys.chunksCacheSize),
                config.get(keys.chunksPrefetchDistance),
                config.get(keys.chunksFetchAttempts),
                config.get(keys.chunksPolicy)));
    PaperIntegrationRegistry integrationRegistry =
        new PaperIntegrationRegistry(platformApi.regions());
    getServer()
        .getServicesManager()
        .register(NavigationService.class, this.platformApi, this, ServicePriority.Normal);
    // The navigation impl is also the search-modification registrar; the integration registry is
    // the
    // destination/navigator registrar. Both are the single service each layer's facade looks up.
    getServer()
        .getServicesManager()
        .register(
            SearchModificationRegistrar.class, this.platformApi, this, ServicePriority.Normal);
    getServer()
        .getServicesManager()
        .register(IntegrationRegistrar.class, integrationRegistry, this, ServicePriority.Normal);

    Locale defaultLocale = Locale.forLanguageTag(config.get(keys.localeDefault));
    Messages messages = new Messages(defaultLocale, config.get(keys.messagesShowPrefix), logger);

    // Cobblestone's own destinations sit under its key like an integration's, but are written in
    // its own terms rather than adapted from the integration API.
    integrationRegistry.registerProvider(
        this,
        new BuiltInDestinations(
            dataStore.locations(),
            dataStore.deaths(),
            dataStore.mounts(),
            platformApi.scheduler(),
            platformApi.regions(),
            () -> config.get(keys.deathsTrack),
            messages,
            MenuIcons.reader(config, keys.menuIcons)));
    // Each player's last death is recorded so they can navigate back to it.
    getServer()
        .getPluginManager()
        .registerEvents(
            new PaperDeathListener(
                dataStore.deaths(), platformApi.scheduler(), () -> config.get(keys.deathsTrack)),
            this);
    // So are their last mount, and when each of their pets was tamed (to number same-named pets).
    mountListener = new PaperMountListener(dataStore.mounts(), platformApi.scheduler());
    getServer().getPluginManager().registerEvents(mountListener, this);
    getServer().getPluginManager().registerEvents(new PaperPetListener(), this);

    // Trips tick on the platform scheduler (Folia-safe region tasks); the default trail navigator
    // is
    // registered as a service so it is discovered like any third-party navigator.
    this.tripManager =
        new TripManager<>(platformApi.scheduler(), config.get(keys.tripsMaxActivePerPlayer));
    // Each trail floats its trip's name over itself while the player stands still; hitting the
    // Cancel line above it cancels the trip.
    this.holograms = new PaperHolograms(this);
    TrailLabelManager<Entity, PaperTripAgent, Location> trailLabels =
        new TrailLabelManager<>(
            tripManager, platformApi.scheduler(), messages, new PaperHologramPlatform(holograms));
    getServer().getPluginManager().registerEvents(new PaperLabelClickListener(trailLabels), this);
    integrationRegistry.registerNavigator(
        this,
        TrailNavigatorSettings.NAVIGATOR_ID,
        new PaperTrailNavigatorFactory(config, keys, messages, trailLabels));

    // Folia has no scoreboard API.
    boolean folia = isFolia();
    if (folia && config.get(keys.tripsHud)) {
      getLogger().info("The trip HUD is unavailable on Folia.");
    }
    this.tripHud =
        new TripHud<>(
            tripManager,
            platformApi.scheduler(),
            messages,
            dataStore.playerPreferences(),
            () -> !folia && config.get(keys.tripsHud),
            agent -> new PaperHudDisplay(agent.player()),
            logger);
    tripManager.onChange(tripHud::refresh);
    getServer().getOnlinePlayers().forEach(player -> tripHud.load(player.getUniqueId()));

    // Discovered vanilla portals are surfaced to searches as an internal transition provider, and
    // learned from player teleports by the portal listener.
    platformApi.register(
        this,
        new PaperPortalSearchModificationService(
            dataStore.portalTransitions(), dataStore.endReturnPortals(), dataStore.gateways()));
    getServer()
        .getPluginManager()
        .registerEvents(
            new PaperPortalListener(
                dataStore.portalTransitions(),
                dataStore.endReturnPortals(),
                dataStore.gateways(),
                platformApi.scheduler(),
                logger,
                () -> config.get(keys.portalsCostSeconds),
                () -> config.get(keys.portalsDiscovery)),
            this);
    // Optional determinism so block-granular routing is exact (entry off, exit on by default).
    getServer()
        .getPluginManager()
        .registerEvents(
            new PortalNormalizationListener(
                () -> config.get(keys.portalsNormalizeEntry),
                () -> config.get(keys.portalsNormalizeExit)),
            this);

    getServer()
        .getPluginManager()
        .registerEvents(new CobblestoneListener(tripManager, searchRegistry, tripHud), this);
    // Keep the chunk cache honest: a block changing evicts the snapshot it belongs to.
    // When another plugin disables, drop everything it registered into our registries.
    getServer()
        .getPluginManager()
        .registerEvents(new IntegrationLifecycleListener(platformApi, integrationRegistry), this);

    SearchGate searchGate = new SearchGate(config.get(keys.searchMaxConcurrentPerPlayer));
    long liveIntervalMillis = config.get(keys.tripsLiveIntervalTicks) * 50L; // 50 ms per tick
    Supplier<SearchSettings> searchSettings =
        () ->
            SearchSettings.builder()
                .maxCellsVisited(config.get(keys.algorithmMaxCellsVisited))
                .maxWallClockMillis(config.get(keys.algorithmMaxWallClockSeconds) * 1000L)
                .tier1UnsolvedPessimism(config.get(keys.algorithmTier1UnsolvedPessimism))
                .runningAverageWidth(config.get(keys.algorithmRunningAverageWidth))
                .heuristicWeight(config.get(keys.algorithmHeuristicWeight))
                .build();

    // The trip service is the shared "search-then-guide" code path: the /navigate command and
    // integration plugins both start trips through it. Registered as a service like the rest.
    PaperTripServiceImpl tripService =
        new PaperTripServiceImpl(
            platformApi,
            integrationRegistry,
            tripManager,
            searchRegistry,
            searchGate,
            searchSettings,
            liveIntervalMillis);
    getServer()
        .getServicesManager()
        .register(TripService.class, tripService, this, ServicePriority.Normal);

    // The menu starts trips through the command, so both remember them as recent destinations.
    RecentDestinations recents = new RecentDestinations();
    NavigateCommand navigateCommand =
        new NavigateCommand(
            platformApi,
            tripService,
            integrationRegistry,
            searchRegistry,
            searchGate,
            searchSettings,
            logger,
            messages,
            recents);
    PaperNavigationMenus menus =
        new PaperNavigationMenus(
            this,
            integrationRegistry,
            navigateCommand,
            recents,
            messages,
            () -> MenuSettings.from(config, keys),
            logger);
    getServer().getPluginManager().registerEvents(menus, this);

    getLifecycleManager()
        .registerEventHandler(
            LifecycleEvents.COMMANDS,
            event -> {
              // /navigate and /location are also /cobblestone subcommands; each node is built
              // once and shared, so both spellings behave alike.
              var navigate = navigateCommand.node(menus::open);
              var location = CobblestoneCommand.location(messages, dataStore.locations());
              var registrar = event.registrar();
              registrar.register(
                  CobblestoneCommand.build(
                      config,
                      keys,
                      messages,
                      logger,
                      dataStore.portalTransitions(),
                      tripManager,
                      tripHud,
                      searchRegistry,
                      navigate,
                      location),
                  "Cobblestone commands",
                  List.of("stone"));
              registrar.register(navigate, "Navigate to a destination", List.of("nav"));
              registrar.register(location, "Save, remove, or list locations", List.of("loc"));
            });

    if (config.get(keys.metricsEnabled)) {
      this.metrics =
          new PaperMetrics(
              this,
              config.get(keys.dataBackend).name().toLowerCase(Locale.ROOT),
              tripManager,
              searchRegistry);
    }

    getLogger().info("Cobblestone enabled.");
  }

  private static boolean isFolia() {
    return ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"));
  }

  @Override
  public void onDisable() {
    if (metrics != null) {
      metrics.shutdown();
    }
    if (tripHud != null) {
      tripHud.closeAll();
    }
    if (tripManager != null) {
      tripManager.stopEverything(); // also clears the trails' labels
    }
    if (holograms != null) {
      holograms.removeAll();
    }
    if (platformApi != null) {
      // Cancels in-flight searches and stops the search worker pool.
      platformApi.shutdown();
    }
    getServer().getServicesManager().unregisterAll(this);
    if (mountListener != null) {
      try {
        mountListener.saveLoaded();
      } catch (RuntimeException e) {
        getLogger().log(Level.WARNING, "Could not save where loaded mounts are", e);
      }
    }
    if (dataStore != null) {
      dataStore.close();
    }
    getLogger().info("Cobblestone disabled.");
  }
}
