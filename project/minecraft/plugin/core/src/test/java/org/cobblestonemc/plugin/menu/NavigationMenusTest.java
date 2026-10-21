/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.api.DestinationChild;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationNode;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.config.MenuIcons;
import org.cobblestonemc.plugin.destination.DestinationProvider;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.message.Messages;
import org.junit.jupiter.api.Test;

/** What the menus do with a click, and the items of Cobblestone's own entries. */
class NavigationMenusTest {

  private final List<String> done = new ArrayList<>();

  @Test
  void aPickClosesTheMenuBeforeNavigating() {
    Menus menus = new Menus();
    NavigationMenu menu = menus.menu("alex");
    menu.render();
    menus.handle("alex", menu.click(MenuLayout.slot(1, 1)), () -> done.add("redraw"));
    assertEquals(List.of("close", "navigate cobblestone spawn"), done);

    done.clear();
    menus.handle("alex", new MenuAction.Redraw(), () -> done.add("redraw"));
    menus.handle("alex", new MenuAction.Close(), () -> done.add("redraw"));
    assertEquals(List.of("redraw", "close"), done);
  }

  @Test
  void cobblestonesOwnEntriesTakeTheirConfiguredItem() {
    Display own = Display.of(Icon.item("minecraft:dirt"));
    DestinationNode<String> level =
        MenuIcons.withIcons(
            DestinationNode.<String>builder()
                .destination("spawn", DestinationLeaf.of(() -> "spawn"))
                .destination("market", DestinationLeaf.of(() -> "market"))
                .child(
                    "death",
                    DestinationChild.<String>builder()
                        .destination(() -> "death")
                        .display(own)
                        .build())
                .build(),
            name -> Icon.item("minecraft:" + name));
    assertEquals(Display.of(Icon.item("minecraft:spawn")), level.children().get("spawn").display());
    assertEquals(Display.none(), level.children().get("market").display(), "not one of ours");
    assertEquals(own, level.children().get("death").display(), "already chose its own");
  }

  /** Menus over one provider offering {@code spawn}, whose player is a name. */
  private final class Menus extends NavigationMenus<String> {

    Menus() {
      super(
          () ->
              Map.<String, DestinationProvider<String>>of(
                  "cobblestone",
                  player ->
                      DestinationNode.<NavigationTarget>builder()
                          .destination("spawn", DestinationLeaf.of(() -> null))
                          .build()),
          new RecentDestinations(),
          new Messages(Locale.ENGLISH, true, new NavigationMenuTest.QuietLogger()),
          () ->
              new MenuSettings(
                  Component.text("Menu"),
                  Icon.item(MenuIcons.FILLER_DEFAULT),
                  true,
                  name -> Icon.item("minecraft:stone")));
    }

    @Override
    protected Locale locale(String player) {
      return Locale.ENGLISH;
    }

    @Override
    protected UUID id(String player) {
      return UUID.nameUUIDFromBytes(player.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected Predicate<String> hasPermission(String player) {
      return permission -> true;
    }

    @Override
    protected Predicate<List<String>> canNavigate(String player) {
      return address -> true;
    }

    @Override
    protected void closeThen(String player, Runnable then) {
      done.add("close");
      then.run();
    }

    @Override
    protected void navigate(String player, DestinationResolver.Resolved<NavigationTarget> pick) {
      done.add("navigate " + String.join(" ", pick.address()));
    }

    @Override
    protected void fillIn(String player, MenuAction.FillIn fillIn) {
      done.add("fill in " + String.join(" ", fillIn.address()));
    }
  }
}
