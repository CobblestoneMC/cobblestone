/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

/**
 * Abstract persistence for Cobblestone's plugin state, backed by a pluggable store the admin
 * selects in config. The abstraction lives in the plugin layer because persistence is only relevant
 * when Cobblestone runs as a plugin — the core navigation library is standalone. (design/06)
 *
 * <p>Only the DAOs whose subsystems have landed are exposed here; rail/highway segment and
 * player-preference DAOs join {@link #locations()} and {@link #portalTransitions()} as those
 * features arrive in later sub-phases. A store is single-use: {@link #init()} once at enable,
 * {@link #close()} once at disable.
 */
public interface DataStore {

  /**
   * Opens the store and creates or migrates its schema. Call exactly once, before any DAO use.
   *
   * @throws DataStoreException if the store cannot be opened or migrated
   */
  void init();

  /**
   * Returns the location DAO.
   *
   * @return the location DAO
   */
  LocationDao locations();

  /**
   * Returns the portal-transition DAO (end portals: region → point).
   *
   * @return the portal-transition DAO
   */
  PortalTransitionDao portalTransitions();

  /**
   * Returns the end-return portal DAO (End exit portals; destination resolved per-player).
   *
   * @return the end-return portal DAO
   */
  EndReturnPortalDao endReturnPortals();

  /**
   * Returns the end-gateway DAO.
   *
   * @return the end-gateway DAO
   */
  GatewayDao gateways();

  /**
   * Closes the store and releases its resources. Idempotent; safe to call even if {@link #init()}
   * failed.
   */
  void close();
}
