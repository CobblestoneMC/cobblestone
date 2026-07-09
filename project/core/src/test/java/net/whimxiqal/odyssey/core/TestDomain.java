/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import net.whimxiqal.odyssey.api.Domain;

/**
 * A trivial single-type {@link Domain} for core tests, identified by {@code key}.
 *
 * @param key the domain identifier
 */
record TestDomain(String key) implements Domain {

  @Override
  public int minY() {
    return -64;
  }

  @Override
  public int maxY() {
    return 320;
  }
}
