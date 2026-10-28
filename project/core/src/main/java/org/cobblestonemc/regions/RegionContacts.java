/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.util.HashMap;
import java.util.Map;

/**
 * Contacts between regions, gathered cell by cell before they become portals.
 *
 * <p>Two regions touch along many cells, and each pair should become one portal whose box covers
 * all of them, so contacts are accumulated by pair and emitted once. Falls are kept apart from
 * ordinary contacts because they are one-way: {@code (a, b)} and {@code (b, a)} are different
 * falls, while a contact is the same whichever side it is seen from.
 *
 * <p>Region numbers are whatever the caller uses: section-local while a section is built, the
 * graph's own at a seam. Not thread-safe; reused by one builder or one graph.
 */
final class RegionContacts {

  private final Map<Long, int[]> contacts = new HashMap<>();
  private final Map<Long, Map<Integer, int[]>> drops = new HashMap<>();

  /** The last contact touched, since consecutive cells nearly always join the same two regions. */
  private long lastContact = Long.MIN_VALUE;

  private int[] lastContactBox;

  /** Receives each gathered contact as a portal. */
  interface Sink {
    /**
     * Receives a portal.
     *
     * @param a the first region, the one a fall leaves
     * @param b the second, the one a fall lands in
     * @param fall how far the body falls, or 0 for a contact crossed both ways
     * @param box the cells on both sides of the contact
     */
    void portal(int a, int b, int fall, int[] box);
  }

  /** Records that two cells of different regions share a face; {@code -1} is no region. */
  void touch(int a, int b, int ax, int ay, int az, int bx, int by, int bz) {
    if (a < 0 || b < 0 || a == b) {
      return;
    }
    long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
    int[] box;
    if (key == lastContact) {
      box = lastContactBox;
    } else {
      box = contacts.computeIfAbsent(key, k -> empty());
      lastContact = key;
      lastContactBox = box;
    }
    grow(box, ax, ay, az);
    grow(box, bx, by, bz);
  }

  /**
   * Records a fall of {@code depth} blocks from region {@code from} into region {@code to}, one-way
   * at the ledge: the box is the ledge and the air beside it. Falls of different depths between the
   * same two regions are separate portals, so an agent that can take only the shallow ones is
   * priced from where those are.
   */
  void drop(int from, int to, int depth, int fx, int fy, int fz, int ax, int ay, int az) {
    if (from < 0 || to < 0 || to == from) {
      return;
    }
    int[] box =
        drops
            .computeIfAbsent(((long) from << 32) | to, k -> new HashMap<>())
            .computeIfAbsent(depth, k -> empty());
    grow(box, fx, fy, fz);
    grow(box, ax, ay, az);
  }

  /** Emits everything gathered, contacts before falls, and starts afresh. */
  void flush(Sink sink) {
    for (Map.Entry<Long, int[]> entry : contacts.entrySet()) {
      long key = entry.getKey();
      sink.portal((int) (key >>> 32), (int) key, 0, entry.getValue());
    }
    for (Map.Entry<Long, Map<Integer, int[]>> entry : drops.entrySet()) {
      long key = entry.getKey();
      for (Map.Entry<Integer, int[]> fall : entry.getValue().entrySet()) {
        sink.portal((int) (key >>> 32), (int) key, fall.getKey(), fall.getValue());
      }
    }
    contacts.clear();
    drops.clear();
    lastContact = Long.MIN_VALUE;
    lastContactBox = null;
  }

  private static int[] empty() {
    return new int[] {
      Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
      -Integer.MAX_VALUE, -Integer.MAX_VALUE, -Integer.MAX_VALUE
    };
  }

  static void grow(int[] box, int x, int y, int z) {
    box[0] = Math.min(box[0], x);
    box[1] = Math.min(box[1], y);
    box[2] = Math.min(box[2], z);
    box[3] = Math.max(box[3], x);
    box[4] = Math.max(box[4], y);
    box[5] = Math.max(box[5], z);
  }
}
