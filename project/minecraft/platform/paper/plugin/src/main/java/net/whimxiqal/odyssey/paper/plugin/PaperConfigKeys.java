/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.List;
import net.whimxiqal.odyssey.minecraft.ChunkLoadPolicy;
import net.whimxiqal.odyssey.plugin.config.ConfigPlatform;

/**
 * Paper's contribution to the configuration: the platform profile for the settings whose wording or
 * defaults differ from Sponge.
 *
 * <p>Paper has no keys of its own yet. Its chunk loading goes through {@code getChunkAtAsync},
 * which reads a generated chunk without loading it into the world and takes "may I generate?" as a
 * parameter, so it needs neither a ticket budget nor any of the machinery Sponge does.
 */
final class PaperConfigKeys {

  private PaperConfigKeys() {}

  /** The platform profile for Paper, describing the settings that read differently here. */
  static ConfigPlatform platform() {
    return new ConfigPlatform(
        "Paper",
        ChunkLoadPolicy.ALLOW_LOAD,
        List.of(
            ChunkLoadPolicy.LOADED_ONLY,
            ChunkLoadPolicy.ALLOW_LOAD,
            ChunkLoadPolicy.ALLOW_LOAD_AND_GENERATE),
        """
        How far Odyssey may go to obtain a chunk a search wants to walk through.

          loaded_only
            Only chunks already in memory. Searches never load anything, and stop at the edge of
            what players are keeping loaded — cheap, but a search will often fail to find a route
            that plainly exists.

          allow_load
            Also read chunks that have already been generated. No new terrain is created.

          allow_load_and_generate
            Also generate terrain that does not exist yet. A long search into unexplored territory
            can generate a great deal of world, and it is permanent: your world files grow to
            match. Turn this on only if you want searches to path through land nobody has
            visited.""");
  }
}
