/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

/**
 * What the simulated disk did during a run.
 *
 * <p>A snapshot rather than a live view of the IO model, and a record rather than the model itself:
 * a benchmark wants the counters, not the machinery that produced them, and handing out the engine
 * would let a caller perturb the thing it is measuring.
 *
 * @param reads how many chunk reads were charged
 * @param coldReads how many of those were a chunk's first read this run
 * @param totalDelayMicros the total modelled delay charged, in microseconds
 */
public record IoStats(long reads, long coldReads, long totalDelayMicros) {

  /** Returns how many reads were served warm. */
  public long warmReads() {
    return reads - coldReads;
  }
}
