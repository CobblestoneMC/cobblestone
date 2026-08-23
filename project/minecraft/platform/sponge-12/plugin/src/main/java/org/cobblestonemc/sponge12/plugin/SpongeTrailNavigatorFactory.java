/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.cobblestonemc.plugin.config.ConfigKeys;
import org.cobblestonemc.plugin.config.ConfigManager;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.sponge12.plugin.api.NavigatorFactory;
import org.cobblestonemc.sponge12.plugin.api.TrailNavigatorSettings;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.effect.particle.ParticleType;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.registry.RegistryTypes;
import org.spongepowered.api.util.Color;
import org.spongepowered.api.world.server.ServerLocation;

/**
 * The built-in {@link NavigatorFactory} (key {@code trail}) that creates {@link
 * SpongeTrailNavigator}s. Appearance (buffer, colors, density) is read from config on each
 * creation, so a reload applies to subsequently started trips. Particle names are the vanilla ids
 * (matched case-insensitively against {@code minecraft:<name>}).
 */
public final class SpongeTrailNavigatorFactory implements NavigatorFactory {

  private final ConfigManager config;
  private final ConfigKeys keys;
  private final Messages messages;

  /**
   * Creates the factory.
   *
   * @param config the config manager (read live for trail appearance)
   * @param keys the registered config keys
   * @param messages the message renderer (for action prompts)
   */
  public SpongeTrailNavigatorFactory(ConfigManager config, ConfigKeys keys, Messages messages) {
    this.config = config;
    this.keys = keys;
    this.messages = messages;
  }

  @Override
  public Navigator<ServerLocation> create(
      ServerPlayer player,
      Path<ServerLocation, MinecraftStepPayload> path,
      NavigatorSettings settings) {
    List<ParticleType> particles =
        settings
            .get(TrailNavigatorSettings.PARTICLES)
            .orElseGet(() -> parseParticles(config.get(keys.trailParticles)));
    List<ParticleType> highlightParticles =
        settings
            .get(TrailNavigatorSettings.HIGHLIGHT_PARTICLES)
            .orElseGet(() -> parseParticles(config.get(keys.trailHighlightParticles)));
    List<Color> colors =
        settings
            .get(TrailNavigatorSettings.COLORS)
            .orElseGet(() -> parseColors(config.get(keys.trailColors)));
    return new SpongeTrailNavigator(
        player,
        path,
        config.get(keys.trailBufferCells),
        particles,
        highlightParticles,
        colors,
        config.get(keys.trailDensity),
        config.get(keys.tripsRecalculateDistance),
        messages);
  }

  /** Resolves vanilla particle ids ({@code minecraft:<name>}), skipping unknown ones. */
  private static List<ParticleType> parseParticles(List<String> names) {
    List<ParticleType> particles = new ArrayList<>();
    for (String name : names) {
      Sponge.game()
          .registry(RegistryTypes.PARTICLE_TYPE)
          .<ParticleType>findValue(ResourceKey.minecraft(name.trim().toLowerCase(Locale.ROOT)))
          .ifPresent(particles::add);
    }
    return particles;
  }

  /** Parses hex {@code RRGGBB} strings to colors, skipping malformed entries. */
  private static List<Color> parseColors(List<String> hexes) {
    List<Color> colors = new ArrayList<>();
    for (String hex : hexes) {
      try {
        colors.add(Color.ofRgb(Integer.parseInt(hex.trim(), 16)));
      } catch (RuntimeException ignored) {
        // Skip a malformed color; the trail falls back to a default if none are valid.
      }
    }
    return colors;
  }
}
