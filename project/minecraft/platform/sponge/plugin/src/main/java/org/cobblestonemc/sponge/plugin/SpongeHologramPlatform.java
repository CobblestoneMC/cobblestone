/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.hologram.Gaze;
import org.cobblestonemc.plugin.hologram.Hologram;
import org.cobblestonemc.plugin.hologram.HologramPlatform;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.cobblestonemc.plugin.navigator.Vec3;
import org.cobblestonemc.sponge.hologram.SpongeHologram;
import org.cobblestonemc.sponge.hologram.SpongeHolograms;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.LightTypes;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.api.world.weather.WeatherType;
import org.spongepowered.api.world.weather.WeatherTypes;
import org.spongepowered.math.vector.Vector3d;

/** Trip labels on Sponge, drawn with {@link SpongeHolograms}. */
final class SpongeHologramPlatform implements HologramPlatform<SpongeTripAgent, ServerLocation> {

  private final SpongeHolograms holograms;
  // Block states are immutable and shared, and a data lookup is slow, so remember each answer.
  private final Map<BlockState, Boolean> solidByState = new HashMap<>();

  SpongeHologramPlatform(SpongeHolograms holograms) {
    this.holograms = holograms;
  }

  private static Hologram wrap(SpongeHologram label) {
    return new Hologram() {
      @Override
      public boolean move(Vec3 at) {
        return label.move(vector(at));
      }

      @Override
      public void text(Component text) {
        label.text(text);
      }

      @Override
      public void remove() {
        label.remove();
      }
    };
  }

  private static Vector3d vector(Vec3 at) {
    return new Vector3d(at.x(), at.y(), at.z());
  }

  @Override
  public Vec3 point(ServerLocation location) {
    return new Vec3(location.x(), location.y(), location.z());
  }

  @Override
  public Optional<Gaze> gaze(SpongeTripAgent agent) {
    ServerPlayer player = agent.player();
    if (!player.isOnline()) {
      return Optional.empty();
    }
    Vector3d eye = player.eyePosition().get();
    // Minecraft's look vector, from the head's pitch (x) and yaw (y) in degrees.
    Vector3d rotation = player.headRotation().get();
    double pitch = Math.toRadians(rotation.x());
    double yaw = Math.toRadians(rotation.y());
    Vec3 look =
        new Vec3(
            -Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    return Optional.of(new Gaze(new Vec3(eye.x(), eye.y(), eye.z()), look));
  }

  @Override
  public TrailSmoother.BlockProbe probe(SpongeTripAgent agent) {
    return new SpongeWorldProbe(agent.player().world(), solidByState);
  }

  @Override
  public int light(SpongeTripAgent agent, Vec3 at) {
    ServerWorld world = agent.player().world();
    int x = (int) Math.floor(at.x());
    int y = (int) Math.floor(at.y());
    int z = (int) Math.floor(at.z());
    if (!world.isBlockLoaded(x, y, z)) {
      return 0;
    }
    int sky = world.light(LightTypes.SKY, x, y, z) - skyDarkening(world);
    return Math.max(world.light(LightTypes.BLOCK, x, y, z), Math.max(0, sky));
  }

  /**
   * How far the time of day and the weather dim sky light, as Minecraft works it out: Sponge only
   * gives the raw sky light, which is as bright at midnight as at noon.
   */
  static int skyDarkening(ServerWorld world) {
    double day = world.properties().dayTime().asTicks().ticks() / 24000.0 - 0.25;
    day -= Math.floor(day);
    double timeOfDay = (day * 2.0 + (0.5 - Math.cos(day * Math.PI) / 2.0)) / 3.0;
    double sun = 0.5 + 2.0 * Math.clamp(Math.cos(timeOfDay * 2.0 * Math.PI), -0.25, 0.25);
    WeatherType weather = world.weather().type();
    boolean thunder = weather.equals(WeatherTypes.THUNDER.get());
    boolean rain = thunder || weather.equals(WeatherTypes.RAIN.get());
    double rainDimming = rain ? 1.0 - 5.0 / 16.0 : 1.0;
    double thunderDimming = thunder ? 1.0 - 5.0 / 16.0 : 1.0;
    return (int) ((1.0 - sun * rainDimming * thunderDimming) * 11.0);
  }

  @Override
  public Optional<Hologram> spawn(SpongeTripAgent agent, Vec3 at, Component text, Runnable onHit) {
    ServerPlayer player = agent.player();
    return holograms
        .spawn(player, player.world(), vector(at), text, onHit)
        .map(SpongeHologramPlatform::wrap);
  }
}
