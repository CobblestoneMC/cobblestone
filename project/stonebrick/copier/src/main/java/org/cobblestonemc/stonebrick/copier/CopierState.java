/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import java.nio.file.Path;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.cobblestonemc.stonebrick.format.CorpusLayout;
import org.jetbrains.annotations.Nullable;

/**
 * What the plugin remembers between commands: where captures are written, and the one job that may
 * be running.
 *
 * <p><b>One job at a time.</b> Two captures writing into one directory would interleave their
 * updates to its trait table, and the loser would leave the capture referencing block states
 * nothing describes — a failure that appears much later, at benchmark time, as an unreadable
 * column.
 */
final class CopierState {

  private final Path outputRoot;
  private volatile @Nullable CaptureJob active;

  CopierState(Path outputRoot) {
    this.outputRoot = outputRoot;
  }

  /** Returns the capture name used when no {@code --name} is given. */
  String defaultCaptureName() {
    return "capture";
  }

  /**
   * Returns a handle on a capture directory by name.
   *
   * @param name the capture name
   * @return the capture directory
   */
  CaptureDirectory capture(String name) {
    return CorpusLayout.at(outputRoot).capture(name);
  }

  /**
   * Returns where captures are written.
   *
   * <p>Laid out as a corpus — {@code out/captures/&lt;name&gt;/} — rather than one directory per
   * capture at the top level, so that moving a finished capture into a benchmark corpus is a
   * directory copy with nothing to rename or rearrange.
   *
   * @return the output root
   */
  Path outputRoot() {
    return outputRoot;
  }

  /** Returns whether a capture is currently running. */
  boolean hasActiveJob() {
    CaptureJob job = active;
    return job != null && !job.isFinished();
  }

  /** Returns a one-line description of the running job, or that there is none. */
  String activeStatus() {
    CaptureJob job = active;
    if (job == null) {
      return "No capture has been run since the server started.";
    }
    return (job.isFinished() ? "Last capture — " : "Running — ") + job.status();
  }

  /**
   * Starts a job, which becomes the active one.
   *
   * @param job the job
   */
  void start(CaptureJob job) {
    this.active = job;
    job.start();
  }

  /** Asks the running job to stop, if there is one. */
  void cancel() {
    CaptureJob job = active;
    if (job != null) {
      job.cancel();
    }
  }
}
