/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

/**
 * How aggressively the chunk provider may materialize a chunk that isn't already in memory.
 */
public enum ChunkLoadPolicy {

  /** Only use chunks already loaded in memory; everything else is impassable. */
  LOADED_ONLY,

  /** Also read already-generated chunks from disk, but never generate new terrain. */
  LOAD_FROM_DISK,

  /** Allow terrain generation (can be laggy); opt-in. */
  GENERATE
}
