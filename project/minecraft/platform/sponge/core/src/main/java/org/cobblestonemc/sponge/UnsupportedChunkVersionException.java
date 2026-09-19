/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.io.IOException;

/**
 * A saved chunk that exists, but was written by a Minecraft too old for Cobblestone to decode.
 *
 * <p>Cobblestone has no data fixers of its own, so a chunk saved before 1.18 and never re-saved
 * since cannot be read off disk. That is not the same as the chunk being absent — it is there, and
 * the server can upgrade it by loading it — so it is raised as a failed read, which sends the
 * caller down the ticket path, rather than answered as "nothing saved", which would wall it off.
 */
final class UnsupportedChunkVersionException extends IOException {

  private static final long serialVersionUID = 1L;

  private final int dataVersion;

  UnsupportedChunkVersionException(int dataVersion) {
    super("Chunk was saved with data version " + dataVersion + ", before the 1.18 layout");
    this.dataVersion = dataVersion;
  }

  /**
   * Returns the data version the chunk was saved with.
   *
   * @return the data version
   */
  int dataVersion() {
    return dataVersion;
  }
}
