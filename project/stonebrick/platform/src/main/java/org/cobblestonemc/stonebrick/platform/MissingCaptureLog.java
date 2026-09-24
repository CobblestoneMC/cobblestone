/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import java.util.concurrent.atomic.AtomicLong;
import org.cobblestonemc.stonebrick.format.Sbc;

/**
 * Records every read that fell outside the capture, so a degenerate benchmark run is caught instead
 * of reported.
 *
 * <p><b>This is the harness's most important safety property.</b> Uncaptured terrain reads as
 * impassable, which is indistinguishable to a search from a wall — so a capture that is slightly
 * too small produces a run that completes, succeeds, and reports a number that means nothing.
 * Nothing downstream can tell. The run has to be failed by the only component that knows the
 * difference between "there is rock here" and "we never looked here".
 *
 * <p><b>Why a log rather than an exception.</b> Throwing from inside a mode's block lookup would
 * unwind through the search's cooperative pump and its {@code CompletableFuture} plumbing, where it
 * would most likely surface as a generic solve failure with the coordinates lost. Accumulating
 * instead costs a few atomics on a path that is already doing IO, lets the run finish, and — the
 * real payoff — collects the <em>whole</em> region that was missing rather than the first block of
 * it, so the remedy is one command rather than a dozen rounds of enlarge-and-retry.
 *
 * <p>Thread-safe: reads happen on every worker thread a solve touches.
 */
public final class MissingCaptureLog {

  private final AtomicLong reads = new AtomicLong();
  private final AtomicLong minChunkX = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong minChunkZ = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong maxChunkX = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong maxChunkZ = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong minY = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong maxY = new AtomicLong(Long.MIN_VALUE);

  private volatile String worldKey = "";

  /**
   * Records a read of a chunk column the capture does not contain.
   *
   * @param world the world key
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   */
  public void missingColumn(String world, int chunkX, int chunkZ) {
    record(world, chunkX, chunkZ, Long.MAX_VALUE, Long.MIN_VALUE);
  }

  /**
   * Records a read of a cube a captured column does not contain — the partial-vertical-capture
   * case, where the surface band was captured and the search went looking below it.
   *
   * @param world the world key
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param y the world Y read
   */
  public void missingCube(String world, int chunkX, int chunkZ, int y) {
    record(world, chunkX, chunkZ, y, y);
  }

  private void record(String world, int chunkX, int chunkZ, long lowY, long highY) {
    reads.incrementAndGet();
    worldKey = world;
    minChunkX.accumulateAndGet(chunkX, Math::min);
    maxChunkX.accumulateAndGet(chunkX, Math::max);
    minChunkZ.accumulateAndGet(chunkZ, Math::min);
    maxChunkZ.accumulateAndGet(chunkZ, Math::max);
    if (lowY != Long.MAX_VALUE) {
      minY.accumulateAndGet(lowY, Math::min);
      maxY.accumulateAndGet(highY, Math::max);
    }
  }

  /**
   * Returns whether anything was read outside the capture.
   *
   * @return {@code true} if the run touched uncaptured terrain
   */
  public boolean isEmpty() {
    return reads.get() == 0;
  }

  /**
   * Returns how many reads fell outside the capture.
   *
   * @return the count
   */
  public long reads() {
    return reads.get();
  }

  /**
   * Returns a report naming what was missing and the command that would capture it.
   *
   * @param captureName the capture the run was reading
   * @return the report, or an empty string if nothing was missing
   */
  public String report(String captureName) {
    if (isEmpty()) {
      return "";
    }
    // One chunk of margin: a search that reached the edge was heading somewhere, and capturing
    // exactly what it asked for tends to move the boundary rather than remove it.
    long x1 = minChunkX.get() - 1;
    long z1 = minChunkZ.get() - 1;
    long x2 = maxChunkX.get() + 1;
    long z2 = maxChunkZ.get() + 1;

    StringBuilder text = new StringBuilder();
    text.append(
            "The run read %,d block%s that this capture does not contain, so its result is%n"
                .formatted(reads.get(), reads.get() == 1 ? "" : "s"))
        .append("degenerate: uncaptured terrain is indistinguishable from a wall.%n".formatted())
        .append("  world  %s%n".formatted(worldKey))
        .append("  chunks %d,%d .. %d,%d%n".formatted(x1, z1, x2, z2));

    String vertical = "";
    if (minY.get() != Long.MAX_VALUE) {
      text.append("  Y      %d .. %d%n".formatted(minY.get(), maxY.get()));
      // Read below a captured band: the fix is a deeper band, not a wider rectangle.
      vertical =
          " --y %d %d"
              .formatted(
                  Sbc.sectionFloor(Sbc.sectionOf((int) minY.get())),
                  Sbc.sectionFloor(Sbc.sectionOf((int) maxY.get())) + Sbc.CUBE_SIZE - 1);
    }
    text.append(
        "  /copier copy %d %d %d %d --name %s --world %s%s --overwrite%n"
            .formatted(x1, z1, x2, z2, captureName, worldKey, vertical));
    return text.toString();
  }
}
