/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Guards the {@code ServiceLoader} wiring behind {@link OdysseyApi#load()}: the
 * {@code META-INF/services/net.whimxiqal.odyssey.OdysseyApi} file must name a real, instantiable
 * implementation. A stale class name here would only surface as a crash at plugin enable, so this
 * catches it at build time instead.
 */
class OdysseyApiLoadTest {

  @Test
  void loadResolvesAnImplementation() {
    OdysseyApi api = OdysseyApi.load();
    assertNotNull(api, "OdysseyApi.load() must resolve a provider from the service file");
    assertTrue(api instanceof OdysseyApiImpl, "expected the bundled OdysseyApiImpl");
  }
}
