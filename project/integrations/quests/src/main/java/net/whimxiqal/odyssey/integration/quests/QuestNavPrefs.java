/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.quests;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.whimxiqal.odyssey.paper.plugin.api.TrailNavigatorSettings;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Resolves OdysseyQuests' per-quest navigation preferences from the plugin config: whether a quest
 * auto-navigates, and the {@link NavigatorSettings} (navigator id + trail particles/colors) to
 * guide it with. Each field falls back from a per-quest override ({@code quests.<id>.<field>}) to
 * the global default, so an admin styles everything once and tweaks individual quests as needed.
 *
 * <p>The config is read live (through the plugin's {@link FileConfiguration}), so a {@code
 * /odysseyquests reload} — or Bukkit's own reload — takes effect on the next quest update without a
 * restart.
 */
final class QuestNavPrefs {

  private final FileConfiguration config;

  QuestNavPrefs(FileConfiguration config) {
    this.config = config;
  }

  /** Whether a trip should be auto-started when {@code questId}'s compass target updates. */
  boolean autoNavigate(String questId) {
    ConfigurationSection quest = questSection(questId);
    if (quest != null && quest.isSet("enabled")) {
      return quest.getBoolean("enabled");
    }
    return config.getBoolean("auto-navigate", true);
  }

  /** The navigator settings (id + trail styling) to guide {@code questId} with. */
  NavigatorSettings settings(String questId) {
    ConfigurationSection quest = questSection(questId);
    String navigator = firstSet(quest, "navigator", config.getString("navigator", "trail"));
    if (!TrailNavigatorSettings.NAVIGATOR_ID.equals(navigator)) {
      // A non-trail navigator: we don't know its setting keys, so pass only the id.
      return NavigatorSettings.builder(navigator).build();
    }
    List<Particle> particles = parseParticles(stringList(quest, "particles", "particles"));
    List<Color> colors = parseColors(stringList(quest, "colors", "colors"));
    TrailNavigatorSettings.Builder builder = TrailNavigatorSettings.builder();
    if (!particles.isEmpty()) {
      builder.particles(particles);
    }
    if (!colors.isEmpty()) {
      builder.colors(colors);
    }
    return builder.build();
  }

  private ConfigurationSection questSection(String questId) {
    ConfigurationSection quests = config.getConfigurationSection("quests");
    return quests == null ? null : quests.getConfigurationSection(questId);
  }

  private static String firstSet(ConfigurationSection quest, String key, String fallback) {
    return quest != null && quest.isSet(key) ? quest.getString(key) : fallback;
  }

  /**
   * A per-quest string list override ({@code quests.<id>.<questKey>}), else the global {@code key}.
   */
  private List<String> stringList(ConfigurationSection quest, String questKey, String globalKey) {
    if (quest != null && quest.isSet(questKey)) {
      return quest.getStringList(questKey);
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
