/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.cobblestonemc.paper.hologram.PaperHologram;
import org.cobblestonemc.paper.hologram.PaperHolograms;
import org.cobblestonemc.plugin.hologram.Hologram;
import org.cobblestonemc.plugin.hologram.HologramPlatform;
import org.cobblestonemc.plugin.hologram.PlayerPosition;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.cobblestonemc.plugin.navigator.Vec3;

/** Trip labels on Paper, drawn with {@link PaperHolograms}. */
final class PaperHologramPlatform implements HologramPlatform<PaperTripAgent, Location> {

  private final PaperHolograms holograms;

  PaperHologramPlatform(PaperHolograms holograms) {
    this.holograms = holograms;
  }

  @Override
  public Vec3 point(Location location) {
    return new Vec3(location.getX(), location.getY(), location.getZ());
  }

  @Override
  public Optional<PlayerPosition> position(PaperTripAgent agent) {
    Player player = agent.player();
    if (!player.isOnline()) {
      return Optional.empty();
    }
    return Optional.of(
        new PlayerPosition(point(player.getLocation()), point(player.getEyeLocation())));
  }

  @Override
  public TrailSmoother.BlockProbe probe(PaperTripAgent agent) {
    return new PaperWorldProbe(agent.player().getWorld());
  }

  @Override
  public Optional<Hologram> spawn(PaperTripAgent agent, Vec3 at, Component text, Runnable onHit) {
    Player player = agent.player();
    World world = player.getWorld();
    return holograms
        .spawn(player, location(world, at), text, onHit)
        .map(PaperHologramPlatform::wrap);
  }

  private static Hologram wrap(PaperHologram label) {
    return new Hologram() {
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

  private static Location location(World world, Vec3 at) {
    return new Location(world, at.x(), at.y(), at.z());
  }
}
