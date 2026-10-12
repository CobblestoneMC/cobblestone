/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.util.Optional;
import org.cobblestonemc.CellRegion;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * Makes the regions a search navigates to, from Sponge's types: an integration's {@link
 * WorldRegion} converted, or one of Cobblestone's own.
 */
public final class SpongeRegions {

  private final WorldWrapper worlds;

  SpongeRegions(WorldWrapper worlds) {
    this.worlds = worlds;
  }

  /**
   * An integration's region, in the search's terms.
   *
   * @param region the region
   * @return the region, or empty if its world is not loaded
   */
  public Optional<DomainRegion<MinecraftWorld>> of(WorldRegion<ServerWorld, Vector3i> region) {
    return region.world() == null
        ? Optional.empty()
        : Optional.of(SpongeConversions.region(region, worlds));
  }

  /**
   * The block at a location.
   *
   * @param location the location
   * @return the region
   */
  public DomainRegion<MinecraftWorld> block(ServerLocation location) {
    return new CellRegion<>(SpongeConversions.cell(location), worlds.wrap(location.world()));
  }
}
