/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.api.Validity;
import org.cobblestonemc.plugin.config.MenuIcons;
import org.cobblestonemc.plugin.destination.DestinationProvider;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.message.Messages;
import org.junit.jupiter.api.Test;

/** What the menus do with a click. */
class NavigationMenusTest {

  private static final NavigationTarget SPAWN =
      NavigationTarget.builder("spawn", (Destination<DomainRegion<MinecraftWorld>>) List::of)
          .build();

  /** Where {@code death}, {@code oops} and {@code spawn} sit, alphabetically. */
  private static final int DEATH = MenuLayout.slot(1, 1);

  private static final int OOPS = MenuLayout.slot(1, 2);
  private static final int SPAWN_SLOT = MenuLayout.slot(1, 3);

  private final List<String> done = new ArrayList<>();
  private final Platform platform = new Platform();
  private final NavigationMenus<String> menus =
      new NavigationMenus<>(
          platform,
          () ->
              Map.<String, DestinationProvider<String>>of(
                  "cobblestone",
                  player ->
                      DestinationTree.<NavigationTarget>builder()
                          .destination("spawn", DestinationLeaf.of(() -> SPAWN))
                          .destination(
                              "death",
                              DestinationLeaf.ofValidity(
                                  () -> Validity.invalid(Component.text("You have not died."))))
                          .destination(
                              "oops",
                              DestinationLeaf.of(
                                  () -> {
                                    throw new IllegalStateException("broken");
                                  }))
                          .build()),
          new RecentDestinations(),
          new Messages(Locale.ENGLISH, false, new NavigationMenuTest.QuietLogger()),
          () ->
              new MenuSettings(
                  Component.text("Menu"),
                  Icon.item(MenuIcons.FILLER_DEFAULT),
                  true,
                  name -> Icon.item("minecraft:" + name)),
          new NavigationMenuTest.QuietLogger());

  @Test
  void aPickClosesTheMenuBeforeNavigatingToWhatWasBuilt() {
    NavigationMenu menu = menus.menu("alex");
    menu.render();
    menus.click("alex", menu, SPAWN_SLOT, () -> done.add("redraw"));
    assertEquals(List.of("close", "navigate cobblestone spawn to spawn"), done);

    done.clear();
    menu.render();
    menus.click("alex", menu, MenuLayout.CLOSE, () -> done.add("redraw"));
    assertEquals(List.of("close"), done);
  }

  @Test
  void aPickThatCannotBeGoneToStaysInTheMenuAsABarrierUntilTheNextClick() {
    NavigationMenu menu = menus.menu("alex");
    menu.render();
    menus.click("alex", menu, DEATH, () -> done.add("redraw"));
    assertEquals(List.of("redraw"), done);

    MenuItem refused = menu.render().get(DEATH);
    assertEquals(Icon.item("minecraft:" + MenuIcons.CLOSE), refused.icon());
    assertEquals(
        "You have not died.", NavigationMenuTest.plain(refused.lore().getFirst()), "says why");

    menu.click(OOPS);
    assertEquals(
        Icon.item("minecraft:" + MenuIcons.DESTINATION),
        menu.render().get(DEATH).icon(),
        "back as it was");
  }

  @Test
  void aPickThatFailsToBuildStaysInTheMenuAsABarrier() {
    NavigationMenu menu = menus.menu("alex");
    menu.render();
    menus.click("alex", menu, OOPS, () -> done.add("redraw"));
    assertEquals(List.of("redraw"), done);
    MenuItem refused = menu.render().get(OOPS);
    assertEquals(Icon.item("minecraft:" + MenuIcons.CLOSE), refused.icon());
    assertTrue(!refused.lore().isEmpty(), "says something went wrong");
  }

  /** The platform, whose player is a name, writing down what it is asked to do. */
  private final class Platform implements MenuPlatform<String> {

    @Override
    public Locale locale(String player) {
      return Locale.ENGLISH;
    }

    @Override
    public UUID id(String player) {
      return UUID.nameUUIDFromBytes(player.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Predicate<String> hasPermission(String player) {
      return permission -> true;
    }

    @Override
    public Predicate<List<String>> canNavigate(String player) {
      return address -> true;
    }

    @Override
    public void closeThen(String player, Runnable then) {
      done.add("close");
      then.run();
    }

    @Override
    public void navigate(
        String player,
        DestinationResolver.Resolved<NavigationTarget> pick,
        NavigationTarget destination) {
      done.add(
          "navigate "
              + String.join(" ", pick.address())
              + " to "
              + NavigationMenuTest.plain(destination.displayName()));
    }

    @Override
    public void fillIn(String player, MenuAction.FillIn fillIn) {
      done.add("fill in " + String.join(" ", fillIn.address()));
    }
  }
}
