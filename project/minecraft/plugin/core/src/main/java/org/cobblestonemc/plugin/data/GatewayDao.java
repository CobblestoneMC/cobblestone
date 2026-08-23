/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import java.util.List;

/**
 * Persistence for learned end-gateway links. Keyed by gateway block; the exit updates on change.
 */
public interface GatewayDao {

  /**
   * Records a gateway link, keyed by its gateway block. If the gateway is already known, its exit
   * is updated (a gateway can be re-targeted); otherwise it is inserted.
   *
   * @param gateway the gateway link
   */
  void upsert(GatewayTransition gateway);

  /**
   * Every recorded gateway link.
   *
   * @return the gateways (never {@code null})
   */
  List<GatewayTransition> all();

  /**
   * Removes every recorded gateway link.
   *
   * @return how many were removed
   */
  int clear();
}
