/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

/**
 * Wraps a {@link DataStore} so the DAOs behind player-facing tab-completion — locations and death
 * locations — read through a one-second per-player cache ({@link ExpiringCache}). Every backend
 * gets the same buffer this way, and the layers above keep talking to plain DAO interfaces.
 *
 * <p>The portal DAOs are not cached: nothing tab-completes them, and searches read them in bulk,
 * once per search.
 */
final class CachingDataStore implements DataStore {

  private final DataStore delegate;

  private LocationDao locations;
  private DeathLocationDao deaths;

  CachingDataStore(DataStore delegate) {
    this.delegate = delegate;
  }

  @Override
  public void init() {
    delegate.init();
    this.locations = new CachingLocationDao(delegate.locations());
    this.deaths = new CachingDeathLocationDao(delegate.deaths());
  }

  @Override
  public LocationDao locations() {
    if (locations == null) {
      throw new IllegalStateException("DataStore.init() has not been called");
    }
    return locations;
  }

  @Override
  public DeathLocationDao deaths() {
    if (deaths == null) {
      throw new IllegalStateException("DataStore.init() has not been called");
    }
    return deaths;
  }

  @Override
  public PortalTransitionDao portalTransitions() {
    return delegate.portalTransitions();
  }

  @Override
  public EndReturnPortalDao endReturnPortals() {
    return delegate.endReturnPortals();
  }

  @Override
  public GatewayDao gateways() {
    return delegate.gateways();
  }

  @Override
  public void close() {
    delegate.close();
  }
}
