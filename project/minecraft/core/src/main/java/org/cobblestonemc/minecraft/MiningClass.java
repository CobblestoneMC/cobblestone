/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

/**
 * A bucket of breakable blocks that one tool breaks at much the same speed.
 *
 * <p>A tag, not a cost. What breaking a block of a bucket costs depends on who breaks it, with what
 * pickaxe, shovel or axe, and is worked out by whoever prices the agent. The bucket only says which
 * tool helps and roughly how hard the blocks in it are, so that terrain can be summarized without
 * storing a number that is only true for one agent.
 *
 * <p><b>Buckets follow what an agent would pay.</b> Netherrack takes a pickaxe like stone but
 * breaks four times faster, so it is a bucket of its own; deepslate takes twice as long as stone.
 * An ore goes with the rock it sits in: an ore of its own hardness would cut every vein out of the
 * stone around it.
 */
public enum MiningClass {

  /** Broken by hand about as fast as with anything: leaves, glass, wool, plants. */
  SOFT,

  /** Soft rock for a pickaxe: netherrack and the ores in it, sandstone. */
  SOFT_ROCK,

  /** Rock for a pickaxe: stone, cobblestone, granite, basalt, and the ores in stone. */
  ROCK,

  /** Hard rock for a pickaxe: deepslate and its ores, end stone. */
  HARD_ROCK,

  /** Breaks only with a diamond pickaxe or better: obsidian, crying obsidian, ancient debris. */
  OBSIDIAN,

  /** For a shovel: dirt, grass, sand, gravel, clay, soul sand. */
  DIRT,

  /** For an axe: logs, planks, and things made of them. */
  WOOD;

  /** Every class, cached; {@code values()} allocates. */
  public static final MiningClass[] ALL = values();

  /** The tool a block's vanilla tags say breaks it fastest. */
  public enum Tool {
    PICKAXE,
    SHOVEL,
    AXE,
    HOE,
    NONE
  }

  /**
   * Buckets a breakable block from what its platform knows about it.
   *
   * <p>The one rule every platform buckets by, so that Paper, Sponge and a capture of either agree.
   *
   * @param tool the tool its tags name
   * @param needsDiamond whether it needs a diamond tool to drop anything
   * @param hardness its hardness, as Minecraft defines it
   * @param key its block id without a namespace, such as {@code deepslate_iron_ore}; an ore, whose
   *     id ends in {@code _ore}, goes with the rock its id names
   * @return the bucket
   */
  public static MiningClass of(Tool tool, boolean needsDiamond, double hardness, String key) {
    if (needsDiamond) {
      return OBSIDIAN;
    }
    return switch (tool) {
      case SHOVEL -> DIRT;
      case AXE -> WOOD;
      case HOE -> SOFT;
      case NONE -> hardness < 1 ? SOFT : ROCK;
      case PICKAXE -> {
        if (key.endsWith("_ore")) {
          yield key.startsWith("deepslate_")
              ? HARD_ROCK
              : key.startsWith("nether_") ? SOFT_ROCK : ROCK;
        }
        yield hardness < 1 ? SOFT_ROCK : hardness < 3 ? ROCK : HARD_ROCK;
      }
    };
  }
}
