/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.Domain;
import net.whimxiqal.odyssey.FutureOr;

/**
 * A Minecraft world — the single concrete {@link Domain} type for the Minecraft embedder. Dimensions
 * are distinguished by {@link #environment()}, never by subtyping (so the {@code D} generic stays a
 * single type across a search).
 *
 * <p>A world is also the block-access handle a mode uses: {@link #blockAt(Cell)} returns a
 * {@link FutureOr} that is immediate on a cache hit and pending on a miss (the platform's chunk
 * provider backs it). Equality/hash are by the world's namespaced {@link #key()}.
 */
public interface MinecraftWorld extends Domain {

  /**
   * Returns the world's namespaced key (e.g. {@code "minecraft:overworld"}).
   *
   * @return the world key
   */
  String key();

  /**
   * Returns the world's environment, for dimension-aware cost tuning.
   *
   * @return the environment
   */
  Environment environment();

  /**
   * Returns the block at {@code cell}, possibly pending on a chunk fetch. An unavailable cell (chunk
   * not loaded under the configured policy) resolves to an impassable "unknown" block.
   *
   * @param cell the cell
   * @return the block, immediate or pending
   */
  FutureOr<MinecraftBlock> blockAt(Cell cell);

  /** The vanilla dimension kinds, plus a catch-all for custom/modded dimensions. */
  enum Environment {
    OVERWORLD,
    NETHER,
    END,
    CUSTOM
  }
}
