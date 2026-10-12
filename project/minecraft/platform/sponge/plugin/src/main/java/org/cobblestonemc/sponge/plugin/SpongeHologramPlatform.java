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
import org.spongepowered.api.world.server.ServerLocation;
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
    ServerPlayer player = (ServerPlayer) agent.entity();
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
    return new SpongeWorldProbe(((ServerPlayer) agent.entity()).world(), solidByState);
  }

  @Override
  public Optional<Hologram> spawn(SpongeTripAgent agent, Vec3 at, Component text, Runnable onHit) {
    ServerPlayer player = (ServerPlayer) agent.entity();
    return holograms
        .spawn(player, player.world(), vector(at), text, onHit)
        .map(SpongeHologramPlatform::wrap);
  }
}
