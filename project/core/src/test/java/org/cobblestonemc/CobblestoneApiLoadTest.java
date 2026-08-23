/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Guards the {@code ServiceLoader} wiring behind {@link CobblestoneApi#load()}: the {@code
 * META-INF/services/org.cobblestonemc.CobblestoneApi} file must name a real, instantiable
 * implementation. A stale class name here would only surface as a crash at plugin enable, so this
 * catches it at build time instead.
 */
class CobblestoneApiLoadTest {

  @Test
  void loadResolvesAnImplementation() {
    CobblestoneApi api = CobblestoneApi.load();
    assertNotNull(api, "CobblestoneApi.load() must resolve a provider from the service file");
    assertTrue(api instanceof CobblestoneApiImpl, "expected the bundled CobblestoneApiImpl");
  }
}
