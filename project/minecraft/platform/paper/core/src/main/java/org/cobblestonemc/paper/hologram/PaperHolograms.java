/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.hologram;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/**
 * Floating text labels seen by one player, which run a callback when that player hits them.
 * Register an instance as a listener so hits are seen and the entities can't be hit or used
 * otherwise.
 *
 * <p>Each label is two entities that are never saved and only sent to its player: an invisible
 * marker armor stand, whose name is the text, and an {@link Interaction} sized to the text, which
 * is what the player actually hits (a marker armor stand has no hitbox, and a regular one's hitbox
 * sits below its name and would overlap a label stacked above it). Attacks on and right-clicks of
 * either are cancelled.
 *
 * <p>Folia: a label is spawned and changed only from the thread owning its region, and its removal
 * from any other thread is handed to its entities' own schedulers.
 */
public final class PaperHolograms implements Listener {

  /**
   * How far above an armor stand its name's text is centered. The client draws a name 0.5 above the
   * entity's height (0 for a marker) downward, one line of 9 pixels at 0.025 blocks each.
   */
  private static final double NAME_CENTER = 0.5 - 9 * 0.025 / 2;

  private static final float HITBOX_HEIGHT = 0.28f;
  private static final double BLOCKS_PER_CHARACTER = 0.15;
  private static final float MIN_HITBOX_WIDTH = 0.4f;
  private static final float MAX_HITBOX_WIDTH = 3.0f;

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
   * Places a label for {@code viewer}.
   *
   * @param viewer the only player who sees it
   * @param at the center of its text
   * @param text the text
   * @param onHit run on the viewer's thread when the viewer hits it
   * @return the label, or empty if {@code at} is not owned by the current thread
   */
  public Optional<PaperHologram> spawn(Player viewer, Location at, Component text, Runnable onHit) {
    if (!Bukkit.isOwnedByCurrentRegion(at)) {
      return Optional.empty();
    }
    ArmorStand stand =
        at.getWorld()
            .spawn(
                nameAt(at),
                ArmorStand.class,
                entity -> {
                  hide(entity);
                  entity.setMarker(true);
                  entity.setInvisible(true);
                  entity.setGravity(false);
                  entity.setCanTick(false);
                  entity.addDisabledSlots(EquipmentSlot.values());
                  entity.customName(text);
                  entity.setCustomNameVisible(true);
                });
    Interaction hitbox =
        at.getWorld()
            .spawn(
                hitboxAt(at),
                Interaction.class,
                entity -> {
                  hide(entity);
                  entity.setResponsive(false);
                  entity.setInteractionHeight(HITBOX_HEIGHT);
                  entity.setInteractionWidth(width(text));
                });
    viewer.showEntity(plugin, stand);
    viewer.showEntity(plugin, hitbox);
    Label label = new Label(viewer.getUniqueId(), stand, hitbox, onHit);
    byEntity.put(stand.getUniqueId(), label);
    byEntity.put(hitbox.getUniqueId(), label);
    return Optional.of(label);
  }

  /** Removes every label, e.g. on shutdown. */
  public void removeAll() {
    Map.copyOf(byEntity).values().forEach(Label::remove);
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onAttack(PrePlayerAttackEntityEvent event) {
    Label label = byEntity.get(event.getAttacked().getUniqueId());
    if (label == null) {
      return;
    }
    event.setCancelled(true);
    if (label.viewer().equals(event.getPlayer().getUniqueId())) {
      label.onHit().run();
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onInteract(PlayerInteractEntityEvent event) {
    if (byEntity.containsKey(event.getRightClicked().getUniqueId())) {
      event.setCancelled(true);
    }
  }

  private void hide(Entity entity) {
    entity.setVisibleByDefault(false);
    entity.setPersistent(false);
    entity.setInvulnerable(true);
    entity.setSilent(true);
  }

  private static Location nameAt(Location at) {
    return at.clone().subtract(0, NAME_CENTER, 0);
  }

  private static Location hitboxAt(Location at) {
    return at.clone().subtract(0, HITBOX_HEIGHT / 2.0, 0);
  }

  /** A hitbox about as wide as the text is drawn. */
  private static float width(Component text) {
    int characters = PlainTextComponentSerializer.plainText().serialize(text).length();
    return (float)
        Math.clamp(characters * BLOCKS_PER_CHARACTER, MIN_HITBOX_WIDTH, MAX_HITBOX_WIDTH);
  }

  private final class Label implements PaperHologram {

    private final UUID viewer;
    private final ArmorStand stand;
    private final Interaction hitbox;
    private final Runnable onHit;
    private volatile boolean removed;

    Label(UUID viewer, ArmorStand stand, Interaction hitbox, Runnable onHit) {
      this.viewer = viewer;
      this.stand = stand;
      this.hitbox = hitbox;
      this.onHit = onHit;
    }

    UUID viewer() {
      return viewer;
    }

    Runnable onHit() {
      return onHit;
    }

    @Override
    public void text(Component text) {
      if (!removed && Bukkit.isOwnedByCurrentRegion(stand)) {
        stand.customName(text);
        hitbox.setInteractionWidth(width(text));
      }
    }

    @Override
    public void remove() {
      if (removed) {
        return;
      }
      removed = true;
      byEntity.remove(stand.getUniqueId());
      byEntity.remove(hitbox.getUniqueId());
      for (Entity entity : new Entity[] {stand, hitbox}) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
          entity.remove();
        } else if (plugin.isEnabled()) {
          entity.getScheduler().run(plugin, task -> entity.remove(), null);
        } // else shutting down: it was never going to be saved, so it goes with its chunk
      }
    }
  }
}
