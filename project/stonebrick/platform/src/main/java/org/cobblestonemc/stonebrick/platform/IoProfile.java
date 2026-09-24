/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

/**
 * What a chunk read costs, as a model a benchmark can dial.
 *
 * <p>Every field is here because it changes the <em>shape</em> of the search's behaviour, not just
 * its total: latency decides how much a cache miss hurts, the cold/warm split decides whether
 * re-reading is cheap, queue depth decides whether reading ahead helps or merely queues, and jitter
 * decides whether a solve's slowest reads dominate it. A single "milliseconds per chunk" number
 * would reproduce none of that.
 *
 * @param mode how the delay is spent
 * @param coldLatencyMicros latency of the first read of a chunk in a run
 * @param warmLatencyMicros latency of a later read of the same chunk, as the OS page cache would
 *     serve it
 * @param jitterSigma spread of the lognormal multiplier applied to latency; {@code 0} disables it
 * @param microsPerKiB throughput term, charged against the stored size of the column
 * @param queueDepth how many reads may be in flight before further ones queue behind them
 * @param seed fixes the jitter, so a run is repeatable
 */
public record IoProfile(
    IoMode mode,
    long coldLatencyMicros,
    long warmLatencyMicros,
    double jitterSigma,
    double microsPerKiB,
    int queueDepth,
    long seed) {

  /** No delay; the search's own cost, measured alone. */
  public static IoProfile zero() {
    return new IoProfile(IoMode.ZERO, 0, 0, 0, 0, Integer.MAX_VALUE, 0);
  }

  /** An NVMe SSD: sub-100µs reads, deep queue, little spread. */
  public static IoProfile nvme() {
    return new IoProfile(IoMode.VIRTUAL, 80, 10, 0.15, 0.4, 32, 1);
  }

  /** A SATA SSD: the common case on a rented server. */
  public static IoProfile sataSsd() {
    return new IoProfile(IoMode.VIRTUAL, 300, 20, 0.25, 1.5, 16, 1);
  }

  /** A spinning disk: a seek dominates, the queue is shallow, and the spread is wide. */
  public static IoProfile spinning() {
    return new IoProfile(IoMode.VIRTUAL, 8_000, 40, 0.6, 12.0, 4, 1);
  }

  /**
   * A busy shared host: SSD latency, but contending with everything else on the box.
   *
   * <p>The profile most worth checking a change against. A server taking chunk reads for a search
   * is by definition doing something else at the same time, and a read that is fast in isolation
   * and erratic under load is exactly the case a benchmark run on an idle laptop will miss.
   */
  public static IoProfile busyServer() {
    return new IoProfile(IoMode.VIRTUAL, 1_200, 60, 1.1, 4.0, 2, 1);
  }

  /** Returns this profile with a different mode. */
  public IoProfile withMode(IoMode newMode) {
    return new IoProfile(
        newMode, coldLatencyMicros, warmLatencyMicros, jitterSigma, microsPerKiB, queueDepth, seed);
  }

  /** Returns this profile with a different seed. */
  public IoProfile withSeed(long newSeed) {
    return new IoProfile(
        mode, coldLatencyMicros, warmLatencyMicros, jitterSigma, microsPerKiB, queueDepth, newSeed);
  }

  /**
   * Returns the named profile, or {@code null} if the name is not one.
   *
   * @param name the profile name, case-insensitive
   * @return the profile, or {@code null}
   */
  public static IoProfile byName(String name) {
    return switch (name.toLowerCase(java.util.Locale.ROOT)) {
      case "zero" -> zero();
      case "nvme" -> nvme();
      case "sata-ssd", "sata" -> sataSsd();
      case "spinning" -> spinning();
      case "busy-server", "busy" -> busyServer();
      default -> null;
    };
  }
}
