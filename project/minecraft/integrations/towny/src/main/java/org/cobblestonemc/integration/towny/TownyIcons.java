/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.towny;

import com.palmergames.bukkit.towny.object.Town;
import java.util.Locale;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.cobblestonemc.paper.plugin.api.PaperIcon;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.api.Icon;

/**
 * The items Towny's entries show in Cobblestone's navigation menu, as this plugin's config sets
 * them (see {@code config.yml}). A town's own item is, by default, a banner in its map color — the
 * color its mayor picks with {@code /town set mapcolor} — matched to the nearest of the sixteen
 * dyes.
 */
final class TownyIcons {

  /** The config value that means "a banner in the town's map color". */
  static final String MAP_COLOR = "map-color";

  /** A town that has no map color, or a color that does not read. */
  private static final Material NO_COLOR = Material.WHITE_BANNER;

  private final FileConfiguration config;

  TownyIcons(FileConfiguration config) {
    this.config = config;
  }

  Display root() {
    return display("root", "minecraft:bell");
  }

  Display towns() {
    return display("towns", "minecraft:white_banner");
  }

  /** A town's item: its banner, unless the config names one item for every town. */
  Icon town(Town town) {
    String configured = config.getString("icons.town", MAP_COLOR);
    if (configured == null || configured.trim().equalsIgnoreCase(MAP_COLOR)) {
      return PaperIcon.of(banner(town.getMapColorHexCode()));
    }
    return Icon.parse(configured).orElseGet(() -> PaperIcon.of(NO_COLOR));
  }

  Display home() {
    return display("home", "minecraft:red_bed");
  }

  Display outposts() {
    return display("outposts", "minecraft:campfire");
  }

  Display outpost() {
    return display("outpost", "minecraft:campfire");
  }

  Display plotTypes() {
    return display("plot-types", "minecraft:filled_map");
  }

  Display plotType() {
    return display("plot-type", "minecraft:map");
  }

  Display plots() {
    return display("plots", "minecraft:oak_sign");
  }

  Display plot() {
    return display("plot", "minecraft:oak_sign");
  }

  private Display display(String key, String fallback) {
    return Display.of(
        Icon.parse(config.getString("icons." + key, fallback))
            .orElseGet(() -> Icon.item(fallback)));
  }

  /**
   * The banner nearest a map color written as hex ({@code RRGGBB}, with or without a {@code #}).
   */
  static Material banner(String hex) {
    if (hex == null || hex.isBlank()) {
      return NO_COLOR;
    }
    int rgb;
    try {
      rgb = Integer.parseInt(hex.trim().replaceFirst("^#", ""), 16);
    } catch (NumberFormatException e) {
      return NO_COLOR;
    }
    DyeColor nearest = DyeColor.WHITE;
    long best = Long.MAX_VALUE;
    for (DyeColor dye : DyeColor.values()) {
      long distance = distance(rgb, dye.getColor());
      if (distance < best) {
        best = distance;
        nearest = dye;
      }
    }
    Material banner = Material.matchMaterial(nearest.name().toLowerCase(Locale.ROOT) + "_banner");
    return banner == null ? NO_COLOR : banner;
  }

  private static long distance(int rgb, Color color) {
    long red = ((rgb >> 16) & 0xFF) - color.getRed();
    long green = ((rgb >> 8) & 0xFF) - color.getGreen();
    long blue = (rgb & 0xFF) - color.getBlue();
    return red * red + green * green + blue * blue;
  }
}
