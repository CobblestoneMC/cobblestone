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
 * <p>Each chunk of {@code CHUNK} nodes is smoothed once and then kept: the trail doesn't shift as
 * the player walks. A chunk is smoothed from the nodes' current positions, with its smoothed
 * neighbors held in place, so the two join exactly. Up to {@code CONTEXT} nodes of a neighbor that
 * isn't smoothed yet are smoothed along with it, but only as context, so the chunk's ends don't
 * bend toward the unsmoothed path. A chunk that had unloaded nodes, or nodes held back by unloaded
 * blocks around them, is re-smoothed every {@code RETRY_TICKS} until everything it read has loaded,
 * and one that had unavailable nodes is re-smoothed after {@link #resetUnavailable}. Until a node's
 * chunk is smoothed, the node keeps its original position and is not {@link #drawable}.
 */
final class SmoothedTrail {

  static final int CHUNK = 32;
  static final int CONTEXT = 16;

  /** Bounds the work of one tick on a fresh path. */
  private static final int CHUNKS_PER_TICK = 1;

  private static final int RETRY_TICKS = 20;
  private static final int NEVER = Integer.MIN_VALUE;

  private final List<Vec3> original;
  private final IntPredicate pinned;
  private final Vec3[] nodes;
  private final List<Vec3> view;
  private final boolean[] loaded;
  private final int[] smoothedAt; // per chunk: the tick it was last smoothed, or NEVER
  private final boolean[] incomplete; // per chunk: whether it read an unloaded block
  private final boolean[] partial; // per chunk: whether it had an unavailable node

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
    this.partial = new boolean[chunks];
  }

  int size() {
    return nodes.length;
  }

  /** The node at {@code index}: smoothed if its chunk has been, otherwise the original. */
  Vec3 node(int index) {
    return nodes[index];
  }

  /** The original, block-centered node at {@code index}. */
  Vec3 original(int index) {
    return original.get(index);
  }

  /** A live view of every node (see {@link #node}). */
  List<Vec3> nodes() {
    return view;
  }

  /** Whether the node at {@code index} has been smoothed and its block was loaded. */
  boolean drawable(int index) {
    return index >= 0 && index < nodes.length && loaded[index];
  }

  /** The node at {@code index} if it is {@link #drawable}, otherwise {@code null}. */
  Vec3 drawableNode(int index) {
    return drawable(index) ? nodes[index] : null;
  }

  /**
   * Smooths the not-yet-smoothed (or incompletely loaded) chunks covering nodes {@code from}
   * through {@code to}, nearest first, up to a small per-tick budget.
   *
   * @param from the first node needed
   * @param to the last node needed (inclusive)
   * @param tick the current tick counter
   * @param available whether a node can be read right now (e.g. it is in the player's world); an
   *     unavailable node is treated as unloaded, but isn't retried until {@link #resetUnavailable}
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

  /**
   * Marks every chunk that had unavailable nodes to be smoothed again, e.g. once the player changes
   * world.
   */
  void resetUnavailable() {
    for (int chunk = 0; chunk < partial.length; chunk++) {
      if (partial[chunk]) {
        smoothedAt[chunk] = NEVER;
      }
    }
  }

  private void smoothChunk(int chunk, IntPredicate available, TrailSmoother.BlockProbe probe) {
    int coreStart = chunk * CHUNK;
    int coreEnd = Math.min(nodes.length, coreStart + CHUNK);
    int start = Math.max(0, coreStart - CONTEXT);
    int end = Math.min(nodes.length, coreEnd + CONTEXT);
    var pin = new boolean[end - start];
    var provisional = new boolean[end - start];
    var readable = new boolean[end - start];
    for (int i = start; i < end; i++) {
      boolean core = i >= coreStart && i < coreEnd;
      boolean settled = !core && smoothedAt[i / CHUNK] != NEVER;
      readable[i - start] = available.test(i);
      pin[i - start] = pinned.test(i) || !readable[i - start] || settled;
      provisional[i - start] = !core && !settled;
    }
    TrailSmoother.Result result =
        TrailSmoother.smooth(
            original.subList(start, end), view.subList(start, end), pin, provisional, probe);
    boolean anyUnloaded = false;
    boolean anyUnavailable = false;
    for (int i = coreStart; i < coreEnd; i++) {
      nodes[i] = result.points().get(i - start);
      loaded[i] = readable[i - start] && result.loaded()[i - start];
      anyUnloaded |= readable[i - start] && !result.loaded()[i - start];
      anyUnavailable |= !readable[i - start];
    }
    incomplete[chunk] = anyUnloaded || result.nearUnloaded();
    partial[chunk] = anyUnavailable;
  }
}
