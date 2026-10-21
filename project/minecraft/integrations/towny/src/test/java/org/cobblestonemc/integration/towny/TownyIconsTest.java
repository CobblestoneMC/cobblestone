/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.towny;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class TownyIconsTest {

  @Test
  void aMapColorBecomesTheNearestDyesBanner() {
    assertEquals(Material.RED_BANNER, TownyIcons.banner("B02E26"));
    assertEquals(Material.RED_BANNER, TownyIcons.banner("#c03030"));
    assertEquals(Material.BLUE_BANNER, TownyIcons.banner("3c44aa"));
    assertEquals(Material.BLACK_BANNER, TownyIcons.banner("000000"));
  }

  @Test
  void noColorOrOneThatDoesNotReadIsWhite() {
    assertEquals(Material.WHITE_BANNER, TownyIcons.banner(null));
    assertEquals(Material.WHITE_BANNER, TownyIcons.banner(" "));
    assertEquals(Material.WHITE_BANNER, TownyIcons.banner("not a color"));
  }
}
