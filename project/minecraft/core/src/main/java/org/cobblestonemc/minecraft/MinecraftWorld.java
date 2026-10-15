/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import org.cobblestonemc.Domain;

/**
 * A Minecraft world — the single concrete {@link Domain} type for the Minecraft embedder.
 * Dimensions are distinguished by {@link #environment()}, never by subtyping (so the {@code D}
 * generic stays a single type across a search).
 *
 * <p>A world only names a place: its blocks are read through a search's {@link ChunkSource}, never
 * through the world itself. Equality/hash are by the world's namespaced {@link #key()}.
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

  /** The vanilla dimension kinds, plus a catch-all for custom/modded dimensions. */
  enum Environment {
    OVERWORLD,
    NETHER,
    END,
    CUSTOM
  }
}
