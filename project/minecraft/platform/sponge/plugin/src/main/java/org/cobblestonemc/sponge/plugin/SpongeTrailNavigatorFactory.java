/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.cobblestonemc.plugin.config.ConfigKeys;
import org.cobblestonemc.plugin.config.ConfigManager;
import org.cobblestonemc.plugin.hologram.TrailLabels;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.navigator.TrailShape;
import org.cobblestonemc.sponge.plugin.api.NavigatorFactory;
import org.cobblestonemc.sponge.plugin.api.TrailNavigatorSettings;
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
  private final TrailLabels<ServerLocation> labels;

  /**
   * Creates the factory.
   *
   * @param config the config manager (read live for trail appearance)
   * @param keys the registered config keys
   * @param messages the message renderer (for action prompts)
   * @param labels where trips' labels float over their trails
   */
  public SpongeTrailNavigatorFactory(
      ConfigManager config,
      ConfigKeys keys,
      Messages messages,
      TrailLabels<ServerLocation> labels) {
    this.config = config;
    this.keys = keys;
    this.messages = messages;
    this.labels = labels;
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
    // An explicit choice holds for the trip; otherwise the config is followed live.
    BooleanSupplier showLabel =
        settings
            .get(TrailNavigatorSettings.SHOW_LABEL)
            .<BooleanSupplier>map(show -> () -> show)
            .orElse(() -> config.get(keys.tripsHolograms));
    TrailShape shape =
        new TrailShape(
            config.get(keys.trailLeadBlocks),
            config.get(keys.trailLeadSeconds),
            config.get(keys.trailGoalClearance));
    return new SpongeTrailNavigator(
        player,
        path,
        config.get(keys.trailBufferCells),
        particles,
        highlightParticles,
        colors,
        config.get(keys.trailDensity),
        config.get(keys.trailParticleTicks),
        config.get(keys.tripsRecalculateDistance),
        messages,
        labels,
        shape,
        showLabel);
  }

  /**
   * Resolves vanilla particle ids ({@code minecraft:<name>}), skipping unknown ones. {@code trail}
   * becomes dust where the server can't draw it, as before Minecraft 1.21.2.
   */
  private static List<ParticleType> parseParticles(List<String> names) {
    List<ParticleType> particles = new ArrayList<>();
    for (String name : names) {
      String key = name.trim().toLowerCase(Locale.ROOT);
      if (key.equals("trail")) {
        particles.add(SpongeTrailNavigator.trailOrDust());
        continue;
      }
      Sponge.game()
          .registry(RegistryTypes.PARTICLE_TYPE)
          .<ParticleType>findValue(ResourceKey.minecraft(key))
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
