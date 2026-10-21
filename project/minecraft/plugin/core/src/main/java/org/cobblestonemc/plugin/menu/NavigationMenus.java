/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.destination.DestinationProvider;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.DestinationRoots;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.message.Messages;

/**
 * Opens navigation menus and carries out what their clicks ask for, leaving a platform only what
 * differs between servers: drawing the menu, and how to close it, navigate, and ask for options.
 *
 * @param <P> the platform player type
 */
public abstract class NavigationMenus<P> {

  private final Supplier<? extends Map<String, ? extends DestinationProvider<P>>> providers;
  private final RecentDestinations recents;
  private final Messages messages;
  private final Supplier<MenuSettings> settings;

  /**
   * Creates the menus.
   *
   * @param providers the registered destination providers, keyed by owner
   * @param recents the players' recent destinations
   * @param messages renders the menus' words
   * @param settings reads how the menus look, each time one opens
   */
  protected NavigationMenus(
      Supplier<? extends Map<String, ? extends DestinationProvider<P>>> providers,
      RecentDestinations recents,
      Messages messages,
      Supplier<MenuSettings> settings) {
    this.providers = providers;
    this.recents = recents;
    this.messages = messages;
    this.settings = settings;
  }

  /**
   * A new menu for a player, on its top level.
   *
   * @param player the player
   * @return the menu
   */
  protected final NavigationMenu menu(P player) {
    Map<String, ? extends DestinationProvider<P>> registered = providers.get();
    Map<String, Display> displays = new TreeMap<>();
    registered.forEach((owner, provider) -> displays.put(owner, provider.display(player)));
    return new NavigationMenu(
        locale(player),
        messages,
        settings.get(),
        DestinationRoots.of(registered, player),
        displays,
        recents.get(id(player)),
        hasPermission(player),
        canNavigate(player));
  }

  /**
   * Carries out what a click in a player's menu asks for.
   *
   * @param player the player
   * @param action what the click asks for
   * @param redraw draws the menu again
   */
  protected final void handle(P player, MenuAction action, Runnable redraw) {
    switch (action) {
      case MenuAction.None none -> {}
      case MenuAction.Redraw again -> redraw.run();
      case MenuAction.Close close -> closeThen(player, () -> {});
      case MenuAction.Navigate pick ->
          closeThen(player, () -> navigate(player, pick.destination()));
      case MenuAction.FillIn fillIn -> closeThen(player, () -> fillIn(player, fillIn));
    }
  }

  /**
   * The player's locale.
   *
   * @param player the player
   * @return the locale
   */
  protected abstract Locale locale(P player);

  /**
   * The player's unique id.
   *
   * @param player the player
   * @return the id
   */
  protected abstract UUID id(P player);

  /**
   * Tests whether the player holds a permission node.
   *
   * @param player the player
   * @return the test
   */
  protected abstract Predicate<String> hasPermission(P player);

  /**
   * Tests whether the player may navigate to a canonical address, as {@code /navigate} does.
   *
   * @param player the player
   * @return the test
   */
  protected abstract Predicate<List<String>> canNavigate(P player);

  /**
   * Closes the player's menu and then does something. Called from within a click, so both must wait
   * for the click to finish.
   *
   * @param player the player
   * @param then what to do once the menu is closed
   */
  protected abstract void closeThen(P player, Runnable then);

  /**
   * Navigates the player to a destination they picked, as {@code /navigate} would.
   *
   * @param player the player
   * @param destination the destination, with its options parsed
   */
  protected abstract void navigate(
      P player, DestinationResolver.Resolved<NavigationTarget> destination);

  /**
   * Asks the player for a destination's options, then navigates there.
   *
   * @param player the player
   * @param fillIn the destination, and what to call it while asking
   */
  protected abstract void fillIn(P player, MenuAction.FillIn fillIn);
}
