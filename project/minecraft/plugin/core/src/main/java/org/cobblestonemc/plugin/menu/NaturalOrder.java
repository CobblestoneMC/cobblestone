/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.Comparator;

/**
 * Alphabetical order that reads runs of digits as numbers, ignoring case: {@code outpost-2} before
 * {@code outpost-10}, and {@code Bob} beside {@code bob}. Ties are broken by plain string order, so
 * the order is total.
 */
final class NaturalOrder implements Comparator<String> {

  static final NaturalOrder INSTANCE = new NaturalOrder();

  private NaturalOrder() {}

  @Override
  public int compare(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      char ca = a.charAt(i);
      char cb = b.charAt(j);
      if (Character.isDigit(ca) && Character.isDigit(cb)) {
        int endA = digitsEnd(a, i);
        int endB = digitsEnd(b, j);
        int byNumber = compareNumbers(a.substring(i, endA), b.substring(j, endB));
        if (byNumber != 0) {
          return byNumber;
        }
        i = endA;
        j = endB;
        continue;
      }
      int byChar = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
      if (byChar != 0) {
        return byChar;
      }
      i++;
      j++;
    }
    int byLength = Integer.compare(a.length() - i, b.length() - j);
    return byLength != 0 ? byLength : a.compareTo(b);
  }

  private static int digitsEnd(String text, int from) {
    int end = from;
    while (end < text.length() && Character.isDigit(text.charAt(end))) {
      end++;
    }
    return end;
  }

  /** Compares two runs of digits by value, however long they are. */
  private static int compareNumbers(String a, String b) {
    String trimmedA = a.replaceFirst("^0+(?=.)", "");
    String trimmedB = b.replaceFirst("^0+(?=.)", "");
    int byLength = Integer.compare(trimmedA.length(), trimmedB.length());
    return byLength != 0 ? byLength : trimmedA.compareTo(trimmedB);
  }
}
