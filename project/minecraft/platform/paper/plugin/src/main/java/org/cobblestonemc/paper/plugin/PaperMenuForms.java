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
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.paper.plugin.api.PaperIcon;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.config.MenuIcons;
import org.cobblestonemc.plugin.menu.BedrockTextures;
import org.cobblestonemc.plugin.menu.MenuButton;
import org.cobblestonemc.plugin.menu.MenuSettings;
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
 * The navigation menu as a Bedrock form: a scrolling list of buttons, each with its item's picture
 * where Bedrock has one, which phones and consoles draw far better than a chest. A press is handed
 * to the {@link NavigationMenu} as a chest click would be, and anything that changes the menu sends
 * the form again.
 *
 * <p>It lists only what would work to press. While the player has trips running, the top level
 * opens with a button to stop one, since a Bedrock player has no label {@code Cancel} to hit: the
 * trip itself, if there is only one, or else a list of them to choose from.
 *
 * <p>Only loaded when Geyser or Floodgate is here, since it builds their forms.
 */
final class PaperMenuForms {

  private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

  /** Starts a button's second line: dark gray, like lore, on the button's light face. */
  private static final String SECOND_LINE = "\n§8";

  private final Plugin plugin;
  private final PaperBedrock bedrock;
  private final NavigationMenus<Player> menus;
  private final TripManager<Entity, PaperTripAgent, Location> trips;
  private final Messages messages;
  private final Supplier<MenuSettings> settings;

  PaperMenuForms(
      Plugin plugin,
      PaperBedrock bedrock,
      NavigationMenus<Player> menus,
      TripManager<Entity, PaperTripAgent, Location> trips,
      Messages messages,
      Supplier<MenuSettings> settings) {
    this.plugin = plugin;
    this.bedrock = bedrock;
    this.menus = menus;
    this.trips = trips;
    this.messages = messages;
    this.settings = settings;
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
    List<MenuButton> buttons = menus.list(player, menu);

    SimpleForm.Builder form = form(menu.title(), content(menu, locale), locale);
    List<Runnable> stops = new ArrayList<>();
    Icon stop = settings.get().icons().apply(MenuIcons.CLOSE);
    if (running.size() == 1) {
      button(form, stopText(labels.getFirst(), locale), List.of(stop));
      stops.add(() -> stop(player, running.getFirst(), labels.getFirst()));
    } else if (running.size() > 1) {
      button(
          form,
          plain(messages.renderBare(locale, CobblestoneMessages.MENU_STOP_ANY), locale),
          List.of(stop));
      stops.add(() -> showTrips(player, menu, running, labels));
    }
    for (MenuButton button : buttons) {
      button(form, text(button, locale), button.pictures());
    }
    send(
        player,
        form,
        index -> {
          if (index < stops.size()) {
            stops.get(index).run();
          } else {
            menus.choose(player, menu, index - stops.size(), () -> show(player, menu));
          }
        });
  }

  /** Sends the list of the player's trips, one to stop, with a way back to the menu. */
  private void showTrips(
      Player player,
      NavigationMenu menu,
      List<Trip<Entity, PaperTripAgent, Location>> running,
      List<String> labels) {
    Locale locale = player.locale();
    SimpleForm.Builder form =
        form(
            menu.title(),
            plain(messages.renderBare(locale, CobblestoneMessages.MENU_STOP_ANY), locale),
            locale);
    form.button("« " + plain(messages.renderBare(locale, CobblestoneMessages.MENU_BACK), locale));
    Icon stop = settings.get().icons().apply(MenuIcons.CLOSE);
    for (String label : labels) {
      button(form, stopText(label, locale), List.of(stop));
    }
    send(
        player,
        form,
        index -> {
          if (index == 0) {
            show(player, menu);
          } else {
            stop(player, running.get(index - 1), labels.get(index - 1));
          }
        });
  }

  /** Stops a trip, if it is still running, and says so as the label named it. */
  private void stop(Player player, Trip<Entity, PaperTripAgent, Location> trip, String label) {
    if (trips.trips(player.getUniqueId()).contains(trip)) {
      trips.stop(trip);
      messages.send(player, player.locale(), CobblestoneMessages.HOLOGRAM_CANCELED, label);
    }
  }

  private String stopText(String label, Locale locale) {
    return plain(messages.renderBare(locale, CobblestoneMessages.MENU_STOP, label), locale);
  }

  private static SimpleForm.Builder form(Component title, String content, Locale locale) {
    return SimpleForm.builder().title(LEGACY.serialize(render(title, locale))).content(content);
  }

  /** Sends a form whose presses are handled back on the player's thread. */
  private void send(Player player, SimpleForm.Builder form, IntConsumer pressed) {
    form.validResultHandler(
        response ->
            player
                .getScheduler()
                .run(plugin, task -> pressed.accept(response.clickedButtonId()), null));
    bedrock.sendForm(player.getUniqueId(), form.build());
  }

  /** The player's trips in the order they started, as their labels list them. */
  private List<Trip<Entity, PaperTripAgent, Location>> running(Player player) {
    List<Trip<Entity, PaperTripAgent, Location>> running =
        new ArrayList<>(trips.trips(player.getUniqueId()));
    running.sort(Comparator.comparingInt(Trip::id));
    return running;
  }

  /** The text above the buttons: where in the tree the player is, and whether it is empty. */
  private String content(NavigationMenu menu, Locale locale) {
    List<String> lines = new ArrayList<>();
    menu.breadcrumb().ifPresent(trail -> lines.add(LEGACY.serialize(render(trail, locale))));
    if (menu.empty()) {
      lines.add(plain(messages.renderBare(locale, CobblestoneMessages.MENU_EMPTY), locale));
    }
    return String.join("\n", lines);
  }

  /**
   * A button's text: its name, then its detail beneath. Both plain, since colors meant for the dark
   * tooltip of a chest are hard to read on a button's light face.
   */
  private static String text(MenuButton button, Locale locale) {
    String name = plain(button.name(), locale);
    return button.detail().map(detail -> name + SECOND_LINE + plain(detail, locale)).orElse(name);
  }

  /** Adds a button with the picture of the first of its items that Bedrock has one of, if any. */
  private static void button(SimpleForm.Builder form, String text, List<Icon> pictures) {
    pictures.stream()
        .map(icon -> BedrockTextures.of(item(icon)))
        .flatMap(Optional::stream)
        .findFirst()
        .ifPresentOrElse(
            path -> form.button(text, FormImage.Type.PATH, path), () -> form.button(text));
  }

  /** An icon as the plain item it is, which is all a Bedrock texture can stand for. */
  private static Icon item(Icon icon) {
    return icon instanceof PaperIcon paper
        ? Icon.item(paper.stack().getType().getKey().asString())
        : icon;
  }

  private static Component render(Component component, Locale locale) {
    return GlobalTranslator.render(component, locale);
  }

  private static String plain(Component component, Locale locale) {
    return PlainTextComponentSerializer.plainText().serialize(render(component, locale));
  }
}
