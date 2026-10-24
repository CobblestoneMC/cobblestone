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
 * <p><b>Each bucket is named for a block typical of it,</b> not for every block in it: {@link
 * #DIRT} holds sand and gravel too, and {@link #SANDSTONE} holds netherrack.
 *
 * <p><b>Buckets follow what an agent would pay.</b> Netherrack takes a pickaxe like stone but
 * breaks four times faster, so it is a bucket of its own; deepslate takes twice as long as stone.
 * An ore goes with the rock it sits in: an ore of its own hardness would cut every vein out of the
 * stone around it.
 */
public enum MiningClass {

  /** Broken by hand about as fast as with anything: glass, leaves, wool, plants. */
  GLASS(0),

  /** Soft rock for a pickaxe: sandstone, and netherrack and its ores. */
  SANDSTONE(1),

  /** Rock for a pickaxe: stone, cobblestone, granite, basalt, and the ores in stone. */
  STONE(2),

  /** Hard rock for a pickaxe: deepslate and its ores, end stone. */
  DEEPSLATE(3),

  /** Breaks only with a diamond pickaxe or better: obsidian, crying obsidian, ancient debris. */
  OBSIDIAN(4),

  /** For a shovel: dirt, grass, sand, gravel, clay, soul sand. */
  DIRT(5),

  /** For an axe: logs, planks, and things made of them. */
  WOOD(6);

  /** The most ids there can be: captures store an id in three bits. */
  public static final int MAX_IDS = 8;

  private static final MiningClass[] BY_ID = new MiningClass[MAX_IDS];

  static {
    for (MiningClass klass : values()) {
      if (BY_ID[klass.id] != null) {
        throw new IllegalStateException("mining class id " + klass.id + " is used twice");
      }
      BY_ID[klass.id] = klass;
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
      case HOE -> GLASS;
      case NONE -> hardness < 1 ? GLASS : STONE;
      case PICKAXE -> pickaxe(hardness, key);
    };
  }

  /** A block for a pickaxe: an ore by the rock its id names, anything else by its hardness. */
  private static MiningClass pickaxe(double hardness, String key) {
    if (key.endsWith("_ore")) {
      if (key.startsWith("deepslate_")) {
        return DEEPSLATE;
      }
      if (key.startsWith("nether_")) {
        return SANDSTONE;
      }
      return STONE;
    }
    if (hardness < 1) {
      return SANDSTONE;
    }
    if (hardness < 3) {
      return STONE;
    }
    return DEEPSLATE;
  }
}
