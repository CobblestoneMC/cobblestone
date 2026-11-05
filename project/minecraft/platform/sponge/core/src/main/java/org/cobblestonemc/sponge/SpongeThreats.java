/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import org.cobblestonemc.minecraft.Threats;
import org.cobblestonemc.minecraft.api.CostWeights;
import org.spongepowered.api.block.entity.BlockEntity;
import org.spongepowered.api.block.entity.BlockEntityType;
import org.spongepowered.api.block.entity.BlockEntityTypes;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.Hostile;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.state.BooleanStateProperties;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/** Takes the {@link Threats} snapshot a player's search steers by. Server thread only. */
final class SpongeThreats {

  /**
   * How far around the player threats are looked for, in blocks. Farther than that, by the time the
   * player arrives a mob will have wandered off and a chunk may not be loaded to look in.
   */
  static final int RANGE = 128;

  private SpongeThreats() {}

  /**
   * Snapshots the hostile mobs and the warden-summoning sculk around a player, looking only for
   * what the weights price.
   *
   * @param player the player about to be navigated
   * @param weights what threats cost
   * @return the snapshot
   */
  static Threats around(ServerPlayer player, CostWeights weights) {
    Threats.Builder threats = Threats.builder();
    ServerWorld world = player.world();
    if (weights.dangerousEntity() > 0) {
      for (Entity entity : world.nearbyEntities(player.position(), RANGE)) {
        if (entity instanceof Hostile && !entity.isRemoved()) {
          threats.mob(SpongeConversions.cell(entity.blockPosition()));
        }
      }
    }
    if (weights.sculk() > 0) {
      Vector3i at = player.blockPosition();
      // Only what is loaded: a snapshot must not load chunks, and what it cannot see it does not
      // price.
      for (BlockEntity blockEntity : world.blockEntities()) {
        Vector3i position = blockEntity.blockPosition();
        if (Math.abs(position.x() - at.x()) <= RANGE
            && Math.abs(position.z() - at.z()) <= RANGE
            && alarms(blockEntity)) {
          threats.sculk(SpongeConversions.cell(position));
        }
      }
    }
    return threats.build();
  }

  /** A sensor always can; a shrieker only if it was generated able to summon a warden. */
  private static boolean alarms(BlockEntity blockEntity) {
    BlockEntityType type = blockEntity.type();
    if (type.equals(BlockEntityTypes.SCULK_SENSOR.get())
        || type.equals(BlockEntityTypes.CALIBRATED_SCULK_SENSOR.get())) {
      return true;
    }
    return type.equals(BlockEntityTypes.SCULK_SHRIEKER.get())
        && blockEntity
            .block()
            .stateProperty(BooleanStateProperties.property_CAN_SUMMON())
            .orElse(false);
  }
}
