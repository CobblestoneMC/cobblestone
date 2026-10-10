/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import org.bukkit.block.data.BlockData;
import org.cobblestonemc.minecraft.Direction;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.paper.PaperBlockBridge;
import org.cobblestonemc.stonebrick.format.BlockTraits;

/**
 * Turns a Bukkit block state into the traits a capture records for it, by asking Paper's real block
 * implementation.
 *
 * <p>This is the only place the copier reaches into Cobblestone's platform code, and it is the
 * reason the copier is allowed to (see {@link PaperBlockBridge}).
 */
final class TraitCapture {

  /**
   * {@link BlockTraits#DIRECTIONS} resolved to this build's {@link Direction} constants.
   *
   * <p>Resolved by <b>name</b>, once, rather than by ordinal. The trait table is a wire format read
   * back months later by a different module; binding its face order to whatever order someone
   * happens to have left an enum in would silently reinterpret every partial block the day a
   * constant moved. Resolving by name turns that from a wrong answer into a startup failure.
   */
  private static final Direction[] FACES = resolveFaces();

  private TraitCapture() {}

  private static Direction[] resolveFaces() {
    Direction[] faces = new Direction[BlockTraits.DIRECTIONS.length];
    for (int i = 0; i < faces.length; i++) {
      faces[i] = Direction.valueOf(BlockTraits.DIRECTIONS[i]);
    }
    return faces;
  }

  /**
   * Returns the traits Cobblestone's search would read from the given block state.
   *
   * @param data the block data
   * @return the traits
   */
  static BlockTraits of(BlockData data) {
    MinecraftBlock block = PaperBlockBridge.of(data);
    BlockTraits.Builder traits =
        BlockTraits.builder()
            .set(BlockTraits.PASSABLE, block.isPassable())
            .set(BlockTraits.SOLID_TOP, block.isSolidTop())
            .set(BlockTraits.HALF_HEIGHT, block.isHalfHeight())
            .set(BlockTraits.WATER, block.isWater())
            .set(BlockTraits.LAVA, block.isLava())
            .set(BlockTraits.CLIMBABLE, block.isClimbable())
            .set(BlockTraits.SCAFFOLDING, block.isScaffolding())
            .set(BlockTraits.DANGEROUS, block.isDangerous())
            .set(BlockTraits.SUPPORTS_BOAT, block.supportsBoat())
            .set(BlockTraits.DOOR, block.isDoor())
            .set(BlockTraits.TRAPDOOR, block.isTrapdoor())
            .set(BlockTraits.OPEN, block.isOpen())
            .set(BlockTraits.OPENS_BY_HAND, block.opensByHand())
            .set(BlockTraits.PRESSURE_PLATE, block.isPressurePlate())
            .breakTimeSeconds(block.breakTimeSeconds())
            .speedFactor(block.speedFactor())
            .damagePerSecond(block.damagePerSecond());
    for (int i = 0; i < FACES.length; i++) {
      traits.enterable(i, block.isEnterable(FACES[i]));
      traits.exitable(i, block.isExitable(FACES[i]));
    }
    return traits.build();
  }
}
