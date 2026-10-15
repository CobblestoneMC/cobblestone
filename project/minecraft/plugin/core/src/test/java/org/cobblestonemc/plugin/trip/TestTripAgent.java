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

public record TestTripAgent(UUID uuid, Audience audience) implements TripAgent<Object> {

  public TestTripAgent(UUID uuid) {
    this(uuid, Audience.empty());
  }

  @Override
  public Locale locale() {
    return Locale.ENGLISH;
  }

  @Override
  public Void entity() {
    return null;
  }
}
