/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.plugin.Permissions;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.ClientEmoteEvent;

/**
 * Opens the navigation menu for a Bedrock player who performs one of the configured emotes, as
 * though they had typed {@code /navigate} on its own: typing a command is a chore on a phone or a
 * console, and an emote is a button press. It needs the same permission as the command.
 *
 * <p>Only loaded when Geyser runs on this server; Geyser on a proxy cannot report emotes.
 */
final class PaperBedrockEmotes implements EventRegistrar {

  private final Plugin plugin;
  private final Supplier<List<String>> emotes;
  private final Consumer<Player> open;

  /**
   * Starts listening for emotes.
   *
   * @param plugin Cobblestone
   * @param emotes reads the ids of the emotes that open the menu
   * @param open opens the menu for a player, on their own thread
   */
  PaperBedrockEmotes(Plugin plugin, Supplier<List<String>> emotes, Consumer<Player> open) {
    this.plugin = plugin;
    this.emotes = emotes;
    this.open = open;
    GeyserApi.api().eventBus().subscribe(this, ClientEmoteEvent.class, this::onEmote);
  }

  /** Stops listening. */
  void close() {
    GeyserApi.api().eventBus().unregisterAll(this);
  }

  /** Called on Geyser's own thread, so the menu waits for the player's. */
  private void onEmote(ClientEmoteEvent event) {
    String emote = event.emoteId();
    if (emotes.get().stream().noneMatch(id -> id.trim().equalsIgnoreCase(emote))) {
      return;
    }
    Player player = plugin.getServer().getPlayer(event.connection().javaUuid());
    if (player != null) {
      player
          .getScheduler()
          .run(
              plugin,
              task -> {
                if (player.hasPermission(Permissions.NAVIGATE.value())) {
                  open.accept(player);
                }
              },
              null);
    }
  }
}
