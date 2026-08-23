/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.trip;

import java.util.UUID;

public record TestTripAgent(UUID uuid) implements TripAgent<Object> {
  @Override
  public Void entity() {
    return null;
  }
}
