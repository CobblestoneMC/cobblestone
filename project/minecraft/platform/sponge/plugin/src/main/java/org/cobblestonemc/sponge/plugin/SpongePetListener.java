/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.Optional;
import org.spongepowered.api.data.DataRegistration;
import org.spongepowered.api.data.Key;
import org.spongepowered.api.data.value.Value;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.entity.TameEntityEvent;
import org.spongepowered.api.event.lifecycle.RegisterDataEvent;
import org.spongepowered.plugin.PluginContainer;

/**
 * Stamps each newly tamed animal with when it was tamed, on the entity itself, so that pets sharing
 * a name are numbered oldest first in {@code /navigate pet} ({@code Rex}, {@code Rex-2}, …). The
 * game does not remember an entity's age across chunk loads, so this is the only stable "how old"
 * there is.
 *
 * <p>The stamp is a custom data key persisted with the entity; it is registered through {@link
 * #onRegisterData}, which the plugin must forward its {@link RegisterDataEvent} to.
 */
final class SpongePetListener {

  private final Key<Value<Long>> tamedAt;

  SpongePetListener(PluginContainer container) {
    this.tamedAt = Key.from(container, "tamed_at", Long.class);
  }

  /** Registers the tamed-at key so it is saved with entities. */
  void onRegisterData(RegisterDataEvent event) {
    event.register(DataRegistration.of(tamedAt, Entity.class));
  }

  /**
   * When an entity was tamed (epoch millis), if it was tamed since Cobblestone was installed.
   *
   * @param entity the entity
   * @return the time, or empty
   */
  Optional<Long> tamedAt(Entity entity) {
    return entity.get(tamedAt);
  }

  /** Stamps a newly tamed animal. */
  @Listener(order = Order.POST)
  public void onTame(TameEntityEvent event) {
    event.entity().offer(tamedAt, System.currentTimeMillis());
  }
}
