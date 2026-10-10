/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * What one scenario cost, split by whether the number can be trusted to repeat.
 *
 * <p><b>The split is the important part.</b> Under the deterministic scheduler and a virtual clock,
 * node counts, chunk reads and path cost are exact functions of the input: an unchanged algorithm
 * reproduces them bit for bit, so a difference is a real difference and can gate a build. Wall time
 * is not — it moves by two or three times between a developer's laptop and a CI runner, for reasons
 * nobody can act on. Gating on it produces a test that fails randomly and gets disabled, and then
 * nothing is gating anything.
 *
 * <p>So wall time is recorded, trended and shown in every comparison, and never fails a build. What
 * takes its place as the speed metric is {@code nodesExpanded} and {@code virtualMillis} — the
 * first is what the search actually did, the second is what it would have taken on the modelled
 * disk.
 *
 * @param scenario the scenario's id
 * @param outcome {@code success}, {@code no_route}, {@code timed_out}, {@code limit_exceeded} or
 *     {@code error}
 * @param pathCost the algorithm cost of the path found, or {@code 0} if none
 * @param pathTime the player-facing duration of that path, or {@code 0} if none
 * @param pathSteps how many steps it has
 * @param nodesOpened how many times a cell was placed on the open set
 * @param nodesExpanded how many cells were settled
 * @param chunkReads how many chunk reads were charged
 * @param coldChunkReads how many of those were a chunk's first read
 * @param virtualMillis elapsed time on the modelled clock
 * @param ioDelayMicros total modelled IO delay charged
 * @param realMillis wall-clock time the run took; advisory only
 * @param peakHeapBytes peak heap during the run; advisory only
 * @param missingCaptureReport a description of reads outside the capture, or {@code null}
 * @param missingChunks every chunk read outside the capture, packed as {@code (x << 32) | z}
 * @param configuration what was measured: the heuristic, weight, limits, IO model and the exact
 *     capture. Two runs are only comparable when these agree
 */
public record RunResult(
    String scenario,
    String outcome,
    double pathCost,
    double pathTime,
    int pathSteps,
    long nodesOpened,
    long nodesExpanded,
    long chunkReads,
    long coldChunkReads,
    long virtualMillis,
    long ioDelayMicros,
    long realMillis,
    long peakHeapBytes,
    @Nullable String missingCaptureReport,
    java.util.Set<Long> missingChunks,
    Map<String, String> configuration) {

  /**
   * Returns whether this run produced a number worth comparing.
   *
   * <p>A run that read outside its capture did not, whatever else it reports.
   *
   * @return {@code true} if the result is meaningful
   */
  public boolean isValid() {
    return missingCaptureReport == null;
  }

  /**
   * Returns the metrics a build may be gated on, in a fixed order.
   *
   * @return the deterministic metrics
   */
  public Map<String, Number> deterministic() {
    Map<String, Number> values = new LinkedHashMap<>();
    values.put("pathCost", pathCost);
    values.put("pathTime", pathTime);
    values.put("pathSteps", pathSteps);
    values.put("nodesOpened", nodesOpened);
    values.put("nodesExpanded", nodesExpanded);
    values.put("chunkReads", chunkReads);
    values.put("coldChunkReads", coldChunkReads);
    values.put("virtualMillis", virtualMillis);
    values.put("ioDelayMicros", ioDelayMicros);
    return values;
  }

  /**
   * Returns the metrics that are reported but never gated.
   *
   * @return the advisory metrics
   */
  public Map<String, Number> advisory() {
    Map<String, Number> values = new LinkedHashMap<>();
    values.put("realMillis", realMillis);
    values.put("peakHeapBytes", peakHeapBytes);
    return values;
  }
}
