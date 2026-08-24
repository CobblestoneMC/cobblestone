/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link LocationDao} that serves reads from a short-lived per-scope cache, so that
 * tab-completing {@code /navigate} does not hit the database on every keystroke for every player
 * online.
 *
 * <p>One entry per scope — a player's own locations, or the global ones — holding that scope's
 * whole row set, which is also what {@link #get} is answered from. Every write goes straight to the
 * delegate and then invalidates the scope it touched, so a player never sees their own change
 * missing; the {@linkplain ExpiringCache#TTL_NANOS one-second} expiry only bounds how long a change
 * made outside this process (another server on a shared database) can go unnoticed.
 */
final class CachingLocationDao implements LocationDao {

  private final LocationDao delegate;
  private final ExpiringCache<Optional<UUID>, List<Location>> byScope = new ExpiringCache<>();

  CachingLocationDao(LocationDao delegate) {
    this.delegate = delegate;
  }

  @Override
  public void put(Location location) {
    delegate.put(location);
    byScope.invalidate(location.owner());
  }

  @Override
  public boolean remove(Optional<UUID> owner, String name) {
    boolean removed = delegate.remove(owner, name);
    byScope.invalidate(owner);
    return removed;
  }

  /** Answered from the scope's cached rows; names are unique within a scope. */
  @Override
  public Optional<Location> get(Optional<UUID> owner, String name) {
    return scope(owner).stream().filter(location -> location.name().equals(name)).findFirst();
  }

  @Override
  public List<Location> ownedBy(UUID owner) {
    return scope(Optional.of(owner));
  }

  @Override
  public List<Location> global() {
    return scope(Optional.empty());
  }

  private List<Location> scope(Optional<UUID> owner) {
    return byScope.get(owner, key -> key.map(delegate::ownedBy).orElseGet(delegate::global));
  }
}
