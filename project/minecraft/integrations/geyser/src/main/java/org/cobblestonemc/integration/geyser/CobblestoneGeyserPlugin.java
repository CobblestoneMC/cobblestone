/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.geyser;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.ClientEmoteEvent;

/**
 * Entry point for the Geyser integration: a Bedrock player who performs one of the configured
 * emotes opens Cobblestone's navigation menu, as though they had typed {@code /navigate} on its
 * own. Typing a command is a chore on a phone or a console, and an emote is a button press.
 *
 * <p>The menu is opened by running the command as the player, so it is subject to the same
 * permission as typing it.
 */
public final class CobblestoneGeyserPlugin extends JavaPlugin implements EventRegistrar {

  /** Namespaced, so another plugin's {@code /navigate} cannot answer in Cobblestone's place. */
  private static final String NAVIGATE = "cobblestone:navigate";

  private Set<String> emotes;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    emotes =
        getConfig().getStringList("emotes").stream()
            .map(id -> id.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    GeyserApi.api().eventBus().subscribe(this, ClientEmoteEvent.class, this::onEmote);
    getLogger().info("CobblestoneGeyser enabled.");
  }

  @Override
  public void onDisable() {
    GeyserApi.api().eventBus().unregisterAll(this);
  }

  /** Called on Geyser's own thread, so the command waits for the player's. */
  private void onEmote(ClientEmoteEvent event) {
    if (!emotes.contains(event.emoteId().toLowerCase(Locale.ROOT))) {
      return;
    }
    Player player = getServer().getPlayer(event.connection().javaUuid());
    if (player != null) {
      player.getScheduler().run(this, task -> player.performCommand(NAVIGATE), null);
    }
  }
}
