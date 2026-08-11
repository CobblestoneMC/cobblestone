/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.destination;

import java.util.List;
import java.util.function.Predicate;

/**
 * The Odyssey-owned permission that gates whether a player may <b>navigate</b> to a destination — a
 * separate concern from whether they may <i>teleport</i> there (that stays the target plugin's own
 * permission). Every destination's address in the {@code /navigate} tree maps to a node under
 * {@link #BASE}: {@code essentials.home.base} → {@code odyssey.navigate.essentials.home.base}. So an
 * admin can let players route to a place they cannot teleport to, or hide one they can.
 *
 * <p>Semantics are <b>default-allow</b>: navigation is permitted unless the node is explicitly set to
 * {@code false} (e.g. {@code -odyssey.navigate.towny.town.spawnhaven}, or a wildcard
 * {@code -odyssey.navigate.towny.*} on permission plugins that resolve wildcards). Only the full
 * address node is checked, so a permission plugin's specificity rules apply (a broad deny plus a
 * specific grant still grants).
 */
public final class NavigationPermissions {

  /** The base of every navigation-gate permission node. */
  public static final String BASE = "odyssey.navigate";

  private NavigationPermissions() {
  }

  /** The permission node gating navigation to the destination at the given tree address. */
  public static String node(List<String> address) {
    return address.isEmpty() ? BASE : BASE + "." + String.join(".", address);
  }

  /**
   * Whether the player may navigate to the destination at {@code address}. Default-allow: permitted
   * unless the node is set and false.
   *
   * @param address the destination's full key path in the {@code /navigate} tree
   * @param isSet whether a permission node has an explicit value for the player
   * @param has whether the player holds a permission node (its resolved value)
   * @return {@code true} unless the node is explicitly denied
   */
  public static boolean allowed(List<String> address, Predicate<String> isSet, Predicate<String> has) {
    String node = node(address);
    return !isSet.test(node) || has.test(node);
  }
}
