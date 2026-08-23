/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import java.util.List;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.plugin.config.Codec;
import org.cobblestonemc.plugin.config.ConfigKey;
import org.cobblestonemc.plugin.config.ConfigManager;
import org.cobblestonemc.plugin.config.ConfigPlatform;

/**
 * Sponge's contribution to the configuration: the platform profile for the settings whose wording
 * or defaults differ from Paper, plus the chunk-loading keys that exist only here.
 *
 * <p>Sponge has no asynchronous chunk API and no way to read a chunk that is not loaded, so
 * Cobblestone force-loads chunks with a loading ticket and releases it as soon as the chunk has
 * been copied. That mechanism has a cost Paper's does not, which is why it has a budget setting
 * Paper has no use for.
 */
final class SpongeConfigKeys {

  /**
   * The most chunk-loading tickets Cobblestone may hold at once. Mutable — the budget is consulted
   * per request.
   */
  final ConfigKey<Integer> chunksMaxLoadRequests;

  SpongeConfigKeys(ConfigManager manager) {
    this.chunksMaxLoadRequests =
        manager
            .key("search.chunks.max_load_requests", 256, Codec.ofInt())
            .comment(
                """
                The most chunks Cobblestone may hold loading tickets for at once. Cobblestone needs a chunk
                only long enough to copy it, and releases the ticket immediately afterwards, so
                this is a limit on how much chunk loading it can ask for at any one moment — not on
                how much ground a search may cover.

                Searches read ahead around the frontier, so a single step can want a couple of
                dozen chunks; values below about 64 will mostly disable that read-ahead and make
                searches slower rather than lighter. Lower it if chunk loading is visibly costing
                you tick time.""")
            .mutable()
            .register();
  }

  /** The platform profile for Sponge, describing the settings that read differently here. */
  static ConfigPlatform platform() {
    return new ConfigPlatform(
        "Sponge",
        ChunkLoadPolicy.ALLOW_LOAD,
        List.of(
            ChunkLoadPolicy.LOADED_ONLY,
            ChunkLoadPolicy.ALLOW_LOAD,
            ChunkLoadPolicy.ALLOW_LOAD_AND_GENERATE),
        """
        How far Cobblestone may go to obtain a chunk a search wants to walk through.

        Sponge cannot read a chunk that is not loaded, so anything beyond "loaded_only" means
        Cobblestone force-loads the chunk with a loading ticket, copies it, and releases the ticket
        straight away. See max_load_requests for how much of that may be in flight at once.

          loaded_only
            Only chunks already in memory. Searches never load anything, and stop at the edge of
            what players are keeping loaded — cheap, but a search will often fail to find a route
            that plainly exists.

          allow_load
            Also load chunks that have already been generated. Cobblestone checks that a chunk exists
            on disk before asking for it, so no new terrain is created.

          allow_load_and_generate
            Also let those tickets generate terrain that does not exist yet. This is as expensive
            as it sounds — a long search into unexplored territory can generate a great deal of
            world, and it is permanent: your world files grow to match. Turn this on only if you
            want searches to path through land nobody has visited.""");
  }
}
