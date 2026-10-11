/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.cobblestonemc.TimeSource;
import org.cobblestonemc.minecraft.ChunkFetch;

/**
 * Charges a modelled cost for each chunk read, so a benchmark can ask what a search does on a disk
 * it does not have.
 *
 * <p>The delay is paid the same way in every mode that pays one — by completing the fetch's future
 * from a task scheduled on a {@link TimeSource}. What differs is which clock that is: the system
 * clock for {@link IoMode#SIMULATED}, a virtual one for {@link IoMode#VIRTUAL}. So a virtual run
 * exercises the same parking, the same read-ahead behaviour and the same deadline pressure as a
 * real one, and costs nothing to wait for.
 *
 * <p>Completing later rather than delaying the answer matters: a prefetch nobody ends up waiting on
 * must not cost the solve anything, and a solve blocked on a read must actually park. Sleeping
 * inside {@code fetchChunk} would get both backwards.
 */
final class SimulatedChunkIo {

  private final IoProfile profile;
  private final TimeSource time;

  /** Chunks read at least once this run; a second read is served warm. */
  private final Set<Long> seen = ConcurrentHashMap.newKeySet();

  /**
   * When each simulated IO channel next comes free, in microseconds on the model's timeline.
   *
   * <p>This is what makes {@code queueDepth} mean something. With unbounded parallelism a search
   * that reads ahead aggressively pays one latency for any number of chunks, which is exactly the
   * mistake that makes read-ahead look free; with a shallow queue the same burst serializes and the
   * later reads wait. Which of those a server actually does is the question read-ahead tuning turns
   * on.
   */
  private final AtomicLong[] channels;

  private final AtomicLong reads = new AtomicLong();
  private final AtomicLong coldReads = new AtomicLong();
  private final AtomicLong delayMicros = new AtomicLong();

  SimulatedChunkIo(IoProfile profile, TimeSource time) {
    this.profile = profile;
    this.time = time;
    int depth = Math.max(1, Math.min(profile.queueDepth(), 1024));
    this.channels = new AtomicLong[depth];
    for (int i = 0; i < depth; i++) {
      channels[i] = new AtomicLong();
    }
  }

  /** Returns how many reads have been charged. */
  long reads() {
    return reads.get();
  }

  /** Returns how many of those were a chunk's first read this run. */
  long coldReads() {
    return coldReads.get();
  }

  /** Returns the total modelled delay charged, in microseconds. */
  long totalDelayMicros() {
    return delayMicros.get();
  }

  /**
   * Charges a read and returns a future of its outcome.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param storedBytes the size of the stored column, for the throughput term
   * @param outcome produces the fetch result; invoked once the modelled delay has passed
   * @return the fetch
   */
  CompletableFuture<ChunkFetch> read(
      int chunkX, int chunkZ, int storedBytes, Supplier<ChunkFetch> outcome) {
    reads.incrementAndGet();
    // Counted before the mode is consulted. Whether a read is a chunk's first is a fact about what
    // the search did, not about the disk it was charged against, and a ZERO-io run that reported no
    // cold reads would be describing the model rather than the solve.
    long key = ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
    boolean cold = seen.add(key);
    if (cold) {
      coldReads.incrementAndGet();
    }
    if (profile.mode() == IoMode.ZERO) {
      return CompletableFuture.completedFuture(outcome.get());
    }

    long service = serviceMicros(cold, storedBytes, key);
    long waitMicros = queue(service);
    delayMicros.addAndGet(waitMicros);

    CompletableFuture<ChunkFetch> future = new CompletableFuture<>();
    // Round up: a sub-millisecond read must still cost something, or an NVMe profile would be
    // indistinguishable from ZERO on a clock that only counts milliseconds.
    long delayMillis = Math.max(1, (waitMicros + 999) / 1000);
    time.schedule(() -> future.complete(outcome.get()), delayMillis);
    return future;
  }

  /** The time this read occupies a channel for, before any queueing. */
  private long serviceMicros(boolean cold, int storedBytes, long key) {
    double base = cold ? profile.coldLatencyMicros() : profile.warmLatencyMicros();
    base += profile.microsPerKiB() * storedBytes / 1024.0;
    return Math.max(0, Math.round(base * jitter(key)));
  }

  /**
   * The lognormal multiplier for one chunk's read.
   *
   * <p><b>Derived from the chunk's coordinates, not drawn from a running generator.</b> A shared
   * RNG would hand out its values in whatever order reads happened to be issued, so two runs that
   * explored the same ground in a slightly different order would charge different chunks different
   * delays — and the benchmark would be measuring the ordering as much as the algorithm. Hashing
   * the coordinates makes a chunk's cost a property of the chunk.
   */
  private double jitter(long key) {
    if (profile.jitterSigma() <= 0) {
      return 1.0;
    }
    long mixed = key * 0x9E37_79B9_7F4A_7C15L + profile.seed();
    mixed ^= mixed >>> 32;
    mixed *= 0xBF58_476D_1CE4_E5B9L;
    mixed ^= mixed >>> 29;
    // Two uniforms out of one hash, turned into a standard normal by Box-Muller, then exponentiated
    // so the multiplier is positive and right-skewed — a few reads far slower than the median,
    // which is what a contended disk actually does.
    double u1 = Math.max(1e-12, ((mixed >>> 11) & 0x1F_FFFF) / (double) 0x20_0000);
    double u2 = ((mixed >>> 32) & 0x1F_FFFF) / (double) 0x20_0000;
    double normal = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    return Math.exp(
        profile.jitterSigma() * normal - profile.jitterSigma() * profile.jitterSigma() / 2);
  }

  /**
   * Places the read on the least-busy channel and returns how long until it completes, counting
   * time spent waiting for the channel.
   */
  private long queue(long serviceMicros) {
    long now = time.millis() * 1000L;
    AtomicLong best = channels[0];
    long bestFree = best.get();
    for (int i = 1; i < channels.length; i++) {
      long free = channels[i].get();
      if (free < bestFree) {
        bestFree = free;
        best = channels[i];
      }
    }
    long start = Math.max(now, bestFree);
    best.set(start + serviceMicros);
    return start + serviceMicros - now;
  }
}
