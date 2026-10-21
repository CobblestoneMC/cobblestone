/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin.api;

import java.util.List;
import org.cobblestonemc.plugin.api.NavigatorSettingKey;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.spongepowered.api.effect.particle.ParticleType;
import org.spongepowered.api.util.Color;

/**
 * Per-trip appearance for the built-in <b>trail</b> navigator — the styled particle line. Anything
 * left unset falls back to the server's {@code config.yml} defaults.
 */
public final class TrailNavigatorSettings {

  /** The trail navigator id. */
  public static final String NAVIGATOR_ID = "trail";

  /** The particle types the trail randomly draws with (only {@code DUST} is colored). */
  public static final NavigatorSettingKey<List<ParticleType>> PARTICLES =
      new NavigatorSettingKey<>("trail.particles");

  /**
   * The particle types the trail draws with in highlighted locations (only {@code DUST} colored).
   */
  public static final NavigatorSettingKey<List<ParticleType>> HIGHLIGHT_PARTICLES =
      new NavigatorSettingKey<>("trail.highlight_particles");

  /** The colors {@code DUST} particles are randomly drawn in. */
  public static final NavigatorSettingKey<List<Color>> COLORS =
      new NavigatorSettingKey<>("trail.colors");

  /**
   * Whether the trip's name floats where the trail starts while the player stands still, with a
   * Cancel that cancels the trip. Unset follows the server's {@code trips.trail_labels} config.
   */
  public static final NavigatorSettingKey<Boolean> SHOW_TRAIL_LABEL =
      new NavigatorSettingKey<>("trail.show_trail_label");

  /**
   * Whether the destination's name floats over it once the player is close. Unset follows the
   * server's {@code trips.destination_labels} config.
   */
  public static final NavigatorSettingKey<Boolean> SHOW_DESTINATION_LABEL =
      new NavigatorSettingKey<>("trail.show_destination_label");

  private TrailNavigatorSettings() {}

  /**
   * A builder producing {@link NavigatorSettings} for the trail navigator.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** A builder of trail settings. */
  public static final class Builder {

    private final NavigatorSettings.Builder delegate = NavigatorSettings.builder(NAVIGATOR_ID);

    private Builder() {}

    /**
     * Sets the particle types the trail draws with.
     *
     * @param particles the particle types
     * @return this builder
     */
    public Builder particles(List<ParticleType> particles) {
      delegate.set(PARTICLES, List.copyOf(particles));
      return this;
    }

    /**
     * Sets the particle types the trail draws with in highlighted locations.
     *
     * @param particles the particle types
     * @return this builder
     */
    public Builder highlightParticles(List<ParticleType> particles) {
      delegate.set(HIGHLIGHT_PARTICLES, List.copyOf(particles));
      return this;
    }

    /**
     * Sets the colors {@code DUST} particles are drawn in.
     *
     * @param colors the colors
     * @return this builder
     */
    public Builder colors(List<Color> colors) {
      delegate.set(COLORS, List.copyOf(colors));
      return this;
    }

    /**
     * Sets whether the trip's name floats where the trail starts while the player stands still. See
     * {@link #SHOW_TRAIL_LABEL}.
     *
     * @param show whether to show it
     * @return this builder
     */
    public Builder showTrailLabel(boolean show) {
      delegate.set(SHOW_TRAIL_LABEL, show);
      return this;
    }

    /**
     * Sets whether the destination's name floats over it once the player is close. See {@link
     * #SHOW_DESTINATION_LABEL}.
     *
     * @param show whether to show it
     * @return this builder
     */
    public Builder showDestinationLabel(boolean show) {
      delegate.set(SHOW_DESTINATION_LABEL, show);
      return this;
    }

    /**
     * Builds the settings.
     *
     * @return the settings
     */
    public NavigatorSettings build() {
      return delegate.build();
    }
  }
}
