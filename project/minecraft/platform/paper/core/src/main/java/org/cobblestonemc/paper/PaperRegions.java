/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.cobblestonemc.CellRegion;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.RegionFactory;
import org.cobblestonemc.minecraft.SurfaceRegion;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.joml.Vector3i;

/**
 * Makes the regions a search navigates to, from Paper's types: an integration's {@link WorldRegion}
 * converted, or one of Cobblestone's own, which a {@link WorldRegion} cannot express.
 */
public final class PaperRegions {

  private final WorldWrapper worlds;

  PaperRegions(WorldWrapper worlds) {
    this.worlds = worlds;
  }

  /**
   * An integration's region, in the search's terms.
   *
   * @param region the region
   * @return the region, or empty if its world is not loaded
   */
  public Optional<DomainRegion<MinecraftWorld>> of(WorldRegion<World, Vector3i> region) {
    return region.world() == null
        ? Optional.empty()
        : Optional.of(PaperConversions.region(region, worlds));
  }

  /**
   * The block at a location.
   *
   * @param location the location
   * @return the region, or empty if the location has no loaded world
   */
  public Optional<DomainRegion<MinecraftWorld>> block(Location location) {
    World world = location.getWorld();
    return world == null
        ? Optional.empty()
        : Optional.of(new CellRegion<>(PaperConversions.cell(location), worlds.wrap(world)));
  }

  /**
   * Everywhere in a world that is open to the sky; see {@link SurfaceRegion}.
   *
   * @param world the world
   * @return a factory making the region for each search, which it reads blocks from
   */
  public RegionFactory surface(World world) {
    return SurfaceRegion.anywhere(worlds.wrap(world));
  }

  /**
   * Wherever one column of a world is open to the sky; see {@link SurfaceRegion}.
   *
   * @param world the world
   * @param x the column's block x-coordinate
   * @param z the column's block z-coordinate
   * @return a factory making the region for each search, which it reads blocks from
   */
  public RegionFactory surface(World world, int x, int z) {
    return SurfaceRegion.column(worlds.wrap(world), x, z);
  }
}
