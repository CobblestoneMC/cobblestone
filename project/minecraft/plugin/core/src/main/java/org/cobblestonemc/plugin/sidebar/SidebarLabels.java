/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.sidebar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.cobblestonemc.plugin.trip.TripLabel;

/**
 * Shortens trip labels to the least text that still tells a player's trips apart, for the narrow
 * sidebar.
 *
 * <p>A label built from a destination address ({@code cobblestone location private home}) is cut
 * down to the fewest of its keys that no other trip's label could also be read as — just {@code
 * home} when it is the only trip, {@code private home} beside a trip to {@code location global
 * home}. The destination's own key always stays, and the remaining keys keep their order, the same
 * shapes {@code /navigate} accepts. Between equally short choices, keys nearer the destination win,
 * since they say the most about it.
 *
 * <p>A label supplied through the API carries no address and is shown exactly as given; the caller
 * chose it. It still counts as a neighbour that an address must not be shortened into.
 */
public final class SidebarLabels {

  /**
   * Addresses longer than this are shown in full rather than searched: the candidates double with
   * each key, and no real destination tree is anywhere near this deep.
   */
  private static final int MAX_SHORTENED_KEYS = 12;

  private SidebarLabels() {}

  /**
   * Shortens each label against all the others.
   *
   * @param labels the labels of one player's trips
   * @return the text to show for each label, in the same order
   */
  public static List<String> shorten(List<TripLabel> labels) {
    List<String> out = new ArrayList<>(labels.size());
    for (int i = 0; i < labels.size(); i++) {
      out.add(shorten(labels, i));
    }
    return out;
  }

  private static String shorten(List<TripLabel> labels, int self) {
    TripLabel label = labels.get(self);
    List<String> address = label.address();
    if (address.isEmpty() || address.size() > MAX_SHORTENED_KEYS) {
      return label.text();
    }
    for (List<String> candidate : candidates(address)) {
      if (unique(candidate, labels, self)) {
        return String.join(" ", candidate);
      }
    }
    return label.text(); // two trips to the very same address: nothing shorter tells them apart
  }

  /**
   * Every ordered subsequence of {@code address} that ends with its final key, shortest first and,
   * within a length, preferring keys nearer the end.
   */
  private static List<List<String>> candidates(List<String> address) {
    int ancestors = address.size() - 1;
    // Bit i of a mask keeps ancestor i. A higher mask keeps later ancestors, so among masks with
    // the same number of bits it is the one nearer the destination.
    return IntStream.range(0, 1 << ancestors)
        .boxed()
        .sorted(
            Comparator.comparingInt(Integer::bitCount)
                .thenComparing(Comparator.<Integer>reverseOrder()))
        .map(
            mask -> {
              List<String> keys = new ArrayList<>();
              for (int i = 0; i < ancestors; i++) {
                if ((mask & (1 << i)) != 0) {
                  keys.add(address.get(i));
                }
              }
              keys.add(address.getLast());
              return keys;
            })
        .toList();
  }

  private static boolean unique(List<String> candidate, List<TripLabel> labels, int self) {
    for (int i = 0; i < labels.size(); i++) {
      if (i != self && readableAs(candidate, labels.get(i))) {
        return false;
      }
    }
    return true;
  }

  /** Whether {@code candidate} could just as well name {@code other}. */
  private static boolean readableAs(List<String> candidate, TripLabel other) {
    if (other.address().isEmpty()) {
      return String.join(" ", candidate).equalsIgnoreCase(other.text());
    }
    List<String> keys = other.address();
    if (!keys.getLast().equalsIgnoreCase(candidate.getLast())) {
      return false;
    }
    // The candidate's other keys must appear, in order, among the other address's ancestors.
    int next = 0;
    for (int i = 0; i < keys.size() - 1 && next < candidate.size() - 1; i++) {
      if (keys.get(i).equalsIgnoreCase(candidate.get(next))) {
        next++;
      }
    }
    return next == candidate.size() - 1;
  }
}
