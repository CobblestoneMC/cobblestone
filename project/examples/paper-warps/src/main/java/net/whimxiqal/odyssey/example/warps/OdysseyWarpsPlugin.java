/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.example.warps;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.File;
import net.whimxiqal.odyssey.paper.api.OdysseySearchModifier;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Example Odyssey integration entry point. It exposes two travel modalities — {@code /warp} command
 * warps and auto-teleport portal pads — and registers an {@link OdysseySearchModifier} so Odyssey
 * routes players through both, prompting the command for warps and walking them into pads for portals.
 *
 * <p>The whole hook into navigation is one service registration; Odyssey handles discovery,
 * pathfinding, and rendering. The listeners and commands here are ordinary Bukkit plumbing.
 */
public final class OdysseyWarpsPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    File warpsFile = new File(getDataFolder(), "warps.yml");
    WarpStore store = new WarpStore(warpsFile, getLogger());
    store.load();
    Selections selections = new Selections();

    // The single hook into navigation: register a search modifier as a Bukkit service.
    getServer().getServicesManager().register(
        OdysseySearchModifier.class, new WarpTransitionProvider(store), this, ServicePriority.Normal);

    // The wand (portal box selection) and the auto-teleport-on-entry behaviour.
    getServer().getPluginManager().registerEvents(new WarpListeners(this, store, selections), this);

    // Commands via Paper's Brigadier lifecycle.
    getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
      event.registrar().register(WarpCommands.warp(store), "Teleport to a named warp");
      event.registrar().register(
          WarpCommands.admin(store, selections), "Manage Odyssey destinations, warps, and portals");
    });

    getLogger().info("OdysseyWarps enabled.");
  }

  @Override
  public void onDisable() {
    getServer().getServicesManager().unregisterAll(this);
  }
}
