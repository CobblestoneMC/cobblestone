/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.plugin.api.DestinationEntry;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.cobblestonemc.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.config.MenuIcons;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.message.Messages;
import org.junit.jupiter.api.Test;

/** The navigation menu's layout and behavior, without a server. */
class NavigationMenuTest {

  private static final Messages MESSAGES = new Messages(Locale.ENGLISH, true, new QuietLogger());
  private static final MenuSettings SETTINGS =
      new MenuSettings(
          Component.text("Navigation Menu"),
          Icon.item(MenuIcons.FILLER_DEFAULT),
          true,
          name -> Icon.item(MenuIcons.DEFAULTS.get(name).item()));

  /** Every destination usable, as a list tests them. */
  private static final Predicate<DestinationResolver.Resolved<NavigationTarget>> USABLE =
      pick -> true;

  /** Where the first entry inside the border sits. */
  private static final int FIRST = MenuLayout.slot(1, 1);

  @Test
  void theTopLevelListsEachOwnerAlphabetically() {
    NavigationMenu menu =
        menu(
            roots(
                Map.of(
                    "towny", level().destination("spawn", destination()),
                    "cobblestone", level().destination("death", destination()),
                    "essentials", level().destination("spawn", destination()))));
    Map<Integer, MenuItem> items = menu.render();
    assertEquals("cobblestone »", name(items.get(FIRST)));
    assertEquals("essentials »", name(items.get(FIRST + 1)));
    assertEquals("towny »", name(items.get(FIRST + 2)));
    assertEquals(SETTINGS.filler(), items.get(MenuLayout.BACK).icon(), "nothing to go back to");
  }

  @Test
  void aLevelWithOneWayOnIsSkipped() {
    // One owner, whose only entry is a level: straight through to what is inside it.
    NavigationMenu menu =
        menu(
            roots(
                Map.of(
                    "essentials",
                    level()
                        .subtree(
                            "home", () -> level().destination("base", destination()).build()))));
    Map<Integer, MenuItem> items = menu.render();
    assertEquals("base", name(items.get(FIRST)));
    assertEquals("essentials › home", name(items.get(MenuLayout.BREADCRUMB)));
    assertEquals(Icon.item("minecraft:nether_star"), items.get(MenuLayout.BREADCRUMB).icon());
  }

  @Test
  void anEntryThatIsAlsoADestinationListsItselfFirst() {
    DestinationTree<NavigationTarget> riverwood =
        level()
            .destination("home", destination())
            .subtree(
                "outpost",
                () ->
                    level()
                        .destination("10", destination())
                        .destination("2", destination())
                        .destination("1", destination())
                        .build())
            .build();
    DestinationTree.Builder<NavigationTarget> towns = level();
    towns.entry(
        "riverwood",
        DestinationEntry.<NavigationTarget>builder()
            .destination(destination())
            .subtree(() -> riverwood));
    towns.destination("ashford", destination());
    NavigationMenu menu =
        menu(
            roots(
                Map.of(
                    "towny",
                    level().subtree("town", towns),
                    "other",
                    level().destination("elsewhere", destination()))));

    menu.render();
    assertInstanceOf(MenuAction.Redraw.class, menu.click(FIRST + 1)); // towny (after "other")
    Map<Integer, MenuItem> towny = menu.render();
    assertEquals("towny › town", name(towny.get(MenuLayout.BREADCRUMB)), "the lone town level");
    assertEquals("ashford", name(towny.get(FIRST)));
    assertEquals("riverwood »", name(towny.get(FIRST + 1)));

    menu.click(FIRST + 1);
    Map<Integer, MenuItem> inside = menu.render();
    assertEquals("riverwood", name(inside.get(FIRST)), "the town itself, ahead of its contents");
    assertEquals("home", name(inside.get(FIRST + 1)));
    assertEquals("outpost »", name(inside.get(FIRST + 2)));
    assertInstanceOf(MenuAction.Navigate.class, menu.click(FIRST));

    menu.click(FIRST + 2);
    Map<Integer, MenuItem> outposts = menu.render();
    assertEquals(
        List.of("1", "2", "10"),
        List.of(
            name(outposts.get(FIRST)),
            name(outposts.get(FIRST + 1)),
            name(outposts.get(FIRST + 2))));

    assertInstanceOf(MenuAction.Redraw.class, menu.click(MenuLayout.BACK));
    assertEquals("riverwood", name(menu.render().get(FIRST)));
  }

  @Test
  void destinationsThePlayerMayNotUseAreLeftOut() {
    DestinationTree.Builder<NavigationTarget> cobblestone =
        level()
            .destination("death", destination())
            .destination(
                "vault", DestinationLeaf.<NavigationTarget>of(() -> null).withPermissions("vault"))
            .entry(
                "spawn",
                DestinationEntry.<NavigationTarget>builder()
                    .destination(destination())
                    .subtree(level().destination("inner", destination())));
    NavigationMenu menu =
        new NavigationMenu(
            Locale.ENGLISH,
            MESSAGES,
            SETTINGS,
            roots(Map.of("cobblestone", cobblestone)),
            Map.of(),
            List.of(),
            permission -> false,
            address -> !address.getLast().equals("spawn"));
    Map<Integer, MenuItem> items = menu.render();
    assertEquals("death", name(items.get(FIRST)));
    assertEquals("spawn »", name(items.get(FIRST + 1)), "still a level, no longer a destination");
    assertNull(items.get(FIRST + 2));

    menu.click(FIRST + 1);
    assertEquals("inner", name(menu.render().get(FIRST)), "and opens without itself first");
  }

  @Test
  void levelsWithNothingThePlayerMayPickAreLeftOut() {
    DestinationLeaf<NavigationTarget> vault =
        DestinationLeaf.<NavigationTarget>of(() -> null).withPermissions("vault");
    NavigationMenu menu =
        new NavigationMenu(
            Locale.ENGLISH,
            MESSAGES,
            SETTINGS,
            roots(
                Map.of(
                    "bank",
                    level().destination("vault", vault),
                    "cobblestone",
                    level()
                        .destination("death", destination())
                        .subtree("vaults", () -> level().destination("vault", vault).build())
                        .subtree(
                            "deeper",
                            () ->
                                level()
                                    .subtree(
                                        "vaults", () -> level().destination("vault", vault).build())
                                    .build()))),
            Map.of(),
            List.of(),
            permission -> false,
            address -> true);
    Map<Integer, MenuItem> items = menu.render();
    // "bank" has nothing to offer, so the top level has one way on and is skipped.
    assertEquals("cobblestone", name(items.get(MenuLayout.BREADCRUMB)));
    assertEquals("death", name(items.get(FIRST)));
    // However deep: "deeper" leads only to another level of what the player may not use.
    assertNull(items.get(FIRST + 1), "vaults and deeper hold nothing the player may use");
  }

  @Test
  void recentsThePlayerMayNoLongerUseAreSkipped() {
    NavigationMenu menu =
        new NavigationMenu(
            Locale.ENGLISH,
            MESSAGES,
            SETTINGS,
            roots(
                Map.of(
                    "cobblestone",
                    level()
                        .destination("death", destination())
                        .destination("spawn", destination()))),
            Map.of(),
            List.of(
                new RecentDestinations.Recent(List.of("cobblestone", "spawn"), List.of()),
                new RecentDestinations.Recent(List.of("cobblestone", "death"), List.of())),
            permission -> true,
            address -> !address.getLast().equals("spawn"));
    Map<Integer, MenuItem> items = menu.render();
    assertEquals("death", name(items.get(FIRST)));
    assertNull(items.get(FIRST + 1), "only one recent left");
  }

  @Test
  void aDestinationThatNeedsMoreAsksForIt() {
    DestinationOption<Integer> x = DestinationOption.integer("x", () -> 5);
    DestinationLeaf<NavigationTarget> block =
        DestinationLeaf.withOptions(List.of(x), values -> null);
    NavigationMenu menu =
        menu(
            roots(
                Map.of(
                    "cobblestone",
                    level().destination("block", block).destination("death", destination()))));
    Map<Integer, MenuItem> items = menu.render();
    assertTrue(
        items.get(FIRST).lore().stream()
            .map(NavigationMenuTest::plain)
            .anyMatch(line -> line.contains("<x>")));
    MenuAction.FillIn fillIn = assertInstanceOf(MenuAction.FillIn.class, menu.click(FIRST));
    assertEquals(List.of("cobblestone", "block"), fillIn.address());
    assertEquals(
        List.of(new OptionForm.Field("x", true, List.of(), "5")), OptionForm.fields(block));
  }

  @Test
  void recentsOpenTheFirstPageAndPagesTurn() {
    DestinationOption<Integer> x = DestinationOption.integer("x", () -> 0);
    DestinationTree.Builder<NavigationTarget> cobblestone = level();
    cobblestone.destination("block", DestinationLeaf.withOptions(List.of(x), values -> null));
    for (int i = 0; i < 40; i++) {
      cobblestone.destination("place-" + i, destination());
    }
    List<RecentDestinations.Recent> recents =
        List.of(
            new RecentDestinations.Recent(List.of("cobblestone", "block"), List.of("12")),
            new RecentDestinations.Recent(List.of("cobblestone", "gone"), List.of()),
            new RecentDestinations.Recent(List.of("cobblestone", "place-3"), List.of()));
    NavigationMenu menu =
        new NavigationMenu(
            Locale.ENGLISH,
            MESSAGES,
            SETTINGS,
            roots(Map.of("cobblestone", cobblestone)),
            Map.of(),
            recents,
            permission -> true,
            address -> true);

    Map<Integer, MenuItem> first = menu.render();
    assertEquals("block", name(first.get(FIRST)));
    assertEquals(
        "place-3", name(first.get(FIRST + 1)), "a recent that no longer exists is skipped");
    assertNull(first.get(FIRST + 2));
    assertEquals(SETTINGS.filler(), first.get(MenuLayout.slot(2, 1)).icon(), "the gap");
    assertEquals("block", name(first.get(MenuLayout.slot(3, 1))), "entries below the gap");
    MenuAction.Navigate again = assertInstanceOf(MenuAction.Navigate.class, menu.click(FIRST));
    assertEquals(List.of("12"), again.destination().optionTokens());
    assertEquals(12, again.destination().values().get(x));

    // 41 entries: 14 on the first page, then 27 on the second (all 28 of its slots but one).
    assertEquals(SETTINGS.filler(), first.get(MenuLayout.PREVIOUS).icon());
    menu.click(MenuLayout.NEXT);
    Map<Integer, MenuItem> second = menu.render();
    assertEquals("place-13", name(second.get(FIRST)), "the recents are gone, so the page is full");
    assertEquals(SETTINGS.filler(), second.get(MenuLayout.NEXT).icon());
    assertTrue(plain(second.get(MenuLayout.PREVIOUS).lore().getFirst()).contains("2 of 2"));
  }

  @Test
  void eachEntryUsesItsOwnDisplay() {
    Display display =
        Display.of(Icon.head("abc"))
            .withLabel(Component.text("Home Sweet Home"))
            .withDescription(List.of(Component.text("Where the heart is")));
    DestinationTree.Builder<NavigationTarget> homes = level();
    homes.entry(
        "base",
        DestinationEntry.<NavigationTarget>builder()
            .destination(destination())
            .display(display)
            .build());
    NavigationMenu menu = menu(roots(Map.of("essentials", homes)));
    MenuItem item = menu.render().get(FIRST);
    assertEquals(Icon.head("abc"), item.icon());
    assertEquals("Home Sweet Home", name(item));
    assertEquals("Where the heart is", plain(item.lore().getFirst()));
  }

  @Test
  void aListOpensRecentsFromAButtonThenListsTheWholeLevelUnpaged() {
    DestinationOption<Integer> x = DestinationOption.integer("x", () -> 0);
    DestinationTree.Builder<NavigationTarget> cobblestone = level();
    cobblestone.destination("block", DestinationLeaf.withOptions(List.of(x), values -> null));
    for (int i = 0; i < 40; i++) {
      cobblestone.destination("place-" + i, destination());
    }
    NavigationMenu menu =
        new NavigationMenu(
            Locale.ENGLISH,
            MESSAGES,
            SETTINGS,
            roots(Map.of("cobblestone", cobblestone)),
            Map.of(),
            List.of(
                new RecentDestinations.Recent(List.of("cobblestone", "place-7"), List.of()),
                new RecentDestinations.Recent(List.of("cobblestone", "block"), List.of("12"))),
            permission -> true,
            address -> true);

    List<MenuButton> top = menu.list(USABLE);
    assertTrue(menu.atTop());
    assertEquals(42, top.size(), "recents, then the block and all 40 places");
    assertEquals("Recents »", plain(top.getFirst().name()));
    assertEquals(List.of(Icon.item("minecraft:clock")), top.getFirst().pictures());
    assertEquals("block", plain(top.get(1).name()));
    assertEquals("place-0", plain(top.get(2).name()));
    assertTrue(top.get(2).detail().isEmpty(), "no hint to click a button");
    assertEquals(
        List.of(Icon.item("minecraft:ender_eye"), Icon.item("minecraft:ender_eye")),
        top.get(2).pictures(),
        "its own item, then the fallback");
    assertEquals("place-39", plain(top.getLast().name()), "past where a chest would page");
    assertInstanceOf(MenuAction.Navigate.class, menu.choose(41));
    assertInstanceOf(MenuAction.None.class, menu.choose(42));

    assertInstanceOf(MenuAction.Redraw.class, menu.choose(0));
    List<MenuButton> recents = menu.list(USABLE);
    assertFalse(menu.atTop());
    assertEquals("Recents", plain(menu.breadcrumb().orElseThrow()));
    assertEquals(
        List.of("« Back", "place-7", "block"),
        recents.stream().map(button -> plain(button.name())).toList());
    assertTrue(recents.get(0).pictures().isEmpty(), "back has no picture");
    assertTrue(recents.get(1).detail().isEmpty());
    assertEquals("12", plain(recents.get(2).detail().orElseThrow()), "the options it was given");
    MenuAction.Navigate again = assertInstanceOf(MenuAction.Navigate.class, menu.choose(2));
    assertEquals(List.of("12"), again.destination().optionTokens());

    assertInstanceOf(MenuAction.Redraw.class, menu.choose(0));
    assertTrue(menu.atTop());
    assertEquals(42, menu.list(USABLE).size());
  }

  @Test
  void aListBelowTheTopStartsWithBack() {
    NavigationMenu menu =
        menu(
            roots(
                Map.of(
                    "towny", level().destination("spawn", destination()),
                    "essentials", level().destination("warp", destination()))));
    List<MenuButton> top = menu.list(USABLE);
    assertTrue(menu.breadcrumb().isEmpty());
    assertEquals("essentials »", plain(top.getFirst().name()));
    assertEquals(Icon.item("minecraft:chest"), top.getFirst().pictures().getLast());

    assertInstanceOf(MenuAction.Redraw.class, menu.choose(1));
    List<MenuButton> towny = menu.list(USABLE);
    assertFalse(menu.atTop());
    assertEquals("towny", plain(menu.breadcrumb().orElseThrow()));
    assertEquals(
        List.of("« Back", "spawn"), towny.stream().map(button -> plain(button.name())).toList());
    assertTrue(towny.getFirst().pictures().isEmpty());
    assertInstanceOf(MenuAction.Redraw.class, menu.choose(0));
    assertEquals(2, menu.list(USABLE).size(), "back on top");
  }

  @Test
  void aListLeavesOutWhatWouldNotWorkToPress() {
    DestinationOption<Integer> x = DestinationOption.integer("x", () -> 0);
    NavigationMenu menu =
        new NavigationMenu(
            Locale.ENGLISH,
            MESSAGES,
            SETTINGS,
            roots(
                Map.of(
                    "cobblestone",
                    level()
                        .destination("surface", destination())
                        .destination("spawn", destination())
                        .destination(
                            "block", DestinationLeaf.withOptions(List.of(x), values -> null))
                        .subtree(
                            "caves", () -> level().destination("surface", destination()).build()),
                    "towny",
                    level().destination("surface", destination()))),
            Map.of(),
            List.of(new RecentDestinations.Recent(List.of("cobblestone", "surface"), List.of())),
            permission -> true,
            address -> true);
    Predicate<DestinationResolver.Resolved<NavigationTarget>> notSurface =
        pick -> !pick.address().getLast().equals("surface");

    List<MenuButton> top = menu.list(notSurface);
    assertEquals(
        List.of("cobblestone »"),
        top.stream().map(button -> plain(button.name())).toList(),
        "no recents button, and no towny, with nothing usable in either");
    menu.choose(0);
    assertEquals(
        List.of("« Back", "block", "spawn"),
        menu.list(notSurface).stream().map(button -> plain(button.name())).toList(),
        "a destination that asks first stays; caves holds only the surface");
    assertFalse(menu.empty());
  }

  @Test
  void anEmptyLevelSaysSo() {
    NavigationMenu menu = menu(roots(Map.of()));
    assertTrue(menu.list(USABLE).isEmpty());
    assertTrue(menu.empty());
  }

  @Test
  void recentDestinationsAreNewestFirstAndDistinct() {
    RecentDestinations recents = new RecentDestinations();
    UUID player = UUID.randomUUID();
    for (int i = 0; i < 10; i++) {
      recents.record(player, new RecentDestinations.Recent(List.of("a", "" + i), List.of()));
    }
    recents.record(player, new RecentDestinations.Recent(List.of("a", "5"), List.of()));
    List<String> order =
        recents.get(player).stream().map(recent -> recent.address().getLast()).toList();
    assertEquals(List.of("5", "9", "8", "7", "6", "4", "3"), order);
    assertFalse(recents.get(UUID.randomUUID()).iterator().hasNext());
  }

  // -----------------------------------------------------------------------------------------

  private static NavigationMenu menu(Map<String, DestinationTree<NavigationTarget>> roots) {
    return new NavigationMenu(
        Locale.ENGLISH,
        MESSAGES,
        SETTINGS,
        roots,
        Map.of(),
        List.of(),
        permission -> true,
        address -> true);
  }

  private static Map<String, DestinationTree<NavigationTarget>> roots(
      Map<String, DestinationTree.Builder<NavigationTarget>> builders) {
    Map<String, DestinationTree<NavigationTarget>> roots = new TreeMap<>();
    builders.forEach((key, builder) -> roots.put(key, builder.build()));
    return roots;
  }

  private static DestinationTree.Builder<NavigationTarget> level() {
    return DestinationTree.builder();
  }

  /** A destination that must never be built: the menu only builds what the player picks. */
  private static DestinationLeaf<NavigationTarget> destination() {
    return DestinationLeaf.of(
        () -> {
          throw new AssertionError("built a destination nobody picked");
        });
  }

  private static String name(MenuItem item) {
    return item == null ? null : plain(item.name());
  }

  static String plain(Component component) {
    StringBuilder out = new StringBuilder();
    if (component instanceof TextComponent text) {
      out.append(text.content());
    }
    component.children().forEach(child -> out.append(plain(child)));
    return out.toString();
  }

  static final class QuietLogger extends CobblestoneLogger {
    @Override
    public void trace(String message, Object... args) {}

    @Override
    public void debug(String message, Object... args) {}

    @Override
    public void info(String message, Object... args) {}

    @Override
    public void warn(String message, Object... args) {}

    @Override
    public void error(String message, Throwable throwable, Object... args) {}
  }
}
