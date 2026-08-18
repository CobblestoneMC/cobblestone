/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin.api;

import java.util.List;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettingKey;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import org.bukkit.Color;
import org.bukkit.Particle;

/**
 * Per-trip appearance for the built-in <b>trail</b> navigator — the styled particle line. Build one
 * for a trip and pass it to the trip service:
 *
 * <pre>{@code
 * NavigatorSettings settings = TrailNavigatorSettings.builder()
 *     .particles(List.of(Particle.GLOW, Particle.DUST))
 *     .colors(List.of(Color.AQUA, Color.FUCHSIA))
 *     .build();
 * }</pre>
 *
 * <p>Anything left unset falls back to the server's {@code config.yml} defaults.
 */
public final class TrailNavigatorSettings {

  /** The trail navigator id. */
  public static final String NAVIGATOR_ID = "trail";

  /** The particle types the trail randomly draws with (only {@code DUST} is colored). */
  public static final NavigatorSettingKey<List<Particle>> PARTICLES =
      new NavigatorSettingKey<>("trail.particles");

  /**
   * The particle types the trail randomly draws with in highlighted locations (only {@code DUST} is
   * colored).
   */
  public static final NavigatorSettingKey<List<Particle>> HIGHLIGHT_PARTICLES =
      new NavigatorSettingKey<>("trail.highlight_particles");

  /** The colors {@code DUST} particles are randomly drawn in. */
  public static final NavigatorSettingKey<List<Color>> COLORS =
      new NavigatorSettingKey<>("trail.colors");

  private TrailNavigatorSettings() {}

  /** A builder producing {@link NavigatorSettings} for the trail navigator. */
  public static Builder builder() {
    return new Builder();
  }

  /** A builder of trail settings. */
  public static final class Builder {

    private final NavigatorSettings.Builder delegate = NavigatorSettings.builder(NAVIGATOR_ID);

    private Builder() {}

    /** Sets the particle types the trail draws with. */
    public Builder particles(List<Particle> particles) {
      delegate.set(PARTICLES, List.copyOf(particles));
      return this;
    }

    /** Sets the particle types the trail draws with. */
    public Builder highlightParticles(List<Particle> particles) {
      delegate.set(HIGHLIGHT_PARTICLES, List.copyOf(particles));
      return this;
    }

    /** Sets the colors {@code DUST} particles are drawn in. */
    public Builder colors(List<Color> colors) {
      delegate.set(COLORS, List.copyOf(colors));
      return this;
    }

    /** Builds the settings. */
    public NavigatorSettings build() {
      return delegate.build();
    }
  }
}
