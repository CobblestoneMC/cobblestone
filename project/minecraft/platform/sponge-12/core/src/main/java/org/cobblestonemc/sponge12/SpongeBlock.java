/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12;

import java.util.Set;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.tag.BlockTypeTags;

/**
 * A {@link MinecraftBlock} backed by a Sponge {@link BlockState} from an archetype-volume snapshot
 * — the block-type→predicate table for Sponge.
 *
 * <p>Collision facts read Sponge's block data {@code Keys} ({@link Keys#IS_SOLID}, {@link
 * Keys#IS_PASSABLE}, {@link Keys#IS_OPEN}, {@link Keys#DESTROY_SPEED}); material-level traits use
 * vanilla block tags and a small hand-curated table of {@link BlockType}s.
 */
final class SpongeBlock implements MinecraftBlock {

  private static final Set<BlockType> AIR =
      Set.of(BlockTypes.AIR.get(), BlockTypes.CAVE_AIR.get(), BlockTypes.VOID_AIR.get());
  private static final Set<BlockType> DANGEROUS =
      Set.of(
          BlockTypes.LAVA.get(),
          BlockTypes.FIRE.get(),
          BlockTypes.SOUL_FIRE.get(),
          BlockTypes.MAGMA_BLOCK.get(),
          BlockTypes.CAMPFIRE.get(),
          BlockTypes.SOUL_CAMPFIRE.get(),
          BlockTypes.CACTUS.get(),
          BlockTypes.SWEET_BERRY_BUSH.get(),
          BlockTypes.WITHER_ROSE.get(),
          BlockTypes.POWDER_SNOW.get());
  private static final Set<BlockType> BOAT_SURFACES =
      Set.of(
          BlockTypes.WATER.get(),
          BlockTypes.ICE.get(),
          BlockTypes.PACKED_ICE.get(),
          BlockTypes.BLUE_ICE.get(),
          BlockTypes.FROSTED_ICE.get());
  private static final Set<BlockType> ICE =
      Set.of(
          BlockTypes.ICE.get(),
          BlockTypes.PACKED_ICE.get(),
          BlockTypes.BLUE_ICE.get(),
          BlockTypes.FROSTED_ICE.get());

  private final BlockState state;
  private final BlockType type;

  SpongeBlock(BlockState state) {
    this.state = state;
    this.type = state.type();
  }

  /** The backing snapshot block state, handed to integration break checkers. */
  BlockState state() {
    return state;
  }

  private boolean flag(
      org.spongepowered.api.data.Key<org.spongepowered.api.data.value.Value<Boolean>> key) {
    return state.get(key).orElse(false);
  }

  @Override
  public boolean isPassable() {
    return AIR.contains(type) || (!flag(Keys.IS_SOLID) && !isWater() && !isLava());
  }

  @Override
  public boolean isSolidTop() {
    return flag(Keys.IS_SOLID);
  }

  @Override
  public boolean isHalfHeight() {
    return type.is(BlockTypeTags.SLABS) || type.equals(BlockTypes.SNOW.get());
  }

  @Override
  public boolean isWater() {
    return type.equals(BlockTypes.WATER.get());
  }

  @Override
  public boolean isLava() {
    return type.equals(BlockTypes.LAVA.get());
  }

  @Override
  public boolean isClimbable() {
    return type.is(BlockTypeTags.CLIMBABLE);
  }

  @Override
  public boolean isScaffolding() {
    return type.equals(BlockTypes.SCAFFOLDING.get());
  }

  @Override
  public boolean isDangerous() {
    return DANGEROUS.contains(type);
  }

  @Override
  public double damagePerSecond() {
    if (isLava()) {
      return 20.0;
    }
    return isDangerous() ? 4.0 : 0.0;
  }

  @Override
  public double breakTimeSeconds() {
    double hardness = state.get(Keys.DESTROY_SPEED).orElse(Double.POSITIVE_INFINITY);
    if (hardness < 0.0) {
      return Double.POSITIVE_INFINITY; // bedrock, barriers, etc.
    }
    return Math.max(hardness * 1.5, 0.05);
  }

  @Override
  public boolean supportsBoat() {
    return BOAT_SURFACES.contains(type);
  }

  @Override
  public double speedFactor() {
    if (ICE.contains(type)) {
      return 1.4;
    }
    if (type.equals(BlockTypes.SOUL_SAND.get())
        || type.equals(BlockTypes.SOUL_SOIL.get())
        || type.equals(BlockTypes.HONEY_BLOCK.get())) {
      return 0.4;
    }
    if (type.equals(BlockTypes.COBWEB.get())) {
      return 0.15;
    }
    return 1.0;
  }

  @Override
  public boolean isDoor() {
    return type.is(BlockTypeTags.DOORS)
        || type.is(BlockTypeTags.TRAPDOORS)
        || type.is(BlockTypeTags.FENCE_GATES);
  }

  @Override
  public boolean isOpen() {
    return flag(Keys.IS_OPEN);
  }

  @Override
  public boolean opensByHand() {
    return type.is(BlockTypeTags.WOODEN_DOORS)
        || type.is(BlockTypeTags.WOODEN_TRAPDOORS)
        || type.is(BlockTypeTags.FENCE_GATES);
  }

  @Override
  public boolean isPressurePlate() {
    return type.is(BlockTypeTags.PRESSURE_PLATES);
  }
}
