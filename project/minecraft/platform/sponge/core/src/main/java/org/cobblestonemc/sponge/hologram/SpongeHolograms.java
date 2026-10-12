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
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.data.persistence.DataContainer;
import org.spongepowered.api.data.persistence.DataQuery;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.EntityArchetype;
import org.spongepowered.api.entity.EntityTypes;
import org.spongepowered.api.entity.living.ArmorStand;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.entity.AttackEntityEvent;
import org.spongepowered.api.event.entity.InteractEntityEvent;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.world.chunk.ChunkEvent;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3d;

/**
 * Floating text labels for one player, which run a callback when that player hits them. Register an
 * instance as a listener so hits are seen and the entities can't be hit or used otherwise.
 *
 * <p>Each label is two entities: an invisible marker armor stand, whose name is the text, and an
 * {@code Interaction} sized to the text, which is what gets hit (a marker armor stand has no
 * hitbox). SpongeAPI can't show an entity to one player only, so <b>every player nearby sees every
 * label</b>; only its own player's hit does anything. Attacks on and right-clicks of either entity
 * are cancelled.
 *
 * <p>SpongeAPI can't keep an entity from being saved either, so both carry the scoreboard tag
 * {@value #TAG} and any that were saved (e.g. by a crash) are removed when their chunk loads.
 *
 * <p>SpongeAPI has no data keys for an Interaction's size, so it is spawned from an archetype with
 * the vanilla {@code width} and {@code height} entity data; changing the text respawns it.
 */
public final class SpongeHolograms {

  static final String TAG = "cobblestone_label";

  /**
   * How far above an armor stand its name's text is centered. The client draws a name 0.5 above the
   * entity's height (0 for a marker) downward, one line of 9 pixels at 0.025 blocks each.
   */
  private static final double NAME_CENTER = 0.5 - 9 * 0.025 / 2;

  private static final float HITBOX_HEIGHT = 0.28f;
  private static final double BLOCKS_PER_CHARACTER = 0.15;
  private static final double MIN_HITBOX_WIDTH = 0.4;
  private static final double MAX_HITBOX_WIDTH = 3.0;

  private final Map<UUID, Label> byEntity = new ConcurrentHashMap<>();

  /**
   * Places a label for {@code owner}.
   *
   * @param owner the player whose hits count
   * @param world the world to place it in
   * @param at the center of its text
   * @param text the text
   * @param onHit run when the owner hits it
   * @return the label, or empty if it couldn't be spawned
   */
  public Optional<SpongeHologram> spawn(
      ServerPlayer owner, ServerWorld world, Vector3d at, Component text, Runnable onHit) {
    ArmorStand stand = world.createEntity(EntityTypes.ARMOR_STAND, nameAt(at));
    stand.offer(Keys.HAS_MARKER, true);
    stand.offer(Keys.IS_INVISIBLE, true);
    stand.offer(Keys.IS_GRAVITY_AFFECTED, false);
    stand.offer(Keys.INVULNERABLE, true);
    stand.offer(Keys.IS_SILENT, true);
    stand.offer(Keys.CUSTOM_NAME, text);
    stand.offer(Keys.IS_CUSTOM_NAME_VISIBLE, true);
    stand.offer(Keys.SCOREBOARD_TAGS, Set.of(TAG));
    if (!world.spawnEntity(stand)) {
      return Optional.empty();
    }
    Optional<Entity> hitbox = spawnHitbox(world, at, text);
    if (hitbox.isEmpty()) {
      stand.remove();
      return Optional.empty();
    }
    Label label = new Label(owner.uniqueId(), world, stand, hitbox.get(), at, onHit);
    byEntity.put(stand.uniqueId(), label);
    byEntity.put(hitbox.get().uniqueId(), label);
    return Optional.of(label);
  }

  /** Removes every label, e.g. on shutdown. */
  public void removeAll() {
    Map.copyOf(byEntity).values().forEach(Label::remove);
  }

  @Listener(order = Order.FIRST)
  public void onAttack(InteractEntityEvent.Primary event, @First ServerPlayer player) {
    Label label = byEntity.get(event.entity().uniqueId());
    if (label == null) {
      return;
    }
    event.setCancelled(true);
    if (label.owner.equals(player.uniqueId())) {
      label.onHit.run();
    }
  }

  @Listener(order = Order.FIRST)
  public void onDamage(AttackEntityEvent event) {
    if (byEntity.containsKey(event.entity().uniqueId())) {
      event.setCancelled(true);
    }
  }

  @Listener(order = Order.FIRST)
  public void onInteract(InteractEntityEvent.Secondary event) {
    if (byEntity.containsKey(event.entity().uniqueId())) {
      event.setCancelled(true);
    }
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

  private Optional<Entity> spawnHitbox(ServerWorld world, Vector3d at, Component text) {
    DataContainer data =
        DataContainer.createNew()
            .set(DataQuery.of("width"), width(text))
            .set(DataQuery.of("height"), HITBOX_HEIGHT)
            .set(DataQuery.of("response"), false)
            .set(DataQuery.of("Tags"), List.of(TAG));
    Optional<Entity> hitbox =
        EntityArchetype.builder()
            .type(EntityTypes.INTERACTION.get())
            .entityData(data)
            .build()
            .apply(world.location(hitboxAt(at)));
    hitbox.ifPresent(entity -> entity.offer(Keys.SCOREBOARD_TAGS, Set.of(TAG)));
    return hitbox;
  }

  private static Vector3d nameAt(Vector3d at) {
    return new Vector3d(at.x(), at.y() - NAME_CENTER, at.z());
  }

  private static Vector3d hitboxAt(Vector3d at) {
    return new Vector3d(at.x(), at.y() - HITBOX_HEIGHT / 2.0, at.z());
  }

  /** A hitbox about as wide as the text is drawn. */
  private static float width(Component text) {
    int characters = PlainTextComponentSerializer.plainText().serialize(text).length();
    return (float)
        Math.clamp(characters * BLOCKS_PER_CHARACTER, MIN_HITBOX_WIDTH, MAX_HITBOX_WIDTH);
  }

  private final class Label implements SpongeHologram {

    private final UUID owner;
    private final ServerWorld world;
    private final ArmorStand stand;
    private final Runnable onHit;
    private Entity hitbox;
    private Vector3d at;
    private boolean removed;

    Label(
        UUID owner,
        ServerWorld world,
        ArmorStand stand,
        Entity hitbox,
        Vector3d at,
        Runnable onHit) {
      this.owner = owner;
      this.world = world;
      this.stand = stand;
      this.hitbox = hitbox;
      this.at = at;
      this.onHit = onHit;
    }

    @Override
    public boolean move(Vector3d at) {
      if (removed || stand.isRemoved() || hitbox.isRemoved()) {
        return false;
      }
      this.at = at;
      return stand.setPosition(nameAt(at)) && hitbox.setPosition(hitboxAt(at));
    }

    @Override
    public void text(Component text) {
      if (removed) {
        return;
      }
      stand.offer(Keys.CUSTOM_NAME, text);
      // The hitbox can't be resized in place, so a fresh one replaces it.
      Optional<Entity> resized = spawnHitbox(world, at, text);
      if (resized.isPresent()) {
        byEntity.remove(hitbox.uniqueId());
        hitbox.remove();
        hitbox = resized.get();
        byEntity.put(hitbox.uniqueId(), this);
      }
    }

    @Override
    public void remove() {
      if (removed) {
        return;
      }
      removed = true;
      byEntity.remove(stand.uniqueId());
      byEntity.remove(hitbox.uniqueId());
      stand.remove();
      hitbox.remove();
    }
  }
}
