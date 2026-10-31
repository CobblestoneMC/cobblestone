/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.plugin.menu.NavigationMenu;
import org.cobblestonemc.plugin.menu.NavigationMenus;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.trip.Trip;
import org.cobblestonemc.plugin.trip.TripLabels;
import org.cobblestonemc.plugin.trip.TripManager;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;

/**
 * The navigation menu as a Bedrock form: a scrolling list of named buttons, which phones and
 * consoles draw far better than a chest. A press is handed to the {@link NavigationMenu} as a chest
 * click would be, and anything that changes the menu sends the form again.
 *
 * <p>While the player has trips running, the top level opens with a button to stop each, since a
 * Bedrock player has no label {@code Cancel} to hit.
 *
 * <p>Only loaded when Floodgate is here, since it builds Floodgate's forms.
 */
final class PaperMenuForms {

  private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

  /** The picture on a stop button, from the Bedrock client's own resources. */
  private static final String STOP_TEXTURE = "textures/blocks/barrier";

  private final Plugin plugin;
  private final PaperBedrock bedrock;
  private final NavigationMenus<Player> menus;
  private final TripManager<Entity, PaperTripAgent, Location> trips;
  private final Messages messages;

  PaperMenuForms(
      Plugin plugin,
      PaperBedrock bedrock,
      NavigationMenus<Player> menus,
      TripManager<Entity, PaperTripAgent, Location> trips,
      Messages messages) {
    this.plugin = plugin;
    this.bedrock = bedrock;
    this.menus = menus;
    this.trips = trips;
    this.messages = messages;
  }

  /**
   * Sends a player their menu as it is now. Call on the player's own thread.
   *
   * @param player the player
   * @param menu their menu
   */
  void show(Player player, NavigationMenu menu) {
    Locale locale = player.locale();
    List<Trip<Entity, PaperTripAgent, Location>> running =
        menu.atTop() ? running(player) : List.of();
    List<String> labels = TripLabels.shorten(running.stream().map(Trip::label).toList());
    List<Component> names = menus.list(player, menu); // before content, which asks if it was empty

    SimpleForm.Builder form =
        SimpleForm.builder()
            .title(LEGACY.serialize(render(menu.title(), locale)))
            .content(content(menu, locale));
    for (String label : labels) {
      Component stop = messages.renderBare(locale, CobblestoneMessages.MENU_STOP, label);
      form.button(plain(stop, locale), FormImage.Type.PATH, STOP_TEXTURE);
    }
    names.forEach(name -> form.button(plain(name, locale)));
    form.validResultHandler(
        response ->
            player
                .getScheduler()
                .run(
                    plugin,
                    task -> pressed(player, menu, running, labels, response.clickedButtonId()),
                    null));
    bedrock.sendForm(player.getUniqueId(), form.build());
  }

  /** Handles a press, back on the player's thread. */
  private void pressed(
      Player player,
      NavigationMenu menu,
      List<Trip<Entity, PaperTripAgent, Location>> running,
      List<String> labels,
      int index) {
    if (index < running.size()) {
      Trip<Entity, PaperTripAgent, Location> trip = running.get(index);
      if (trips.trips(player.getUniqueId()).contains(trip)) {
        trips.stop(trip);
        messages.send(
            player, player.locale(), CobblestoneMessages.HOLOGRAM_CANCELED, labels.get(index));
      }
      return;
    }
    menus.choose(player, menu, index - running.size(), () -> show(player, menu));
  }

  /** The player's trips in the order they started, as their labels list them. */
  private List<Trip<Entity, PaperTripAgent, Location>> running(Player player) {
    List<Trip<Entity, PaperTripAgent, Location>> running =
        new ArrayList<>(trips.trips(player.getUniqueId()));
    running.sort(Comparator.comparingInt(Trip::id));
    return running;
  }

  /** The text above the buttons: where in the menu the player is, and whether it is empty. */
  private String content(NavigationMenu menu, Locale locale) {
    List<String> lines = new ArrayList<>();
    if (!menu.atTop()) {
      menu.breadcrumb().ifPresent(trail -> lines.add(LEGACY.serialize(render(trail, locale))));
    }
    if (menu.empty()) {
      lines.add(plain(messages.renderBare(locale, CobblestoneMessages.MENU_EMPTY), locale));
    }
    return String.join("\n", lines);
  }

  private static Component render(Component component, Locale locale) {
    return GlobalTranslator.render(component, locale);
  }

  /** Plain, since colors meant for the dark tooltip of a chest are hard to read on a button. */
  private static String plain(Component component, Locale locale) {
    return PlainTextComponentSerializer.plainText().serialize(render(component, locale));
  }
}
