/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.audience.Audience;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.hologram.TrailLabels;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.navigator.AbstractTrailNavigator;
import org.cobblestonemc.plugin.navigator.TrailBlock;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.cobblestonemc.plugin.navigator.Vec3;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.effect.particle.ParticleEffect;
import org.spongepowered.api.effect.particle.ParticleOptions;
import org.spongepowered.api.effect.particle.ParticleType;
import org.spongepowered.api.effect.particle.ParticleTypes;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.util.Color;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.math.vector.Vector3d;

/**
 * The Sponge binding of {@link AbstractTrailNavigator}: the shared trail geometry with Sponge's
 * native particle spawning. Each configured particle type is drawn per-particle (DUST takes a
 * random colour from the configured palette), shown only to the guided player. The player is
 * re-resolved by UUID each tick so a logout invalidates cleanly. Sponge is single-threaded, so
 * rendering runs on the main server thread.
 */
final class SpongeTrailNavigator extends AbstractTrailNavigator<ServerLocation> {

  private final UUID playerId;
  private final List<ParticleType> particles;
  private final List<ParticleType> highlightParticles;
  private final List<Color> colors;
  // Block states are immutable and shared, and a data lookup is slow, so remember each answer.
  private final Map<BlockState, Boolean> solidByState = new HashMap<>();

  SpongeTrailNavigator(
      ServerPlayer player,
      Path<ServerLocation, MinecraftStepPayload> path,
      int bufferCells,
      List<ParticleType> particleTypes,
      List<ParticleType> highlightParticleTypes,
      List<Color> palette,
      double density,
      int recalcDistance,
      Messages messages,
      TrailLabels<ServerLocation> labels,
      BooleanSupplier showLabel) {
    super(bufferCells, density, recalcDistance, messages, player.locale(), labels, showLabel);
    this.playerId = player.uniqueId();
    this.particles = List.copyOf(particleTypes);
    this.highlightParticles = List.copyOf(highlightParticleTypes);
    this.colors = palette.isEmpty() ? List.of(Color.ofRgb(0x55FFFF)) : List.copyOf(palette);
    setPath(path);
  }

  private Optional<ServerPlayer> player() {
    return Sponge.server().player(playerId);
  }

  @Override
  protected UUID playerId() {
    return playerId;
  }

  @Override
  protected boolean playerOnline() {
    return player().map(ServerPlayer::isOnline).orElse(false);
  }

  @Override
  protected Vec3 playerPoint() {
    return player()
        .map(p -> p.serverLocation())
        .map(l -> new Vec3(l.x(), l.y(), l.z()))
        .orElse(new Vec3(0, 0, 0));
  }

  @Override
  protected Vec3 playerEye() {
    return player()
        .map(p -> p.eyePosition().get())
        .map(eye -> new Vec3(eye.x(), eye.y(), eye.z()))
        .orElse(new Vec3(0, 0, 0));
  }

  @Override
  protected String playerWorldKey() {
    return player().map(p -> p.world().key().asString()).orElse(null);
  }

  @Override
  protected Vec3 renderPoint(ServerLocation location) {
    // Y is raised by 0.9 instead of 0.5 so it sits near the centre of the player's body.
    return new Vec3(location.blockX() + 0.5, location.blockY() + 0.9, location.blockZ() + 0.5);
  }

  @Override
  protected ServerLocation location(Vec3 point) {
    return player().orElseThrow().world().location(point.x(), point.y(), point.z());
  }

  @Override
  protected String worldKey(ServerLocation location) {
    return location.world().key().asString();
  }

  @Override
  protected Audience audience() {
    return player().map(p -> (Audience) p).orElse(Audience.empty());
  }

  @Override
  protected void spawnTrailParticle(double x, double y, double z, double vx, double vy, double vz) {
    spawnParticle(particles, x, y, z, vx, vy, vz);
  }

  @Override
  protected void spawnHighlightParticle(double x, double y, double z) {
    spawnParticle(highlightParticles, x, y, z, 0, 0, 0);
  }

  private void spawnParticle(
      List<ParticleType> types, double x, double y, double z, double vx, double vy, double vz) {
    if (types.isEmpty()) {
      return;
    }
    Optional<ServerPlayer> player = player();
    if (player.isEmpty()) {
      return;
    }
    ThreadLocalRandom random = ThreadLocalRandom.current();
    ParticleType type = types.get(random.nextInt(types.size()));
    ParticleEffect.Builder effect = ParticleEffect.builder().type(type).quantity(1);
    if (type.equals(ParticleTypes.DUST.get())) {
      effect.option(ParticleOptions.COLOR.get(), colors.get(random.nextInt(colors.size())));
    } else if (vx != 0 || vy != 0 || vz != 0) {
      effect.velocity(new Vector3d(vx, vy, vz));
    }
    player.get().spawnParticles(effect.build(), new Vector3d(x, y, z));
  }

  @Override
  protected TrailSmoother.BlockProbe blockProbe() {
    Optional<ServerPlayer> player = player();
    if (player.isEmpty()) {
      return (x, y, z) -> TrailBlock.UNLOADED;
    }
    return new SpongeWorldProbe(player.get().world(), solidByState);
  }
}
