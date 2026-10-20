/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.cobblestonemc.CobblestoneLogger;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.ClientEmoteEvent;

/**
 * Opens the navigation menu when a Bedrock player emotes, on a server with Geyser installed —
 * typing a command is a chore on a phone or a console, and an emote is a button press.
 *
 * <p>Geyser is optional, so nothing here touches its classes until {@link #listen} has checked it
 * is present; on a server without it (or where it runs on a proxy rather than here), this does
 * nothing at all.
 */
public final class BedrockEmotes {

  private static final String GEYSER_API = "org.geysermc.geyser.api.GeyserApi";

  private BedrockEmotes() {}

  /**
   * Starts opening the menu for each Bedrock player who performs one of the configured emotes.
   *
   * @param owner the plugin, which Geyser files the listener under
   * @param emotes the ids of the emotes that open the menu, read on each emote so a reload applies
   * @param open opens the menu for a player, by their Java unique id; called on Geyser's network
   *     thread, so it must hop to the player's own thread itself
   * @param log for saying whether this is on
   * @return stops listening; does nothing if Geyser is not here
   */
  public static Runnable listen(
      Object owner, Supplier<List<String>> emotes, Consumer<UUID> open, CobblestoneLogger log) {
    if (!geyserPresent()) {
      return () -> {};
    }
    try {
      return Hook.listen(owner, emotes, open, log);
    } catch (RuntimeException | LinkageError e) {
      // Geyser is here but its API is not ready, or not the one we compiled against.
      log.warn("Could not listen for Bedrock emotes through Geyser: {}", e.toString());
      return () -> {};
    }
  }

  private static boolean geyserPresent() {
    try {
      Class.forName(GEYSER_API, false, BedrockEmotes.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** Everything that names a Geyser class, loaded only once Geyser is known to be here. */
  private static final class Hook {

    static Runnable listen(
        Object owner, Supplier<List<String>> emotes, Consumer<UUID> open, CobblestoneLogger log) {
      EventRegistrar registrar = EventRegistrar.of(owner);
      GeyserApi.api()
          .eventBus()
          .subscribe(
              registrar,
              ClientEmoteEvent.class,
              event -> {
                String emote = event.emoteId().toLowerCase(Locale.ROOT);
                log.debug(
                    "{} performed Bedrock emote {}", event.connection().bedrockUsername(), emote);
                if (emotes.get().stream().anyMatch(id -> id.trim().equalsIgnoreCase(emote))) {
                  open.accept(event.connection().javaUuid());
                }
              });
      log.info("Bedrock players can open the navigation menu by emoting.");
      return () -> GeyserApi.api().eventBus().unregisterAll(registrar);
    }
  }
}
