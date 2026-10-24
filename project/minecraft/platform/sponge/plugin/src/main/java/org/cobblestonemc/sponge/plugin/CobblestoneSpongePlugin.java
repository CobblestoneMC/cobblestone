/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import com.google.inject.Inject;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Supplier;
import org.apache.logging.log4j.Logger;
import org.bstats.sponge.Metrics;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.minecraft.regions.RegionStores;
import org.cobblestonemc.plugin.LogoutCleanup;
import org.cobblestonemc.plugin.config.ConfigKeys;
import org.cobblestonemc.plugin.config.ConfigManager;
import org.cobblestonemc.plugin.config.MenuIcons;
import org.cobblestonemc.plugin.config.RegionConfig;
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
import org.cobblestonemc.sponge.AnvilOfflineChunkSource;
import org.cobblestonemc.sponge.SpongeNavigationServiceImpl;
import org.cobblestonemc.sponge.api.CobblestoneCoreApi;
import org.cobblestonemc.sponge.hologram.SpongeHolograms;
import org.cobblestonemc.sponge.plugin.api.CobblestoneSpongeApi;
import org.cobblestonemc.sponge.plugin.api.TrailNavigatorSettings;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.RegisterDataEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

/**
 * The Cobblestone Sponge plugin entry point.
 *
 * <p>On {@link ConstructPluginEvent} it loads config, opens the data store, and installs the core
 * navigation service ({@link CobblestoneCoreApi}) and the plugin-layer trip service + integration
 * registry ({@code CobblestonePluginAPI}) — Sponge has no service manager, so both are installed
 * into static accessors. Vanilla portals are discovered from teleports; locations and the trail
 * navigator are registered like any integration; {@code /navigate} (flags, destination resolution,
 * live re-search) and the {@code /cobblestone} admin tree drive guided trips; bStats metrics report
 * when enabled.
 *
 * <p>One class serves every supported Minecraft version. Reading a chunk off disk — the one thing
 * that looked like it would have to differ — turned out to need nothing from Minecraft's own
 * classes (see {@link AnvilOfflineChunkSource}), so the whole plugin compiles once against the
 * SpongeAPI floor and each shipped jar differs only in its metadata. A version that ever does need
 * something of its own supplies a different {@code OfflineChunkSource} here.
 */
@Plugin("cobblestone")
public final class CobblestoneSpongePlugin {

  private final PluginContainer container;
  private final Logger logger;
  private final Path configDir;
  private RegionStores regionStores;
  private final Metrics.Factory metricsFactory;

  private Log4JCobblestoneLogger cobblestoneLogger;
  private ConfigManager config;
  private ConfigKeys keys;
  private SpongeConfigKeys spongeKeys;
  private DataStore dataStore;
  private SpongeNavigationServiceImpl navigationService;
  private Messages messages;
  private SpongeIntegrationRegistry integrationRegistry;
  private TripManager<Entity, SpongeTripAgent, ServerLocation> tripManager;
  private TripHud<Entity, SpongeTripAgent, ServerLocation> tripHud;
  private SpongeHolograms holograms;
  private SpongeTripServiceImpl tripService;
  private SearchRegistry<ServerLocation> searchRegistry;
  private SearchGate searchGate;
  private Supplier<SearchSettings> searchSettings;
  private SpongeMetrics metrics;
  private SpongePetListener petListener;
  private SpongeMountListener mountListener;
  private NavigateCommand navigateCommand;
  private SpongeNavigationMenus menus;

  @Inject
  CobblestoneSpongePlugin(
      PluginContainer container,
      @ConfigDir(sharedRoot = false) Path configDir,
      Metrics.Factory metricsFactory) {
    this.container = container;
    this.logger = container.logger();
    this.configDir = configDir;
    this.metricsFactory = metricsFactory;
  }

  /** Loads config, opens the data store, and installs the core navigation services. */
  @Listener
  public void onConstructPlugin(ConstructPluginEvent event) {
    this.cobblestoneLogger = new Log4JCobblestoneLogger(logger);
    // Created first: its data key must exist by the time RegisterDataEvent fires, even if the rest
    // of startup fails below.
    this.petListener = new SpongePetListener(container);

    Path configFile = configDir.resolve("config.yml");
    this.config = new ConfigManager(configFile, cobblestoneLogger);
    this.keys = new ConfigKeys(config, SpongeConfigKeys.platform());
    this.spongeKeys = new SpongeConfigKeys(config);
    config.load();

    Path databaseFile = configDir.resolve(config.get(keys.dataFile));
    this.dataStore =
        DataStores.create(config.get(keys.dataBackend), databaseFile, cobblestoneLogger);
    try {
      dataStore.init();
    } catch (DataStoreException.NoDriver e) {
      logger.error(
          "Cobblestone configured to use {} but could not find it on the classpath. Cobblestone is disabled.",
          e.getMissingDriver());
      return;
    } catch (DataStoreException e) {
      logger.error("Failed to open Cobblestone's data store; navigation is disabled.", e);
      return;
    }

    // Regions are shared by every search and forgotten when a block in their chunk changes.
    RegionConfig regionConfig = new RegionConfig(config, keys, logger::warn);
    this.regionStores = regionConfig.stores();

    this.navigationService =
        new SpongeNavigationServiceImpl(
            container,
            cobblestoneLogger,
            new ChunkProviderSettings(
                config.get(keys.chunksCacheSize),
                config.get(keys.chunksPrefetchDistance),
                config.get(keys.chunksFetchAttempts),
                config.get(keys.chunksPolicy)),
            () -> config.get(spongeKeys.chunksMaxLoadRequests),
            new AnvilOfflineChunkSource(cobblestoneLogger),
            regionStores);
    this.navigationService.registerListeners(container);
    CobblestoneCoreApi.install(navigationService, navigationService);

    Sponge.eventManager()
        .registerListeners(container, new SpongeRegionListener(regionStores, navigationService));

    // Discovered vanilla portals are surfaced to searches as an internal modifier, and learned from
    // player teleports by the portal listener. (Nether entry normalization is deferred on Sponge.)
    navigationService.register(
        container,
        new SpongePortalSearchModificationService(
            dataStore.portalTransitions(), dataStore.endReturnPortals(), dataStore.gateways()));
    Sponge.eventManager()
        .registerListeners(
            container,
            new SpongePortalListener(
                dataStore.portalTransitions(),
                dataStore.endReturnPortals(),
                dataStore.gateways(),
                navigationService.scheduler(),
                cobblestoneLogger,
                () -> config.get(keys.portalsCostSeconds),
                () -> config.get(keys.portalsDiscovery)));

    // The plugin-layer: the trip service (search-then-guide) and the integration registry,
    // published
    // through CobblestonePluginAPI. Cobblestone's destinations and trail navigator are registered
    // under its own id, like an integration's.
    Locale defaultLocale = Locale.forLanguageTag(config.get(keys.localeDefault));
    this.messages =
        new Messages(defaultLocale, config.get(keys.messagesShowPrefix), cobblestoneLogger);
    this.tripManager =
        new TripManager<>(navigationService.scheduler(), config.get(keys.tripsMaxActivePerPlayer));
    this.integrationRegistry = new SpongeIntegrationRegistry(navigationService.regions());
    // Each trail floats its trip's name over itself while the player stands still; hitting the
    // Cancel line above it cancels the trip. SpongeAPI can't show an entity to one player only, so
    // everyone nearby sees
    // these labels.
    this.holograms = new SpongeHolograms();
    Sponge.eventManager().registerListeners(container, holograms);
    TrailLabelManager<Entity, SpongeTripAgent, ServerLocation> trailLabels =
        new TrailLabelManager<>(
            tripManager,
            navigationService.scheduler(),
            messages,
            new SpongeHologramPlatform(holograms));
    Sponge.eventManager().registerListeners(container, new SpongeLabelClickListener(trailLabels));
    integrationRegistry.registerNavigator(
        container,
        TrailNavigatorSettings.NAVIGATOR_ID,
        new SpongeTrailNavigatorFactory(config, keys, messages, trailLabels));
    this.tripHud =
        new TripHud<>(
            tripManager,
            navigationService.scheduler(),
            messages,
            dataStore.playerPreferences(),
            () -> config.get(keys.tripsHud),
            agent -> new SpongeHudDisplay(agent.player()),
            cobblestoneLogger);
    tripManager.onChange(tripHud::refresh);
    // Cobblestone's own destinations sit under its id like an integration's, but are written in its
    // own terms rather than adapted from the integration API.
    integrationRegistry.registerProvider(
        container,
        new BuiltInDestinations(
            dataStore.locations(),
            dataStore.deaths(),
            dataStore.mounts(),
            petListener,
            navigationService.regions(),
            navigationService::mayBeGenerated,
            () -> config.get(keys.deathsTrack),
            messages,
            MenuIcons.reader(config, keys.menuIcons)));
    // Each player's last death is recorded so they can navigate back to it.
    Sponge.eventManager()
        .registerListeners(
            container,
            new SpongeDeathListener(
                dataStore.deaths(),
                navigationService.scheduler(),
                () -> config.get(keys.deathsTrack)));
    // So are their last mount, and when each of their pets was tamed (to number same-named pets).
    mountListener = new SpongeMountListener(dataStore.mounts(), navigationService.scheduler());
    Sponge.eventManager().registerListeners(container, mountListener);
    Sponge.eventManager().registerListeners(container, petListener);

    // Keep the chunk cache honest: a block changing evicts the snapshot it belongs to.

    this.searchRegistry = new SearchRegistry<>();
    this.searchGate = new SearchGate(config.get(keys.searchMaxConcurrentPerPlayer));
    long liveIntervalMillis = config.get(keys.tripsLiveIntervalTicks) * 50L; // 50 ms per tick
    this.searchSettings =
        () ->
            SearchSettings.builder()
                .maxCellsVisited(config.get(keys.algorithmMaxCellsVisited))
                .maxWallClockMillis(config.get(keys.algorithmMaxWallClockSeconds) * 1000L)
                .tier1UnsolvedPessimism(config.get(keys.algorithmTier1UnsolvedPessimism))
                .runningAverageWidth(config.get(keys.algorithmRunningAverageWidth))
                .heuristicWeight(config.get(keys.algorithmHeuristicWeight))
                .heuristic(regionConfig.heuristic())
                .build();
    this.tripService =
        new SpongeTripServiceImpl(
            navigationService,
            integrationRegistry,
            tripManager,
            searchRegistry,
            searchGate,
            searchSettings,
            liveIntervalMillis,
            regionStores);
    CobblestoneSpongeApi.install(integrationRegistry, tripService);

    // The menu starts trips through the command, so both remember them as recent destinations.
    RecentDestinations recents = new RecentDestinations();
    this.navigateCommand =
        new NavigateCommand(
            navigationService,
            tripService,
            integrationRegistry,
            searchRegistry,
            searchGate,
            searchSettings,
            cobblestoneLogger,
            messages,
            recents);
    this.menus =
        new SpongeNavigationMenus(
            container,
            integrationRegistry,
            navigateCommand,
            recents,
            messages,
            () -> MenuSettings.from(config, keys),
            cobblestoneLogger);

    if (config.get(keys.metricsEnabled)) {
      this.metrics =
          new SpongeMetrics(
              metricsFactory,
              event,
              config.get(keys.dataBackend).name().toLowerCase(Locale.ROOT),
              tripManager,
              searchRegistry);
    }

    logger.info("Cobblestone enabled.");
  }

  /**
   * Describes Cobblestone's permission nodes once the permission service exists. Sponge has no
   * per-plugin unload event, so there is no counterpart to Paper's disable-time registry purge —
   * plugins live for the whole server run.
   */
  @Listener
  public void onStartedEngine(StartedEngineEvent<Server> event) {
    SpongePermissions.register(container);
    if (navigationService != null) {
      navigationService.surveyWorlds();
    }
  }

  /** Registers the custom data Cobblestone keeps on entities (when each pet was tamed). */
  @Listener
  public void onRegisterData(RegisterDataEvent event) {
    if (petListener != null) {
      petListener.onRegisterData(event);
    }
  }

  /** Registers the {@code /cobblestone} and {@code /navigate} commands. */
  @Listener
  public void onRegisterCommands(RegisterCommandEvent<Command.Parameterized> event) {
    if (tripService == null) {
      return; // the data store failed to open; navigation is disabled
    }
    // /navigate and /location are also /cobblestone subcommands; each command is built once and
    // shared, so both spellings behave alike.
    Command.Parameterized navigate = navigateCommand.command(menus::open);
    Command.Parameterized location = CobblestoneCommand.location(messages, dataStore.locations());
    event.register(
        container,
        CobblestoneCommand.build(
            config,
            keys,
            messages,
            cobblestoneLogger,
            dataStore.portalTransitions(),
            tripManager,
            tripHud,
            searchRegistry,
            navigate,
            location),
        "cobblestone",
        "stone");
    event.register(container, navigate, "navigate", "nav");
    event.register(container, location, "location", "loc");
  }

  /** Loads a joining player's state. */
  @Listener
  public void onJoin(ServerSideConnectionEvent.Join event) {
    if (tripHud != null) {
      tripHud.load(event.player().uniqueId());
    }
  }

  /** Tears down a departing player's state. */
  @Listener
  public void onDisconnect(ServerSideConnectionEvent.Disconnect event) {
    if (tripManager == null) {
      return;
    }
    event
        .profile()
        .map(GameProfile::uuid)
        .ifPresent(uuid -> LogoutCleanup.onLogout(uuid, tripManager, searchRegistry, tripHud));
  }

  /** Tears down: stop the search workers, withdraw the core services, close the data store. */
  @Listener
  public void onStoppingEngine(StoppingEngineEvent<Server> event) {
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
    if (navigationService != null) {
      navigationService.shutdown();
    }
    if (regionStores != null) {
      regionStores.close();
    }
    CobblestoneSpongeApi.uninstall();
    CobblestoneCoreApi.uninstall();
    if (mountListener != null) {
      try {
        mountListener.saveLoaded();
      } catch (RuntimeException e) {
        logger.warn("Could not save where loaded mounts are", e);
      }
    }
    if (dataStore != null) {
      dataStore.close();
    }
    logger.info("Cobblestone disabled.");
  }
}
