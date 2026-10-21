/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.hologram;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.EntityTypes;
import org.spongepowered.api.entity.display.BillboardTypes;
import org.spongepowered.api.entity.display.TextDisplay;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.world.chunk.ChunkEvent;
import org.spongepowered.api.util.Ticks;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3d;

/**
 * Floating lines of text. Register an instance as a listener so labels left over from a previous
 * run are cleaned up.
 *
 * <p>Each line is a {@link TextDisplay}, tagged {@value #TAG}. It turns to face whoever looks at
 * it, is lit like a name tag whatever the light around it, and is hidden behind blocks like the
 * world around it, and glides to where it is moved over {@link #GLIDE_TICKS}. It has no hitbox, so
 * nothing can hit or use it; what a player means to hit is worked out from where they look.
 * SpongeAPI can't show an entity to one player only, so everyone nearby sees it.
 */
public final class SpongeHolograms {

  static final String TAG = "cobblestone_label";

  /**
   * How far above a text display its text is centered. The client draws a line upward from the
   * entity: 9 pixels of text and a pixel of background on either side, at 0.025 blocks each.
   */
  private static final double TEXT_CENTER = (9 + 2) * 0.025 / 2;

  private static final int FULL_BRIGHTNESS = 15;

  /** How long a label takes to glide to where it is moved: as often as labels are updated. */
  private static final int GLIDE_TICKS = 2;

  private final Map<UUID, Label> byEntity = new ConcurrentHashMap<>();

  /**
   * Places a line of text.
   *
   * @param world the world to place it in
   * @param at the center of its text
   * @param text the text
   * @return the label, or empty if it couldn't be spawned
   */
  public Optional<SpongeHologram> spawn(ServerWorld world, Vector3d at, Component text) {
    TextDisplay display = world.createEntity(EntityTypes.TEXT_DISPLAY, entityAt(at));
    display.offer(Keys.DISPLAY_NAME, text);
    display.offer(Keys.BILLBOARD_TYPE, BillboardTypes.CENTER.get());
    display.offer(Keys.SEE_THROUGH_BLOCKS, false);
    display.offer(Keys.BLOCK_LIGHT, FULL_BRIGHTNESS);
    display.offer(Keys.SKY_LIGHT, FULL_BRIGHTNESS);
    display.offer(Keys.TELEPORT_DURATION, Ticks.of(GLIDE_TICKS));
    display.offer(Keys.SCOREBOARD_TAGS, Set.of(TAG));
    if (!world.spawnEntity(display)) {
      return Optional.empty();
    }
    Label label = new Label(display);
    byEntity.put(display.uniqueId(), label);
    return Optional.of(label);
  }

  private static Vector3d entityAt(Vector3d at) {
    return new Vector3d(at.x(), at.y() - TEXT_CENTER, at.z());
  }

  /** Removes every label, e.g. on shutdown. */
  public void removeAll() {
    Map.copyOf(byEntity).values().forEach(Label::remove);
  }

  /** Removes labels left over from a previous run (e.g. saved by a crash). */
  @Listener
  public void onChunkEntities(ChunkEvent.Entities.Load event) {
    for (Entity entity : List.copyOf(event.chunk().entities())) {
      if (entity.get(Keys.SCOREBOARD_TAGS).map(tags -> tags.contains(TAG)).orElse(false)
          && !byEntity.containsKey(entity.uniqueId())) {
        entity.remove();
      }
    }
  }

  private final class Label implements SpongeHologram {

    private final TextDisplay display;
    private boolean removed;

    Label(TextDisplay display) {
      this.display = display;
    }

    @Override
    public void text(Component text) {
      if (!removed) {
        display.offer(Keys.DISPLAY_NAME, text);
      }
    }

    @Override
    public void move(Vector3d to) {
      if (!removed) {
        display.setPosition(entityAt(to));
      }
    }

    @Override
    public void remove() {
      if (removed) {
        return;
      }
      removed = true;
      byEntity.remove(display.uniqueId());
      display.remove();
    }
  }
}
