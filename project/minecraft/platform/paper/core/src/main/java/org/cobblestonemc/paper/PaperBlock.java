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
  private final Material material;

  PaperBlock(BlockData data) {
    this.data = data;
    this.material = data.getMaterial();
  }

  @Override
  public boolean isPassable() {
    return material.isAir()
        || walkThrough(material)
        || (!material.isSolid() && !isWater() && !isLava());
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

  @Override
  public boolean isSolidTop() {
    // The same materials that do not stop a body do not hold one up either: nobody stands on top
    // of a torch, and a pressure plate is footing for the cell it occupies, not the one above it.
    return material.isSolid() && !walkThrough(material);
  }

  @Override
  public boolean isHalfHeight() {
    return Tag.SLABS.isTagged(material) || material == Material.SNOW;
  }

  @Override
  public boolean isWater() {
    return material == Material.WATER;
  }

  @Override
  public boolean isLava() {
    return material == Material.LAVA;
  }

  @Override
  public boolean isClimbable() {
    return Tag.CLIMBABLE.isTagged(material);
  }

  @Override
  public boolean isScaffolding() {
    return material == Material.SCAFFOLDING;
  }

  @Override
  public boolean isDangerous() {
    return DANGEROUS.contains(material);
  }

  @Override
  public double damagePerSecond() {
    if (material == Material.LAVA) {
      return 20.0;
    }
    return isDangerous() ? 4.0 : 0.0;
  }

  @Override
  public double breakTimeSeconds() {
    float hardness = material.getHardness();
    if (hardness < 0.0f) {
      return Double.POSITIVE_INFINITY; // bedrock, barriers, etc.
    }
    return Math.max(hardness * 1.5, 0.05);
  }

  @Override
  public boolean supportsBoat() {
    return BOAT_SURFACES.contains(material);
  }

  @Override
  public double speedFactor() {
    if (ICE.contains(material)) {
      return 1.4;
    }
    return switch (material) {
      case SOUL_SAND, SOUL_SOIL, HONEY_BLOCK -> 0.4;
      case COBWEB -> 0.15;
      default -> 1.0;
    };
  }

  @Override
  public boolean isDoor() {
    return Tag.DOORS.isTagged(material)
        || Tag.TRAPDOORS.isTagged(material)
        || Tag.FENCE_GATES.isTagged(material);
  }

  @Override
  public boolean isTrapdoor() {
    return Tag.TRAPDOORS.isTagged(material);
  }

  @Override
  public boolean isOpen() {
    return data instanceof Openable openable && openable.isOpen();
  }

  @Override
  public boolean opensByHand() {
    return Tag.WOODEN_DOORS.isTagged(material)
        || Tag.WOODEN_TRAPDOORS.isTagged(material)
        || Tag.FENCE_GATES.isTagged(material);
  }

  @Override
  public boolean isPressurePlate() {
    return Tag.PRESSURE_PLATES.isTagged(material);
  }
}
