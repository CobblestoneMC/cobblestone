/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import org.jetbrains.annotations.Nullable;

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
 *
 * <p><b>Known limitation: blocks no tool we bucket by helps.</b> A block with no pickaxe, shovel or
 * axe tag is soft below hardness 1 and otherwise banded as rock by its hardness, as if a pickaxe
 * helped. While costs know no tools that prices it right, but once a pickaxe makes rock faster it
 * will under-price these. In the benchmark corpus they are few: nether and warped wart blocks and
 * shroomlight, which a hoe breaks fastest, and vaults, trial spawners and reinforced deepslate.
 * Hoes are left out on purpose, since players rarely carry one; a bucket for hoe blocks (leaves,
 * wart blocks, moss, sculk) is a later improvement.
 */
public enum MiningClass {

  /** Broken by hand about as fast as with anything: leaves, glass, wool, plants. */
  SOFT(0),

  /** Soft rock for a pickaxe: netherrack and the ores in it, sandstone. */
  SOFT_ROCK(1),

  /** Rock for a pickaxe: stone, cobblestone, granite, basalt, and the ores in stone. */
  ROCK(2),

  /** Hard rock for a pickaxe: deepslate and its ores, end stone. */
  HARD_ROCK(3),

  /**
   * Very hard rock, which breaks only with a diamond pickaxe or better: obsidian, crying obsidian,
   * ancient debris.
   */
  VERY_HARD_ROCK(4),

  /** Loose ground, for a shovel: dirt, grass, sand, gravel, clay, soul sand. */
  LOOSE(5),

  /** Anything wooden, for an axe: logs, planks, and things made of them. */
  WOODEN(6);

  /** The most ids there can be: captures store an id in four bits, half of them still free. */
  public static final int MAX_IDS = 16;

  private static final MiningClass[] BY_ID = new MiningClass[MAX_IDS];

  static {
    for (MiningClass clazz : values()) {
      if (BY_ID[clazz.id] != null) {
        throw new IllegalStateException("mining class id " + clazz.id + " is used twice");
      }
      BY_ID[clazz.id] = clazz;
    }
  }

  private final int id;

  /**
   * @param id the class's stored number: never reused, so a removed class's number stays retired
   */
  MiningClass(int id) {
    this.id = id;
  }

  /**
   * Returns the number this class is stored as, in captures. Stable across reordering, unlike an
   * ordinal.
   *
   * @return the id, below {@link #MAX_IDS}
   */
  public int id() {
    return id;
  }

  /**
   * Returns the class stored as a number.
   *
   * @param id the stored number
   * @return the class, or {@code null} if no class has that number
   */
  public static @Nullable MiningClass byId(int id) {
    return id >= 0 && id < MAX_IDS ? BY_ID[id] : null;
  }

  /** The tool a block's vanilla tags say breaks it fastest. */
  public enum Tool {
    PICKAXE,
    SHOVEL,
    AXE,
    NONE
  }

  /**
   * Buckets a breakable block from what its platform knows about it.
   *
   * <p>The one rule every platform buckets by, so that Paper, Sponge and a capture of either agree.
   *
   * @param tool the tool its tags name, of those a player is likely to carry
   * @param needsDiamond whether it needs a diamond tool to drop anything
   * @param hardness its hardness, as Minecraft defines it
   * @param key its block id without a namespace, such as {@code deepslate_iron_ore}; an ore, whose
   *     id ends in {@code _ore}, goes with the rock its id names
   * @return the bucket
   */
  public static MiningClass of(Tool tool, boolean needsDiamond, double hardness, String key) {
    if (needsDiamond) {
      return VERY_HARD_ROCK;
    }
    return switch (tool) {
      case SHOVEL -> LOOSE;
      case AXE -> WOODEN;
      case NONE -> hardness < 1 ? SOFT : rock(hardness);
      case PICKAXE -> pickaxe(hardness, key);
    };
  }

  /** A block for a pickaxe: an ore by the rock its id names, anything else by its hardness. */
  private static MiningClass pickaxe(double hardness, String key) {
    if (key.endsWith("_ore")) {
      if (key.startsWith("deepslate_")) {
        return HARD_ROCK;
      }
      if (key.startsWith("nether_")) {
        return SOFT_ROCK;
      }
      return ROCK;
    }
    return hardness < 1 ? SOFT_ROCK : rock(hardness);
  }

  /**
   * Rock of a hardness of at least 1, banded: stone, deepslate, and from obsidian's hardness of 50
   * up, very hard rock.
   */
  private static MiningClass rock(double hardness) {
    if (hardness < 3) {
      return ROCK;
    }
    if (hardness < 50) {
      return HARD_ROCK;
    }
    return VERY_HARD_ROCK;
  }
}
