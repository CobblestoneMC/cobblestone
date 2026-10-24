/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.NavigationTarget;

/**
 * What differs between servers for the {@link NavigationMenus}: who the player is, and how to close
 * the menu, navigate, and ask for options.
 *
 * @param <P> the platform player type
 */
public interface MenuPlatform<P> {

  /**
   * The player's locale.
   *
   * @param player the player
   * @return the locale
   */
  Locale locale(P player);

  /**
   * The player's unique id.
   *
   * @param player the player
   * @return the id
   */
  UUID id(P player);

  /**
   * Tests whether the player holds a permission node.
   *
   * @param player the player
   * @return the test
   */
  Predicate<String> hasPermission(P player);

  /**
   * Tests whether the player may navigate to a canonical address, as {@code /navigate} does.
   *
   * @param player the player
   * @return the test
   */
  Predicate<List<String>> canNavigate(P player);

  /**
   * Closes the player's menu and then does something. Called from within a click, so both must wait
   * for the click to finish.
   *
   * @param player the player
   * @param then what to do once the menu is closed
   */
  void closeThen(P player, Runnable then);

  /**
   * Navigates the player to a destination they picked, as {@code /navigate} would.
   *
   * @param player the player
   * @param pick the destination, with its options parsed
   * @param destination the destination, already built
   */
  void navigate(
      P player, DestinationResolver.Resolved<NavigationTarget> pick, NavigationTarget destination);

  /**
   * Asks the player for a destination's options, then navigates there.
   *
   * @param player the player
   * @param fillIn the destination, and what to call it while asking
   */
  void fillIn(P player, MenuAction.FillIn fillIn);
}
