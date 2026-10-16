/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.hologram;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;

/**
 * Floating lines of text seen by one player.
 *
 * <p>Each line is a {@link TextDisplay} that is never saved and only sent to its player. It turns
 * to face them, is lit like a name tag whatever the light around it, and is hidden behind blocks
 * like the world around it. When it moves, it glides there over {@link #GLIDE_TICKS}. It has no
 * hitbox, so nothing can hit or use it; what a player means to hit is worked out from where they
 * look.
 *
 * <p>Folia: a label is spawned and changed only from the thread owning its region, and its removal
 * from any other thread is handed to its entity's own scheduler.
 */
public final class PaperHolograms {

  /**
   * How far above a text display its text is centered. The client draws a line upward from the
   * entity: 9 pixels of text and a pixel of background on either side, at 0.025 blocks each.
   */
  private static final double TEXT_CENTER = (9 + 2) * 0.025 / 2;

  private static final int FULL_BRIGHTNESS = 15;

  /** How long a label takes to glide to where it is moved: as often as labels are updated. */
  private static final int GLIDE_TICKS = 2;

  private final Plugin plugin;
  private final Map<UUID, Label> byEntity = new ConcurrentHashMap<>();

  /**
   * Creates the label spawner.
   *
   * @param plugin the plugin owning the labels
   */
  public PaperHolograms(Plugin plugin) {
    this.plugin = plugin;
  }

  /**
   * Places a line of text for {@code viewer}.
   *
   * @param viewer the only player who sees it
   * @param at the center of its text
   * @param text the text
   * @return the label, or empty if {@code at} is not owned by the current thread
   */
  public Optional<PaperHologram> spawn(Player viewer, Location at, Component text) {
    if (!Bukkit.isOwnedByCurrentRegion(at)) {
      return Optional.empty();
    }
    TextDisplay display =
        at.getWorld()
            .spawn(
                entityAt(at),
                TextDisplay.class,
                entity -> {
                  entity.setVisibleByDefault(false);
                  entity.setPersistent(false);
                  entity.text(text);
                  entity.setBillboard(Display.Billboard.CENTER);
                  entity.setSeeThrough(false);
                  entity.setBrightness(new Display.Brightness(FULL_BRIGHTNESS, FULL_BRIGHTNESS));
                  entity.setTeleportDuration(GLIDE_TICKS);
                });
    viewer.showEntity(plugin, display);
    Label label = new Label(display);
    byEntity.put(display.getUniqueId(), label);
    return Optional.of(label);
  }

  private static Location entityAt(Location at) {
    return at.clone().subtract(0, TEXT_CENTER, 0);
  }

  /** Removes every label, e.g. on shutdown. */
  public void removeAll() {
    Map.copyOf(byEntity).values().forEach(Label::remove);
  }

  private final class Label implements PaperHologram {

    private final TextDisplay display;
    private volatile boolean removed;

    Label(TextDisplay display) {
      this.display = display;
    }

    @Override
    public void text(Component text) {
      if (!removed && Bukkit.isOwnedByCurrentRegion(display)) {
        display.text(text);
      }
    }

    @Override
    public void move(Location to) {
      if (!removed) {
        display.teleportAsync(entityAt(to));
      }
    }

    @Override
    public void remove() {
      if (removed) {
        return;
      }
      removed = true;
      byEntity.remove(display.getUniqueId());
      if (Bukkit.isOwnedByCurrentRegion(display)) {
        display.remove();
      } else if (plugin.isEnabled()) {
        display.getScheduler().run(plugin, task -> display.remove(), null);
      } // else shutting down: it was never going to be saved, so it goes with its chunk
    }
  }
}
