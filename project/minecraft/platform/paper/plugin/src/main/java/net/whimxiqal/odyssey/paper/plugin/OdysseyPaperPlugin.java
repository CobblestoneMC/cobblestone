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
import net.whimxiqal.odyssey.OdysseyLogger;
import net.whimxiqal.odyssey.paper.PaperOdysseyApiImpl;
import net.whimxiqal.odyssey.paper.api.PaperOdysseyApi;
import net.whimxiqal.odyssey.plugin.config.ConfigKeys;
import net.whimxiqal.odyssey.plugin.config.ConfigManager;
import net.whimxiqal.odyssey.plugin.message.Messages;
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

  @Override
  public void onEnable() {
    OdysseyLogger log = new JulOdysseyLogger(getLogger());

    Path configFile = getDataFolder().toPath().resolve("config.yml");
    ConfigManager config = new ConfigManager(configFile, "config.yml", log);
    ConfigKeys keys = new ConfigKeys(config);
    config.load();

    Locale defaultLocale = Locale.forLanguageTag(config.get(keys.localeDefault));
    Messages messages = new Messages(defaultLocale, config.get(keys.messagesShowPrefix), log);

    // The transition registry is owned by the plugin; the platform API only reads from / registers
    // into it (design/05). Both are reachable to other plugins via the registered plugin API.
    this.platformApi = new PaperOdysseyApiImpl(this);
    PaperOdysseyApi pluginApi = new PaperOdysseyApiImpl(this);
    getServer().getServicesManager()
        .register(PaperOdysseyApi.class, pluginApi, this, ServicePriority.Normal);

    getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
        event.registrar().register(
            OdysseyCommand.build(config, keys, messages),
            "Odyssey admin and utility commands",
            List.of("ody")));

    getLogger().info("Odyssey enabled.");
  }

  @Override
  public void onDisable() {
    if (platformApi != null) {
      // Cancels in-flight searches and stops the search worker pool. Trips/data-store shutdown join
      // this in Phases 6b/6c.
      platformApi.shutdown();
    }
    getServer().getServicesManager().unregisterAll(this);
    getLogger().info("Odyssey disabled.");
  }
}
