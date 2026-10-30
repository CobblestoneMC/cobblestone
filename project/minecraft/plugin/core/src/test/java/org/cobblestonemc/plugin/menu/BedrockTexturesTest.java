/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.config.MenuIcons;
import org.junit.jupiter.api.Test;

/** Which Java items have a picture on a Bedrock form. */
class BedrockTexturesTest {

  @Test
  void everyDefaultMenuItemHasOneButTheHeads() {
    // Heads have no texture of their own; the menu falls back to its folder or destination item.
    Set<String> heads = Set.of("minecraft:skeleton_skull", "minecraft:player_head");
    MenuIcons.DEFAULTS.forEach(
        (name, icon) -> {
          if (!heads.contains(icon.item())) {
            assertTrue(
                BedrockTextures.of(Icon.item(icon.item())).isPresent(), name + ": " + icon.item());
          }
        });
  }

  @Test
  void keysAreReadAsIconsAre() {
    assertEquals(
        Optional.of("textures/items/compass_item"), BedrockTextures.of(Icon.item("compass")));
    assertEquals(
        Optional.of("textures/items/compass_item"),
        BedrockTextures.of(Icon.item("Minecraft:Compass")));
  }

  @Test
  void headsAndUnknownItemsHaveNone() {
    assertEquals(Optional.empty(), BedrockTextures.of(Icon.head("abc")));
    assertEquals(Optional.empty(), BedrockTextures.of(Icon.playerHead(UUID.randomUUID())));
    assertEquals(Optional.empty(), BedrockTextures.of(Icon.item("example:widget")));
  }
}
