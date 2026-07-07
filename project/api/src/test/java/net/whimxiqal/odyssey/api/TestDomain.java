/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

/**
 * A trivial {@link Domain} for core-api tests: identified by its {@code key}, with value-based
 * equality from the record components.
 *
 * @param key the domain identifier
 * @param minY the world floor
 * @param maxY the world ceiling
 */
record TestDomain(String key, int minY, int maxY) implements Domain {

  static TestDomain of(String key) {
    return new TestDomain(key, -64, 320);
  }
}
