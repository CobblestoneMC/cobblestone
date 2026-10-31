/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MiningClass;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.data.Key;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.data.value.Value;
import org.spongepowered.api.registry.RegistryTypes;
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

  /** One per block state: states are shared, and their answers never change. */
  private static final Map<BlockState, SpongeBlock> BY_STATE = new ConcurrentHashMap<>();

  private final BlockState state;
  private final boolean isPassable;
  private final boolean isSolidTop;
  private final boolean isHalfHeight;
  private final boolean isWater;
  private final boolean isLava;
  private final MiningClass miningClass;
  private final boolean isClimbable;
  private final boolean isScaffolding;
  private final boolean isDangerous;
  private final double damagePerSecond;
  private final double breakTimeSeconds;
  private final boolean supportsBoat;
  private final boolean boostsSoulSpeed;
  private final double speedFactor;
  private final boolean isDoor;
  private final boolean isTrapdoor;
  private final boolean isOpen;
  private final boolean opensByHand;
  private final boolean isPressurePlate;

  /**
   * Works out everything a block state says of itself, once.
   *
   * <p>Most of it comes from vanilla tags and Sponge's data keys, each a lookup: asked for every
   * cell of every section a search builds, they were most of what building one cost.
   */
  private SpongeBlock(BlockState state) {
    this.state = state;
    BlockType type = state.type();
    this.isPassable = isPassable(state, type);
    this.isSolidTop = isSolidTop(state, type);
    this.isHalfHeight = isHalfHeight(state, type);
    this.isWater = isWater(state, type);
    this.isLava = isLava(state, type);
    this.miningClass = miningClass(state, type);
    this.isClimbable = isClimbable(state, type);
    this.isScaffolding = isScaffolding(state, type);
    this.isDangerous = isDangerous(state, type);
    this.damagePerSecond = damagePerSecond(state, type);
    this.breakTimeSeconds = breakTimeSeconds(state, type);
    this.supportsBoat = supportsBoat(state, type);
    this.boostsSoulSpeed = boostsSoulSpeed(state, type);
    this.speedFactor = speedFactor(state, type);
    this.isDoor = isDoor(state, type);
    this.isTrapdoor = isTrapdoor(state, type);
    this.isOpen = isOpen(state, type);
    this.opensByHand = opensByHand(state, type);
    this.isPressurePlate = isPressurePlate(state, type);
  }

  /**
   * Returns the block for a state.
   *
   * @param state the state
   * @return its block, the same one every time
   */
  static SpongeBlock of(BlockState state) {
    SpongeBlock known = BY_STATE.get(state);
    return known != null ? known : BY_STATE.computeIfAbsent(state, SpongeBlock::new);
  }

  /** The backing snapshot block state, handed to integration break checkers. */
  BlockState state() {
    return state;
  }

  @Override
  public boolean isPassable() {
    return isPassable;
  }

  @Override
  public boolean isSolidTop() {
    return isSolidTop;
  }

  @Override
  public boolean isHalfHeight() {
    return isHalfHeight;
  }

  @Override
  public boolean isWater() {
    return isWater;
  }

  @Override
  public boolean isLava() {
    return isLava;
  }

  @Override
  public MiningClass miningClass() {
    return miningClass;
  }

  @Override
  public boolean isClimbable() {
    return isClimbable;
  }

  @Override
  public boolean isScaffolding() {
    return isScaffolding;
  }

  @Override
  public boolean isDangerous() {
    return isDangerous;
  }

  @Override
  public double damagePerSecond() {
    return damagePerSecond;
  }

  @Override
  public double breakTimeSeconds() {
    return breakTimeSeconds;
  }

  @Override
  public boolean supportsBoat() {
    return supportsBoat;
  }

  @Override
  public boolean boostsSoulSpeed() {
    return boostsSoulSpeed;
  }

  @Override
  public double speedFactor() {
    return speedFactor;
  }

  @Override
  public boolean isDoor() {
    return isDoor;
  }

  @Override
  public boolean isTrapdoor() {
    return isTrapdoor;
  }

  @Override
  public boolean isOpen() {
    return isOpen;
  }

  @Override
  public boolean opensByHand() {
    return opensByHand;
  }

  @Override
  public boolean isPressurePlate() {
    return isPressurePlate;
  }

  private static boolean flag(BlockState state, Key<Value<Boolean>> key) {
    return state.get(key).orElse(false);
  }

  private static boolean isPassable(BlockState state, BlockType type) {
    return AIR.contains(type)
        || (!flag(state, Keys.IS_SOLID) && !isWater(state, type) && !isLava(state, type));
  }

  private static boolean isSolidTop(BlockState state, BlockType type) {
    return flag(state, Keys.IS_SOLID);
  }

  private static boolean isHalfHeight(BlockState state, BlockType type) {
    return type.is(BlockTypeTags.SLABS) || type.equals(BlockTypes.SNOW.get());
  }

  private static boolean isWater(BlockState state, BlockType type) {
    return type.equals(BlockTypes.WATER.get());
  }

  private static boolean isLava(BlockState state, BlockType type) {
    return type.equals(BlockTypes.LAVA.get());
  }

  private static MiningClass miningClass(BlockState state, BlockType type) {
    return MiningClass.of(
        tool(type),
        type.is(BlockTypeTags.NEEDS_DIAMOND_TOOL),
        state.get(Keys.DESTROY_SPEED).orElse(0.0),
        type.key(RegistryTypes.BLOCK_TYPE).value());
  }

  /**
   * The tool the block's vanilla tags say breaks it fastest, of those {@link MiningClass} buckets
   * by: a hoe's blocks count as no tool's.
   */
  private static MiningClass.Tool tool(BlockType type) {
    if (type.is(BlockTypeTags.MINEABLE_PICKAXE)) {
      return MiningClass.Tool.PICKAXE;
    }
    if (type.is(BlockTypeTags.MINEABLE_SHOVEL)) {
      return MiningClass.Tool.SHOVEL;
    }
    if (type.is(BlockTypeTags.MINEABLE_AXE)) {
      return MiningClass.Tool.AXE;
    }
    return MiningClass.Tool.NONE;
  }

  private static boolean isClimbable(BlockState state, BlockType type) {
    return type.is(BlockTypeTags.CLIMBABLE);
  }

  private static boolean isScaffolding(BlockState state, BlockType type) {
    return type.equals(BlockTypes.SCAFFOLDING.get());
  }

  private static boolean isDangerous(BlockState state, BlockType type) {
    return DANGEROUS.contains(type);
  }

  private static double damagePerSecond(BlockState state, BlockType type) {
    if (isLava(state, type)) {
      return 20.0;
    }
    return isDangerous(state, type) ? 4.0 : 0.0;
  }

  private static double breakTimeSeconds(BlockState state, BlockType type) {
    double hardness = state.get(Keys.DESTROY_SPEED).orElse(Double.POSITIVE_INFINITY);
    if (hardness < 0.0) {
      return Double.POSITIVE_INFINITY; // bedrock, barriers, etc.
    }
    return Math.max(hardness * 1.5, 0.05);
  }

  private static boolean supportsBoat(BlockState state, BlockType type) {
    return BOAT_SURFACES.contains(type);
  }

  private static boolean boostsSoulSpeed(BlockState state, BlockType type) {
    return type.equals(BlockTypes.SOUL_SAND.get()) || type.equals(BlockTypes.SOUL_SOIL.get());
  }

  private static double speedFactor(BlockState state, BlockType type) {
    if (ICE.contains(type)) {
      return 1.4;
    }
    // Not soul soil: it only looks like soul sand. Soul Speed works on both.
    if (type.equals(BlockTypes.SOUL_SAND.get()) || type.equals(BlockTypes.HONEY_BLOCK.get())) {
      return 0.4;
    }
    if (type.equals(BlockTypes.COBWEB.get())) {
      return 0.15;
    }
    return 1.0;
  }

  private static boolean isDoor(BlockState state, BlockType type) {
    return type.is(BlockTypeTags.DOORS)
        || type.is(BlockTypeTags.TRAPDOORS)
        || type.is(BlockTypeTags.FENCE_GATES);
  }

  private static boolean isTrapdoor(BlockState state, BlockType type) {
    return type.is(BlockTypeTags.TRAPDOORS);
  }

  private static boolean isOpen(BlockState state, BlockType type) {
    return flag(state, Keys.IS_OPEN);
  }

  private static boolean opensByHand(BlockState state, BlockType type) {
    return type.is(BlockTypeTags.WOODEN_DOORS)
        || type.is(BlockTypeTags.WOODEN_TRAPDOORS)
        || type.is(BlockTypeTags.FENCE_GATES);
  }

  private static boolean isPressurePlate(BlockState state, BlockType type) {
    return type.is(BlockTypeTags.PRESSURE_PLATES);
  }
}
