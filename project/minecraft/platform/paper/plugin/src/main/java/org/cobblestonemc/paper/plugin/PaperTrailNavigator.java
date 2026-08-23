/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.navigator.AbstractTrailNavigator;
import org.cobblestonemc.plugin.navigator.Vec3;

/**
 * The Paper binding of {@link AbstractTrailNavigator}: the shared trail geometry with Paper's
 * native particle spawning. Each configured particle type is drawn per-particle (DUST takes a
 * random colour from the configured palette — discrete, not blended, so they sparkle), shown only
 * to the guided player, and only when the target position is owned by the current region thread
 * (Folia-safe). Verified on a live server; the follow geometry is unit-tested in plugin-core.
 */
final class PaperTrailNavigator extends AbstractTrailNavigator<Location> {

  private static final float DUST_SIZE = 1.0f;

  private final Player player;
  private final List<Particle> particles;
  private final List<Particle> highlightParticles;
  private final List<Particle.DustOptions> dusts;

  PaperTrailNavigator(
      Player player,
      Path<Location, MinecraftStepPayload> path,
      int bufferCells,
      List<Particle> particleTypes,
      List<Particle> highlightParticleTypes,
      List<Color> palette,
      double density,
      int recalcDistance,
      Messages messages) {
    super(bufferCells, density, recalcDistance, messages, player.locale());
    this.player = player;
    this.particles = List.copyOf(particleTypes);
    this.highlightParticles = List.copyOf(highlightParticleTypes);
    List<Color> colors = palette.isEmpty() ? List.of(Color.AQUA) : palette;
    this.dusts = new ArrayList<>(colors.size());
    for (Color color : colors) {
      dusts.add(new Particle.DustOptions(color, DUST_SIZE));
    }
    setPath(path);
  }

  @Override
  protected boolean playerOnline() {
    return player.isOnline();
  }

  @Override
  protected Vec3 playerPoint() {
    Location location = player.getLocation();
    return new Vec3(location.getX(), location.getY(), location.getZ());
  }

  @Override
  protected String playerWorldKey() {
    World world = player.getWorld();
    return world == null ? null : world.getKey().asString();
  }

  @Override
  protected Vec3 renderPoint(Location location) {
    // Y is raised by 0.9 instead of 0.5 so it's in the center of the player's body-ish.
    return new Vec3(
        location.getBlockX() + 0.5, location.getBlockY() + 0.9, location.getBlockZ() + 0.5);
  }

  @Override
  protected String worldKey(Location location) {
    World world = location.getWorld();
    return world == null ? null : world.getKey().asString();
  }

  @Override
  protected Audience audience() {
    return player;
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
      List<Particle> particles, double x, double y, double z, double vx, double vy, double vz) {
    if (particles.isEmpty()) {
      return;
    }
    Location location = new Location(player.getWorld(), x, y, z);
    if (!Bukkit.isOwnedByCurrentRegion(location)) {
      return;
    }
    ThreadLocalRandom random = ThreadLocalRandom.current();
    final var particle = particles.get(random.nextInt(particles.size()));
    if (particle == Particle.DUST) {
      Particle.DUST
          .builder()
          .location(location)
          .receivers(player)
          .data(dusts.get(random.nextInt(dusts.size())))
          .spawn();
    } else {
      particle.builder().location(location).receivers(player).offset(vx, vy, vz).count(0).spawn();
    }
  }

  @Override
  protected boolean solidAt(int blockX, int blockY, int blockZ) {
    World world = player.getWorld();
    if (world == null) {
      return false;
    }
    Block block = world.getBlockAt(blockX, blockY, blockZ);
    return Bukkit.isOwnedByCurrentRegion(block) && block.getType().isSolid();
  }
}
