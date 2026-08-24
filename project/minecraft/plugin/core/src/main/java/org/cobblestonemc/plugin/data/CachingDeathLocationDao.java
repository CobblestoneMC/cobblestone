/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import java.util.Optional;
import java.util.UUID;

/**
 * A {@link DeathLocationDao} that serves reads from a short-lived per-player cache, so building the
 * {@code cobblestone death} destination during tab-completion does not hit the database on every
 * keystroke. A death is written through to the delegate and invalidates that player's entry, so the
 * destination points at where they just died and not where they died before.
 */
final class CachingDeathLocationDao implements DeathLocationDao {

  private final DeathLocationDao delegate;
  private final ExpiringCache<UUID, Optional<DeathLocation>> byPlayer = new ExpiringCache<>();

  CachingDeathLocationDao(DeathLocationDao delegate) {
    this.delegate = delegate;
  }

  @Override
  public void upsert(DeathLocation location) {
    delegate.upsert(location);
    byPlayer.invalidate(location.player());
  }

  @Override
  public Optional<DeathLocation> get(UUID player) {
    return byPlayer.get(player, delegate::get);
  }
}
