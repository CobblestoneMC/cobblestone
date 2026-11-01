/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MiningClass;

/**
 * A {@link MinecraftBlock} backed by a Bukkit {@link BlockData} from a chunk snapshot — the
 * material→predicate table for Paper.
 *
 * <p>Collision facts use {@link Material#isSolid()} as a coarse proxy (fine for the 1×1×1 model),
 * corrected by {@link #walkThrough} for the flat fittings it misreports; material-level traits
 * (danger, speed factor, climbable, boat support) use vanilla block tags and a small hand-curated
 * table; break time comes from vanilla hardness with the stone-tool assumption.
 */
final class PaperBlock implements MinecraftBlock {

  private static final Set<Material> DANGEROUS =
      Set.of(
          Material.LAVA,
          Material.FIRE,
          Material.SOUL_FIRE,
          Material.MAGMA_BLOCK,
          Material.CAMPFIRE,
          Material.SOUL_CAMPFIRE,
          Material.CACTUS,
          Material.SWEET_BERRY_BUSH,
          Material.WITHER_ROSE,
          Material.POWDER_SNOW);
  private static final Set<Material> BOAT_SURFACES =
      Set.of(
          Material.WATER,
          Material.ICE,
          Material.PACKED_ICE,
          Material.BLUE_ICE,
          Material.FROSTED_ICE);
  private static final Set<Material> ICE =
      Set.of(Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

  /** Non-colliding fittings without a tag of their own; see {@link #walkThrough}. */
  private static final Set<Material> FLAT_FURNITURE =
      Set.of(
          Material.LEVER,
          Material.TRIPWIRE,
          Material.TRIPWIRE_HOOK,
          Material.REDSTONE_WIRE,
          Material.TORCH,
          Material.WALL_TORCH,
          Material.SOUL_TORCH,
          Material.SOUL_WALL_TORCH,
          Material.REDSTONE_TORCH,
          Material.REDSTONE_WALL_TORCH);

  private final BlockData data;
  private final Traits traits;

  PaperBlock(BlockData data) {
    this.data = data;
    this.traits = Traits.of(data.getMaterial());
  }

  @Override
  public boolean isPassable() {
    return traits.isPassable;
  }

  @Override
  public boolean isSolidTop() {
    return traits.isSolidTop;
  }

  @Override
  public boolean isHalfHeight() {
    return traits.isHalfHeight;
  }

  @Override
  public boolean isWater() {
    return traits.isWater;
  }

  @Override
  public boolean isLava() {
    return traits.isLava;
  }

  @Override
  public boolean isClimbable() {
    return traits.isClimbable;
  }

  @Override
  public boolean isScaffolding() {
    return traits.isScaffolding;
  }

  @Override
  public boolean isDangerous() {
    return traits.isDangerous;
  }

  @Override
  public double damagePerSecond() {
    return traits.damagePerSecond;
  }

  @Override
  public double breakTimeSeconds() {
    return traits.breakTimeSeconds;
  }

  @Override
  public MiningClass miningClass() {
    return traits.miningClass;
  }

  @Override
  public boolean supportsBoat() {
    return traits.supportsBoat;
  }

  @Override
  public boolean boostsSoulSpeed() {
    return traits.boostsSoulSpeed;
  }

  @Override
  public double speedFactor() {
    return traits.speedFactor;
  }

  @Override
  public boolean isDoor() {
    return traits.isDoor;
  }

  @Override
  public boolean isTrapdoor() {
    return traits.isTrapdoor;
  }

  @Override
  public boolean opensByHand() {
    return traits.opensByHand;
  }

  @Override
  public boolean isPressurePlate() {
    return traits.isPressurePlate;
  }

  @Override
  public boolean isOpen() {
    return data instanceof Openable openable && openable.isOpen();
  }

  /**
   * Whether a material's answers depend on its live block state, and its block has to be read
   * whole: only an openable's do.
   *
   * @param material the material
   * @return {@code true} if they do
   */
  static boolean isStateful(Material material) {
    return Traits.of(material).stateful;
  }

  /**
   * Everything a material says of itself, worked out once.
   *
   * <p>Most of it comes from vanilla tags, and asking a tag is a lookup through the server's tag
   * registry: asked for every cell of every section a search builds, it was most of what building
   * one cost. A material's answers never change while the server runs, so they are asked once.
   *
   * <p>Unsynchronized, as {@link PaperBlocks}' instances are: two threads racing on a material each
   * build the same answers, and the fields are final.
   */
  private static final class Traits {

    private static final Traits[] BY_MATERIAL = new Traits[Material.values().length];

    private final boolean stateful;
    private final boolean isPassable;
    private final boolean isSolidTop;
    private final boolean isHalfHeight;
    private final boolean isWater;
    private final boolean isLava;
    private final boolean isClimbable;
    private final boolean isScaffolding;
    private final boolean isDangerous;
    private final double damagePerSecond;
    private final double breakTimeSeconds;
    private final MiningClass miningClass;
    private final boolean supportsBoat;
    private final boolean boostsSoulSpeed;
    private final double speedFactor;
    private final boolean isDoor;
    private final boolean isTrapdoor;
    private final boolean opensByHand;
    private final boolean isPressurePlate;

    private Traits(Material material) {
      this.stateful = isDoor(material);
      this.isPassable = PaperBlock.isPassable(material);
      this.isSolidTop = PaperBlock.isSolidTop(material);
      this.isHalfHeight = PaperBlock.isHalfHeight(material);
      this.isWater = PaperBlock.isWater(material);
      this.isLava = PaperBlock.isLava(material);
      this.isClimbable = PaperBlock.isClimbable(material);
      this.isScaffolding = PaperBlock.isScaffolding(material);
      this.isDangerous = PaperBlock.isDangerous(material);
      this.damagePerSecond = PaperBlock.damagePerSecond(material);
      this.breakTimeSeconds = PaperBlock.breakTimeSeconds(material);
      this.miningClass = PaperBlock.miningClass(material);
      this.supportsBoat = PaperBlock.supportsBoat(material);
      this.boostsSoulSpeed = PaperBlock.boostsSoulSpeed(material);
      this.speedFactor = PaperBlock.speedFactor(material);
      this.isDoor = PaperBlock.isDoor(material);
      this.isTrapdoor = PaperBlock.isTrapdoor(material);
      this.opensByHand = PaperBlock.opensByHand(material);
      this.isPressurePlate = PaperBlock.isPressurePlate(material);
    }

    static Traits of(Material material) {
      Traits known = BY_MATERIAL[material.ordinal()];
      if (known == null) {
        known = new Traits(material);
        BY_MATERIAL[material.ordinal()] = known;
      }
      return known;
    }
  }

  private static boolean isPassable(Material material) {
    return material.isAir()
        || walkThrough(material)
        || (!material.isSolid() && !isWater(material) && !isLava(material));
  }

  /**
   * Blocks a body walks straight through that {@link Material#isSolid()} nevertheless reports as
   * solid.
   *
   * <p>{@code isSolid()} answers "can this be built upon", not "does this stop movement", and the
   * two part company for the flat furniture of a redstone build. A pressure plate is the one that
   * matters: laid in a doorway, treating it as solid walls the doorway off, and a house with a
   * plate at its threshold has no way in.
   */
  private static boolean walkThrough(Material material) {
    return Tag.PRESSURE_PLATES.isTagged(material)
        || Tag.BUTTONS.isTagged(material)
        || Tag.RAILS.isTagged(material)
        || FLAT_FURNITURE.contains(material);
  }

  private static boolean isSolidTop(Material material) {
    // The same materials that do not stop a body do not hold one up either: nobody stands on top
    // of a torch, and a pressure plate is footing for the cell it occupies, not the one above it.
    return material.isSolid() && !walkThrough(material);
  }

  private static boolean isHalfHeight(Material material) {
    return Tag.SLABS.isTagged(material) || material == Material.SNOW;
  }

  private static boolean isWater(Material material) {
    return material == Material.WATER;
  }

  private static boolean isLava(Material material) {
    return material == Material.LAVA;
  }

  private static boolean isClimbable(Material material) {
    return Tag.CLIMBABLE.isTagged(material);
  }

  private static boolean isScaffolding(Material material) {
    return material == Material.SCAFFOLDING;
  }

  private static boolean isDangerous(Material material) {
    return DANGEROUS.contains(material);
  }

  private static double damagePerSecond(Material material) {
    if (material == Material.LAVA) {
      return 20.0;
    }
    return isDangerous(material) ? 4.0 : 0.0;
  }

  private static double breakTimeSeconds(Material material) {
    float hardness = material.getHardness();
    if (hardness < 0.0f) {
      return Double.POSITIVE_INFINITY; // bedrock, barriers, etc.
    }
    return Math.max(hardness * 1.5, 0.05);
  }

  private static MiningClass miningClass(Material material) {
    return MiningClass.of(
        tool(material),
        Tag.NEEDS_DIAMOND_TOOL.isTagged(material),
        material.getHardness(),
        material.getKey().getKey());
  }

  /**
   * The tool the block's vanilla tags say breaks it fastest, of those {@link MiningClass} buckets
   * by: a hoe's blocks count as no tool's.
   */
  private static MiningClass.Tool tool(Material material) {
    if (Tag.MINEABLE_PICKAXE.isTagged(material)) {
      return MiningClass.Tool.PICKAXE;
    }
    if (Tag.MINEABLE_SHOVEL.isTagged(material)) {
      return MiningClass.Tool.SHOVEL;
    }
    if (Tag.MINEABLE_AXE.isTagged(material)) {
      return MiningClass.Tool.AXE;
    }
    return MiningClass.Tool.NONE;
  }

  private static boolean supportsBoat(Material material) {
    return BOAT_SURFACES.contains(material);
  }

  private static boolean boostsSoulSpeed(Material material) {
    return material == Material.SOUL_SAND || material == Material.SOUL_SOIL;
  }

  private static double speedFactor(Material material) {
    if (ICE.contains(material)) {
      return 1.4;
    }
    return switch (material) {
      // Not soul soil: it only looks like soul sand. Soul Speed works on both.
      case SOUL_SAND, HONEY_BLOCK -> 0.4;
      case COBWEB -> 0.15;
      default -> 1.0;
    };
  }

  private static boolean isDoor(Material material) {
    return Tag.DOORS.isTagged(material)
        || Tag.TRAPDOORS.isTagged(material)
        || Tag.FENCE_GATES.isTagged(material);
  }

  private static boolean isTrapdoor(Material material) {
    return Tag.TRAPDOORS.isTagged(material);
  }

  private static boolean opensByHand(Material material) {
    return Tag.WOODEN_DOORS.isTagged(material)
        || Tag.WOODEN_TRAPDOORS.isTagged(material)
        || Tag.FENCE_GATES.isTagged(material);
  }

  private static boolean isPressurePlate(Material material) {
    return Tag.PRESSURE_PLATES.isTagged(material);
  }
}
