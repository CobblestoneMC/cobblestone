/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * A trail's nodes, smoothed by {@link TrailSmoother} lazily and in fixed chunks, so a long path is
 * only smoothed where the player is about to see it.
 *
 * <p>Each chunk of {@code CHUNK} nodes is smoothed once, together with {@code CONTEXT} nodes of
 * context on either side (so it joins its neighbors almost seamlessly), and then kept: the trail
 * doesn't shift as the player walks. A chunk that had unloaded nodes is re-smoothed every {@code
 * RETRY_TICKS} until everything in it has loaded. Until a node's chunk is smoothed, the node keeps
 * its original position and is not {@link #drawable}.
 */
final class SmoothedTrail {

  static final int CHUNK = 32;
  static final int CONTEXT = 16;
  private static final int CHUNKS_PER_TICK = 2; // bounds the work of one tick on a fresh path
  private static final int RETRY_TICKS = 20;
  private static final int NEVER = Integer.MIN_VALUE;

  private final List<Vec3> original;
  private final IntPredicate pinned;
  private final Vec3[] nodes;
  private final List<Vec3> view;
  private final boolean[] loaded;
  private final int[] smoothedAt; // per chunk: the tick it was last smoothed, or NEVER
  private final boolean[] incomplete; // per chunk: whether it had an unloaded node

  /**
   * Creates an unsmoothed trail.
   *
   * @param original the block-centered nodes, in path order
   * @param pinned whether the node at an index must stay in place (e.g. next to an action step)
   */
  SmoothedTrail(List<Vec3> original, IntPredicate pinned) {
    this.original = List.copyOf(original);
    this.pinned = pinned;
    this.nodes = this.original.toArray(new Vec3[0]);
    this.view = Collections.unmodifiableList(Arrays.asList(nodes));
    this.loaded = new boolean[nodes.length];
    int chunks = (nodes.length + CHUNK - 1) / CHUNK;
    this.smoothedAt = new int[chunks];
    Arrays.fill(smoothedAt, NEVER);
    this.incomplete = new boolean[chunks];
  }

  int size() {
    return nodes.length;
  }

  /** The node at {@code index}: smoothed if its chunk has been, otherwise the original. */
  Vec3 node(int index) {
    return nodes[index];
  }

  /** A live view of every node (see {@link #node}). */
  List<Vec3> nodes() {
    return view;
  }

  /** Whether the node at {@code index} has been smoothed and its block was loaded. */
  boolean drawable(int index) {
    return index >= 0 && index < nodes.length && loaded[index];
  }

  /**
   * Smooths the not-yet-smoothed (or incompletely loaded) chunks covering nodes {@code from}
   * through {@code to}, nearest first, up to a small per-tick budget.
   *
   * @param from the first node needed
   * @param to the last node needed (inclusive)
   * @param tick the current tick counter
   * @param available whether a node can be read right now (e.g. it is in the player's world); an
   *     unavailable node is treated as unloaded
   * @param probe reads the world
   */
  void refresh(int from, int to, int tick, IntPredicate available, TrailSmoother.BlockProbe probe) {
    if (nodes.length == 0) {
      return;
    }
    int budget = CHUNKS_PER_TICK;
    int last = Math.min(to, nodes.length - 1) / CHUNK;
    for (int chunk = Math.max(0, from) / CHUNK; chunk <= last && budget > 0; chunk++) {
      boolean stale =
          smoothedAt[chunk] == NEVER
              || (incomplete[chunk] && tick - smoothedAt[chunk] >= RETRY_TICKS);
      if (stale) {
        smoothChunk(chunk, available, probe);
        smoothedAt[chunk] = tick;
        budget--;
      }
    }
  }

  private void smoothChunk(int chunk, IntPredicate available, TrailSmoother.BlockProbe probe) {
    int coreStart = chunk * CHUNK;
    int coreEnd = Math.min(nodes.length, coreStart + CHUNK);
    int start = Math.max(0, coreStart - CONTEXT);
    int end = Math.min(nodes.length, coreEnd + CONTEXT);
    var pin = new boolean[end - start];
    var readable = new boolean[end - start];
    for (int i = start; i < end; i++) {
      readable[i - start] = available.test(i);
      pin[i - start] = pinned.test(i) || !readable[i - start];
    }
    TrailSmoother.Result result = TrailSmoother.smooth(original.subList(start, end), pin, probe);
    boolean anyUnloaded = false;
    for (int i = coreStart; i < coreEnd; i++) {
      nodes[i] = result.points().get(i - start);
      loaded[i] = readable[i - start] && result.loaded()[i - start];
      anyUnloaded |= !loaded[i];
    }
    incomplete[chunk] = anyUnloaded;
  }
}
