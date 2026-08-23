/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.towny;

import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.WorldCoord;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.World;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.paper.api.BoxWorldRegion;
import org.joml.Vector3i;

/**
 * Turns Towny's claimed chunks into Cobblestone {@link WorldRegion}s. A claimed chunk becomes a
 * {@code 16 × (world height) × 16} box; a town/plot destination is the union of its chunks, so the
 * search finds the nearest claimed block of it.
 *
 * <p>These are pure-geometry snapshots — they call Towny only while being built (which happens on
 * the search-initiating thread), never during the search itself, so {@code contains()} stays
 * lock-free.
 */
final class TownyRegions {

  private TownyRegions() {}

  /** The box region covering one claimed chunk. */
  static WorldRegion<World, Vector3i> chunk(World world, int chunkX, int chunkZ) {
    int minX = chunkX << 4;
    int minZ = chunkZ << 4;
    return BoxWorldRegion.of(
        new Location(world, minX, world.getMinHeight(), minZ),
        new Location(world, minX + 15, world.getMaxHeight() - 1, minZ + 15));
  }

  /** Box regions for every claimed chunk of the town whose block passes {@code include}. */
  static List<WorldRegion<World, Vector3i>> plots(Town town, Predicate<TownBlock> include) {
    List<WorldRegion<World, Vector3i>> regions = new ArrayList<>();
    for (TownBlock townBlock : town.getTownBlocks()) {
      if (!include.test(townBlock)) {
        continue;
      }
      WorldCoord coord = townBlock.getWorldCoord();
      World world = coord.getBukkitWorld();
      if (world == null) {
        continue; // the town's world is unloaded; skip
      }
      regions.add(chunk(world, coord.getCoord().getX(), coord.getCoord().getZ()));
    }
    return regions;
  }
}
