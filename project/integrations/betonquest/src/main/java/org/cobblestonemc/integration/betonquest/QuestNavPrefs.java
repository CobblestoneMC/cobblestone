/*
 * CobblestoneBetonQuest — a BetonQuest integration for the Cobblestone navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. Because it links against BetonQuest (GPL-3.0), this
 * module is distributed under the GPL rather than Cobblestone's MIT license. It is distributed WITHOUT
 * ANY WARRANTY. See the GNU General Public License (the LICENSE file in this module) for details.
 */
package org.cobblestonemc.integration.betonquest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.cobblestonemc.paper.plugin.api.TrailNavigatorSettings;
import org.cobblestonemc.plugin.api.NavigatorSettings;

/**
 * Resolves CobblestoneBetonQuest's per-compass navigation preferences from the plugin config:
 * whether a compass auto-navigates, and the {@link NavigatorSettings} (navigator id + trail
 * particles/colors) to guide it with. Each field falls back from a per-compass override ({@code
 * compasses.<tag>.<field>}) to the global default. A {@code null} tag (an unrecognized compass)
 * uses the global defaults only.
 *
 * <p>The config is read live (through the plugin's {@link FileConfiguration}), so a reload takes
 * effect on the next compass change without a restart.
 */
final class QuestNavPrefs {

  private final FileConfiguration config;

  QuestNavPrefs(FileConfiguration config) {
    this.config = config;
  }

  /** Whether a trip should be auto-started when a player sets their compass to {@code tag}. */
  boolean autoNavigate(String tag) {
    ConfigurationSection compass = compassSection(tag);
    if (compass != null && compass.isSet("enabled")) {
      return compass.getBoolean("enabled");
    }
    return config.getBoolean("auto-navigate", true);
  }

  /** The navigator settings (id + trail styling) to guide {@code tag} with. */
  NavigatorSettings settings(String tag) {
    ConfigurationSection compass = compassSection(tag);
    String navigator = firstSet(compass, "navigator", config.getString("navigator", "trail"));
    if (!TrailNavigatorSettings.NAVIGATOR_ID.equals(navigator)) {
      // A non-trail navigator: we don't know its setting keys, so pass only the id.
      return NavigatorSettings.builder(navigator).build();
    }
    List<Particle> particles = parseParticles(stringList(compass, "particles", "particles"));
    List<Color> colors = parseColors(stringList(compass, "colors", "colors"));
    TrailNavigatorSettings.Builder builder = TrailNavigatorSettings.builder();
    if (!particles.isEmpty()) {
      builder.particles(particles);
    }
    if (!colors.isEmpty()) {
      builder.colors(colors);
    }
    return builder.build();
  }

  private ConfigurationSection compassSection(String tag) {
    if (tag == null) {
      return null;
    }
    ConfigurationSection compasses = config.getConfigurationSection("compasses");
    return compasses == null ? null : compasses.getConfigurationSection(tag);
  }

  private static String firstSet(ConfigurationSection compass, String key, String fallback) {
    return compass != null && compass.isSet(key) ? compass.getString(key) : fallback;
  }

  /**
   * A per-compass string list override ({@code compasses.<tag>.<localKey>}), else global {@code
   * key}.
   */
  private List<String> stringList(ConfigurationSection compass, String localKey, String globalKey) {
    if (compass != null && compass.isSet(localKey)) {
      return compass.getStringList(localKey);
    }
    return config.getStringList(globalKey);
  }

  /** Parses Bukkit {@code Particle} names, skipping unknown ones. */
  private static List<Particle> parseParticles(List<String> names) {
    List<Particle> particles = new ArrayList<>();
    for (String name : names) {
      try {
        particles.add(Particle.valueOf(name.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ignored) {
        // Skip an unknown particle name.
      }
    }
    return particles;
  }

  /** Parses hex {@code RRGGBB} strings to colors, skipping malformed entries. */
  private static List<Color> parseColors(List<String> hexes) {
    List<Color> colors = new ArrayList<>();
    for (String hex : hexes) {
      try {
        colors.add(Color.fromRGB(Integer.parseInt(hex.trim(), 16)));
      } catch (RuntimeException ignored) {
        // Skip a malformed color.
      }
    }
    return colors;
  }
}
