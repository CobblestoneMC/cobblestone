/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The destinations each player has most recently set off to, newest first, so the navigation menu
 * can offer their favorites without a walk through the tree. Kept in memory only: a restart forgets
 * them, which is the point — this is a convenience, not a record.
 *
 * <p>Thread-safe: trips start on whichever thread their search finishes on.
 */
public final class RecentDestinations {

  /** How many destinations are remembered per player: one row of the menu. */
  public static final int CAPACITY = MenuLayout.CONTENT_COLUMNS;

  /**
   * One remembered destination: what it takes to name it again.
   *
   * @param address its canonical address
   * @param optionTokens the tokens its options were given, as typed
   */
  public record Recent(List<String> address, List<String> optionTokens) {
    /** Canonical constructor; copies the lists. */
    public Recent {
      address = List.copyOf(address);
      optionTokens = List.copyOf(optionTokens);
    }
  }

  private final Map<UUID, List<Recent>> byPlayer = new ConcurrentHashMap<>();

  /**
   * Remembers that a player set off to a destination, moving it to the front if it was already
   * remembered and forgetting the oldest beyond {@link #CAPACITY}.
   *
   * @param player the player
   * @param recent the destination
   */
  public void record(UUID player, Recent recent) {
    byPlayer.compute(
        player,
        (ignored, current) -> {
          List<Recent> next = new ArrayList<>(CAPACITY);
          next.add(recent);
          if (current != null) {
            for (Recent older : current) {
              if (next.size() == CAPACITY) {
                break;
              }
              if (!older.equals(recent)) {
                next.add(older);
              }
            }
          }
          return List.copyOf(next);
        });
  }

  /**
   * A player's remembered destinations, newest first.
   *
   * @param player the player
   * @return the destinations, possibly empty
   */
  public List<Recent> get(UUID player) {
    return byPlayer.getOrDefault(player, List.of());
  }
}
