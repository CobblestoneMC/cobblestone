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
 * them. An item left blank falls back to the menu's own default. A town's item may instead be a
 * banner in its map color, matched to the nearest of the sixteen dyes.
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

  /** A town's item: its banner, unless the config names one item for every town. */
  Display town(Town town) {
    String configured = config.getString("icons.town");
    if (configured != null && configured.trim().equalsIgnoreCase(MAP_COLOR)) {
      return Display.of(PaperIcon.of(banner(town.getMapColorHexCode())));
    }
    return simpleConfiguredDisplay("town");
  }

  /**
   * The single item configured under {@code icons.<key>}, or the menu's own default if it is left
   * blank.
   */
  public Display simpleConfiguredDisplay(String key) {
    return Icon.parse(config.getString("icons." + key)).map(Display::of).orElse(Display.none());
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
