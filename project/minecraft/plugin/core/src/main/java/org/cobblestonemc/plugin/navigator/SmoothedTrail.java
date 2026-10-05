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
 * only smoothed where the player is about to see it, and the curve drawn through them.
 *
 * <p>The trail is drawn as segments from node {@code i} to node {@code i + 1}, each rounded by
 * {@link TrailCurve} at a {@code rounded} corner and left sharp at the others (and at the trail's
 * ends). The smoother is given the same corners, so what it keeps clear of blocks is what is drawn.
 * A corner that isn't rounded is also held in place: the trail turns exactly there.
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
  private final boolean[] rounded;
  private final boolean[] pinned;
  private final Vec3[] nodes;
  private final List<Vec3> view;
  private final boolean[] loaded;
  private final int[] smoothedAt; // per chunk: the tick it was last smoothed, or NEVER
  private final boolean[] incomplete; // per chunk: whether it read an unloaded block
  private final boolean[] partial; // per chunk: whether it had an unavailable node
  private Vec3 drawOrigin; // drawn in place of node 0, or null

  /**
   * Creates an unsmoothed trail.
   *
   * @param original the block-centered nodes, in path order
   * @param rounded whether the corner at the node at an index is drawn rounded (e.g. not next to an
   *     action step); the trail's first and last nodes never are
   * @param pinned whether the node at a rounded corner must still stay in place (e.g. a mined
   *     block)
   */
  SmoothedTrail(List<Vec3> original, IntPredicate rounded, IntPredicate pinned) {
    this.original = List.copyOf(original);
    int size = this.original.size();
    this.rounded = new boolean[size];
    this.pinned = new boolean[size];
    for (int i = 0; i < size; i++) {
      this.rounded[i] = i > 0 && i < size - 1 && rounded.test(i);
      this.pinned[i] = !this.rounded[i] || pinned.test(i);
    }
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

  /**
   * Draws the trail from {@code origin} instead of node 0, e.g. a guide drawn from wherever the
   * player now is; {@code null} draws from node 0 again. Smoothing still uses node 0.
   */
  void drawFrom(Vec3 origin) {
    this.drawOrigin = origin;
  }

  /** Whether segment {@code i} (node {@code i} to node {@code i + 1}) can be drawn. */
  boolean segmentDrawable(int i) {
    return drawnNodeVisible(i) && drawable(i + 1);
  }

  /** The straight-line length of segment {@code i}, as drawn. */
  double segmentLength(int i) {
    return nodes[i + 1].minus(drawnNode(i)).length();
  }

  /**
   * A point on segment {@code i} as drawn, rounded into its corners where they are rounded and
   * their far neighbor is drawable.
   *
   * @param i the segment, from node {@code i} to node {@code i + 1}
   * @param fraction how far along it, from 0 to 1
   * @return the point and the direction of travel there
   */
  TrailCurve.Sample sample(int i, double fraction) {
    Vec3 prev = rounded[i] && drawnNodeVisible(i - 1) ? drawnNode(i - 1) : null;
    Vec3 next = rounded[i + 1] && drawable(i + 2) ? nodes[i + 2] : null;
    return TrailCurve.sample(prev, drawnNode(i), nodes[i + 1], next, fraction);
  }

  private Vec3 drawnNode(int index) {
    return index == 0 && drawOrigin != null ? drawOrigin : nodes[index];
  }

  private boolean drawnNodeVisible(int index) {
    return (index == 0 && drawOrigin != null) || drawable(index);
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
    var round = Arrays.copyOfRange(rounded, start, end);
    var provisional = new boolean[end - start];
    var readable = new boolean[end - start];
    for (int i = start; i < end; i++) {
      boolean core = i >= coreStart && i < coreEnd;
      boolean settled = !core && smoothedAt[i / CHUNK] != NEVER;
      readable[i - start] = available.test(i);
      pin[i - start] = pinned[i] || !readable[i - start] || settled;
      provisional[i - start] = !core && !settled;
    }
    TrailSmoother.Result result =
        TrailSmoother.smooth(
            original.subList(start, end), view.subList(start, end), pin, round, provisional, probe);
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
