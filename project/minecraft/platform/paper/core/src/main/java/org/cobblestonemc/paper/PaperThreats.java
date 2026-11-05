/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.SculkShrieker;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.cobblestonemc.minecraft.Threats;
import org.cobblestonemc.minecraft.api.CostWeights;

/** Takes the {@link Threats} snapshot a player's search steers by. Server thread only. */
final class PaperThreats {

  /**
   * How far around the player threats are looked for, in blocks. Farther than that, by the time the
   * player arrives a mob will have wandered off and a chunk may not be loaded to look in.
   */
  static final int RANGE = 128;

  private static final Set<Material> SCULK =
      Set.of(Material.SCULK_SHRIEKER, Material.SCULK_SENSOR, Material.CALIBRATED_SCULK_SENSOR);

  private PaperThreats() {}

  /**
   * Snapshots the hostile mobs and the warden-summoning sculk around a player, looking only for
   * what the weights price.
   *
   * @param player the player about to be navigated
   * @param weights what threats cost
   * @return the snapshot
   */
  static Threats around(Player player, CostWeights weights) {
    Threats.Builder threats = Threats.builder();
    Location at = player.getLocation();
    World world = at.getWorld();
    if (weights.dangerousEntity() > 0) {
      for (LivingEntity entity : world.getNearbyLivingEntities(at, RANGE)) {
        if (entity instanceof Enemy && !entity.isDead()) {
          threats.mob(PaperConversions.cell(entity.getLocation()));
        }
      }
    }
    if (weights.sculk() > 0) {
      int chunkX = at.getBlockX() >> 4;
      int chunkZ = at.getBlockZ() >> 4;
      int reach = RANGE >> 4;
      for (int x = chunkX - reach; x <= chunkX + reach; x++) {
        for (int z = chunkZ - reach; z <= chunkZ + reach; z++) {
          if (!world.isChunkLoaded(x, z)) {
            continue; // a snapshot must not load chunks; what it cannot see it does not price
          }
          // Filtered by block before any state is built, so a base full of chests costs nothing.
          for (BlockState state :
              world.getChunkAt(x, z).getTileEntities(b -> SCULK.contains(b.getType()), false)) {
            if (alarms(state)) {
              threats.sculk(PaperConversions.cell(state.getLocation()));
            }
          }
        }
      }
    }
    return threats.build();
  }

  /** A sensor always can; a shrieker only if it was generated able to summon a warden. */
  private static boolean alarms(BlockState state) {
    return !(state.getBlockData() instanceof SculkShrieker shrieker) || shrieker.isCanSummon();
  }
}
