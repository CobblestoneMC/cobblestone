/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin;

import com.google.inject.Inject;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.plugin.LogoutCleanup;
import net.whimxiqal.odyssey.plugin.config.ConfigKeys;
import net.whimxiqal.odyssey.plugin.config.ConfigManager;
import net.whimxiqal.odyssey.plugin.data.DataStore;
import net.whimxiqal.odyssey.plugin.data.DataStoreException;
import net.whimxiqal.odyssey.plugin.data.DataStores;
import net.whimxiqal.odyssey.plugin.message.Messages;
import net.whimxiqal.odyssey.plugin.search.SearchGate;
import net.whimxiqal.odyssey.plugin.search.SearchRegistry;
import net.whimxiqal.odyssey.plugin.trip.TripManager;
import net.whimxiqal.odyssey.sponge12.SpongeNavigationServiceImpl;
import net.whimxiqal.odyssey.sponge12.api.OdysseyCoreAPI;
import net.whimxiqal.odyssey.sponge12.plugin.api.OdysseyPluginAPI;
import net.whimxiqal.odyssey.sponge12.plugin.api.TrailNavigatorSettings;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.ConstructPluginEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

/**
 * The Odyssey Sponge plugin entry point (floor API 12).
 *
 * <p>On {@link ConstructPluginEvent} it loads config, opens the data store, and installs the core
 * navigation service ({@link OdysseyCoreAPI}) and the plugin-layer trip service + integration
 * registry ({@code OdysseyPluginAPI}) — Sponge has no service manager, so both are installed into
 * static accessors. Vanilla portals are discovered from teleports; waypoints and the trail
 * navigator are registered like any integration; {@code /navigate} starts guided trips. Full {@code
 * /odyssey} admin subcommands, destination-tree navigation, and metrics arrive in a later slice.
 */
@Plugin("odyssey")
public final class OdysseySpongePlugin {

  private final PluginContainer container;
  private final Logger logger;
  private final Path configDir;

  private Log4jOdysseyLogger odysseyLogger;
  private ConfigManager config;
  private ConfigKeys keys;
  private DataStore dataStore;
  private SpongeNavigationServiceImpl navigationService;
  private Messages messages;
  private SpongeIntegrationRegistry integrationRegistry;
  private TripManager<Entity, SpongeTripAgent, ServerLocation> tripManager;
  private SpongeTripServiceImpl tripService;
  private SearchRegistry<ServerLocation> searchRegistry;
  private SearchGate searchGate;
  private Supplier<SearchSettings> searchSettings;

  @Inject
  OdysseySpongePlugin(PluginContainer container, @ConfigDir(sharedRoot = false) Path configDir) {
    this.container = container;
    this.logger = container.logger();
    this.configDir = configDir;
  }

  /** Loads config, opens the data store, and installs the core navigation services. */
  @Listener
  public void onConstructPlugin(ConstructPluginEvent event) {
    this.odysseyLogger = new Log4jOdysseyLogger(logger);

    Path configFile = configDir.resolve("config.yml");
    this.config = new ConfigManager(configFile, "config.yml", odysseyLogger);
    this.keys = new ConfigKeys(config);
    config.load();
    odysseyLogger.setLevel(config.get(keys.loggingLevel));

    Path databaseFile = configDir.resolve(config.get(keys.dataFile));
    this.dataStore = DataStores.create(config.get(keys.dataBackend), databaseFile, odysseyLogger);
    try {
      dataStore.init();
    } catch (DataStoreException e) {
      logger.error("Failed to open Odyssey's data store; navigation is disabled.", e);
      return;
    }

    this.navigationService = new SpongeNavigationServiceImpl(container, odysseyLogger);
    OdysseyCoreAPI.install(navigationService, navigationService);

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
                odysseyLogger,
                () -> config.get(keys.portalsCostSeconds),
                () -> config.get(keys.portalsDiscovery)));

    // The plugin-layer: the trip service (search-then-guide) and the integration registry,
    // published
    // through OdysseyPluginAPI. Waypoints and the trail navigator are registered like any
    // integration.
    Locale defaultLocale = Locale.forLanguageTag(config.get(keys.localeDefault));
    this.messages = new Messages(defaultLocale, config.get(keys.messagesShowPrefix), odysseyLogger);
    this.tripManager =
        new TripManager<>(navigationService.scheduler(), config.get(keys.tripsMaxActivePerPlayer));
    this.integrationRegistry = new SpongeIntegrationRegistry();
    integrationRegistry.registerNavigator(
        container,
        TrailNavigatorSettings.NAVIGATOR_ID,
        new SpongeTrailNavigatorFactory(config, keys, messages));
    integrationRegistry.registerDestinations(
        container, new OdysseyDestinationService(dataStore.waypoints()));

    this.searchRegistry = new SearchRegistry<>();
    this.searchGate = new SearchGate(config.get(keys.searchMaxConcurrentPerPlayer));
    long liveIntervalMillis = config.get(keys.tripsLiveIntervalTicks) * 50L; // 50 ms per tick
    this.searchSettings =
        () ->
            SearchSettings.builder()
                .maxCellsVisited(config.get(keys.algorithmMaxCellsVisited))
                .maxWallClockMillis(config.get(keys.algorithmMaxWallClockSeconds) * 1000L)
                .tier1RecalcThreshold(config.get(keys.algorithmTier1RecalcThreshold))
                .runningAverageWidth(config.get(keys.algorithmRunningAverageWidth))
                .heuristicWeight(config.get(keys.algorithmHeuristicWeight))
                .build();
    this.tripService =
        new SpongeTripServiceImpl(
            navigationService,
            integrationRegistry,
            tripManager,
            searchRegistry,
            searchGate,
            searchSettings,
            liveIntervalMillis);
    OdysseyPluginAPI.install(integrationRegistry, tripService);

    logger.info("Odyssey enabled.");
  }

  /** Registers the {@code /odyssey} and {@code /navigate} commands. */
  @Listener
  public void onRegisterCommands(RegisterCommandEvent<Command.Parameterized> event) {
    Command.Parameterized reload =
        Command.builder()
            .shortDescription(Component.text("Reload Odyssey's configuration"))
            .permission("odyssey.admin.reload")
            .executor(
                context -> {
                  config.load();
                  odysseyLogger.setLevel(config.get(keys.loggingLevel));
                  context.sendMessage(Component.text("Odyssey configuration reloaded."));
                  return CommandResult.success();
                })
            .build();
    Command.Parameterized odyssey =
        Command.builder()
            .shortDescription(Component.text("Odyssey navigation"))
            .addChild(reload, "reload")
            .executor(
                context -> {
                  context.sendMessage(
                      Component.text(
                          "Odyssey — navigation for MinecraftOnline."
                              + " Use /navigate <x> <y> <z> | <destination…>."));
                  return CommandResult.success();
                })
            .build();
    event.register(container, odyssey, "odyssey", "ody");
    if (tripService != null) {
      event.register(
          container,
          NavigateCommand.build(
              navigationService,
              tripService,
              integrationRegistry,
              searchRegistry,
              searchGate,
              searchSettings),
          "navigate",
          "nav");
    }
  }

  /** Stops a departing player's trips and in-flight searches. */
  @Listener
  public void onDisconnect(ServerSideConnectionEvent.Disconnect event) {
    if (tripManager == null) {
      return;
    }
    event
        .profile()
        .map(GameProfile::uuid)
        .ifPresent(uuid -> LogoutCleanup.onLogout(uuid, tripManager, searchRegistry));
  }

  /** Tears down: stop the search workers, withdraw the core services, close the data store. */
  @Listener
  public void onStoppingEngine(StoppingEngineEvent<Server> event) {
    if (tripManager != null) {
      tripManager.stopEverything();
    }
    if (navigationService != null) {
      navigationService.shutdown();
    }
    OdysseyPluginAPI.uninstall();
    OdysseyCoreAPI.uninstall();
    if (dataStore != null) {
      dataStore.close();
    }
    logger.info("Odyssey disabled.");
  }
}
