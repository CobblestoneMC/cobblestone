/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

/**
 * How aggressively the chunk provider may materialize a chunk that isn't already in memory.
 *
 * <p>The constant names are the admin-facing vocabulary: they are what an operator writes for
 * {@code search.chunks.policy}, decoded case-insensitively. Keep the two in step — the config
 * template is generated from these names, so renaming a constant renames the setting.
 *
 * <p>Not every platform supports every value; each registers the subset it can honor (see {@code
 * ConfigPlatform}). A value outside that subset is rejected on load with a warning rather than
 * silently doing something else.
 */
public enum ChunkLoadPolicy {

  /** Only use chunks already loaded in memory; everything else is impassable. */
  LOADED_ONLY,

  /** Also read already-generated chunks from disk, but never generate new terrain. */
  ALLOW_LOAD,

  /** Allow terrain generation (can be laggy); opt-in. */
  ALLOW_LOAD_AND_GENERATE
}
