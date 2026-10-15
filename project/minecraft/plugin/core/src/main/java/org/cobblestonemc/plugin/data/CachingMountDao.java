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
 * A {@link MountDao} that serves reads from a short-lived per-player cache, so building the {@code
 * cobblestone mount} destination during tab-completion does not hit the database on every
 * keystroke. A dismount invalidates that player's entry. A move or removal is keyed by the mount,
 * not the player, and may touch several players' rows, so it drops the whole cache — they are rare
 * (a tamed mount's chunk unloading, or the mount dying), and the cache refills within a second.
 */
final class CachingMountDao implements MountDao {

  private final MountDao delegate;
  private final ExpiringCache<UUID, Optional<MountLocation>> byPlayer = new ExpiringCache<>();

  CachingMountDao(MountDao delegate) {
    this.delegate = delegate;
  }

  @Override
  public void upsert(MountLocation location) {
    delegate.upsert(location);
    byPlayer.invalidate(location.player());
  }

  @Override
  public Optional<MountLocation> get(UUID player) {
    return byPlayer.get(player, delegate::get);
  }

  @Override
  public void move(UUID mount, String world, int x, int y, int z) {
    delegate.move(mount, world, x, y, z);
    byPlayer.invalidateAll();
  }

  @Override
  public void remove(UUID mount) {
    delegate.remove(mount);
    byPlayer.invalidateAll();
  }
}
