/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NaturalOrderTest {

  @Test
  void numbersSortByValueAndLettersIgnoreCase() {
    List<String> keys =
        new ArrayList<>(
            List.of(
                "outpost-10", "Outpost-2", "outpost-1", "bob", "Alice", "99999999999999999999"));
    keys.sort(NaturalOrder.INSTANCE);
    assertEquals(
        List.of("99999999999999999999", "Alice", "bob", "outpost-1", "Outpost-2", "outpost-10"),
        keys);
  }

  @Test
  void tiesAreBrokenSoTheOrderIsTotal() {
    // Equal by value and ignoring case, but still not the same key.
    assertNotEquals(0, NaturalOrder.INSTANCE.compare("a7", "a07"));
    assertNotEquals(0, NaturalOrder.INSTANCE.compare("Bob", "bob"));
    assertEquals(
        Integer.signum(NaturalOrder.INSTANCE.compare("a7", "a07")),
        -Integer.signum(NaturalOrder.INSTANCE.compare("a07", "a7")));
    assertEquals(0, NaturalOrder.INSTANCE.compare("a07", "a07"));
  }
}
