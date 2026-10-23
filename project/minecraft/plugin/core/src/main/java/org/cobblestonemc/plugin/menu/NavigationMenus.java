/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.api.Validity;
import org.cobblestonemc.plugin.destination.DestinationProvider;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.DestinationRoots;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Messages;

/**
 * Opens navigation menus and carries out what their clicks ask for, leaving the {@link
 * MenuPlatform} only what differs between servers.
 *
 * @param <P> the platform player type
 */
public final class NavigationMenus<P> {

  private final MenuPlatform<P> platform;
  private final Supplier<? extends Map<String, ? extends DestinationProvider<P>>> providers;
  private final RecentDestinations recents;
  private final Messages messages;
  private final Supplier<MenuSettings> settings;
  private final CobblestoneLogger log;

  /**
   * Creates the menus.
   *
   * @param platform what differs between servers
   * @param providers the registered destination providers, keyed by owner
   * @param recents the players' recent destinations
   * @param messages renders the menus' words
   * @param settings reads how the menus look, each time one opens
   * @param log where a destination that fails to build is reported
   */
  public NavigationMenus(
      MenuPlatform<P> platform,
      Supplier<? extends Map<String, ? extends DestinationProvider<P>>> providers,
      RecentDestinations recents,
      Messages messages,
      Supplier<MenuSettings> settings,
      CobblestoneLogger log) {
    this.platform = platform;
    this.providers = providers;
    this.recents = recents;
    this.messages = messages;
    this.settings = settings;
    this.log = log;
  }

  /**
   * A new menu for a player, on its top level.
   *
   * @param player the player
   * @return the menu
   */
  public NavigationMenu menu(P player) {
    Map<String, ? extends DestinationProvider<P>> registered = providers.get();
    Map<String, Display> displays = new TreeMap<>();
    registered.forEach((owner, provider) -> displays.put(owner, provider.display(player)));
    return new NavigationMenu(
        platform.locale(player),
        messages,
        settings.get(),
        DestinationRoots.of(registered, player),
        displays,
        recents.get(platform.id(player)),
        platform.hasPermission(player),
        platform.canNavigate(player));
  }

  /**
   * Handles a click in a player's menu. Call on the player's own thread: a destination they pick is
   * built here, and may read the world around them. One they cannot go to now stays in the menu,
   * marked with the reason.
   *
   * @param player the player
   * @param menu their menu
   * @param slot the slot clicked
   * @param redraw draws the menu again
   */
  public void click(P player, NavigationMenu menu, int slot, Runnable redraw) {
    switch (menu.click(slot)) {
      case MenuAction.None none -> {}
      case MenuAction.Redraw again -> redraw.run();
      case MenuAction.Close close -> platform.closeThen(player, () -> {});
      case MenuAction.Navigate pick -> {
        switch (build(player, pick.destination())) {
          case Validity.Invalid<NavigationTarget>(Component reason) -> {
            menu.refuse(reason);
            redraw.run();
          }
          case Validity.Valid<NavigationTarget>(NavigationTarget destination) ->
              platform.closeThen(
                  player, () -> platform.navigate(player, pick.destination(), destination));
        }
      }
      case MenuAction.FillIn fillIn ->
          platform.closeThen(player, () -> platform.fillIn(player, fillIn));
    }
  }

  private Validity<NavigationTarget> build(
      P player, DestinationResolver.Resolved<NavigationTarget> pick) {
    try {
      return pick.leaf().build(pick.values());
    } catch (RuntimeException e) {
      // Most likely an integration's leaf; the address's first key names the plugin.
      log.error(
          "menu {} -> {}: building the destination failed",
          e,
          platform.id(player),
          String.join(" ", pick.address()));
      return Validity.invalid(
          messages.render(platform.locale(player), CobblestoneMessages.NAVIGATE_ERROR));
    }
  }
}
