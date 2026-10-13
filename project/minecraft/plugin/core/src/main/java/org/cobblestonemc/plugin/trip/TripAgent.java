/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.trip;

import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;

public interface TripAgent<E> {

  UUID uuid();

  E entity();

  /**
   * Returns who hears about this agent's trips.
   *
   * @return the agent as an audience
   */
  Audience audience();

  /**
   * Returns the locale this agent's messages are rendered in.
   *
   * @return the agent's locale
   */
  Locale locale();
}
