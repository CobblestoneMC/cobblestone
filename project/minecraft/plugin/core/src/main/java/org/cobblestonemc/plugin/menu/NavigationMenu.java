/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.cobblestonemc.plugin.api.DestinationChild;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationNode;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.message.CobblestoneColors;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Message0;
import org.cobblestonemc.plugin.message.Messages;

/**
 * One player's open navigation menu: the levels of the destination tree they have walked into, and
 * the page of each they are on. Platform-neutral — it decides what every slot shows and what every
 * click does, leaving the platform to draw the {@link MenuItem}s and carry out the {@link
 * MenuAction}s — so the menu behaves the same on every server.
 *
 * <h2>What it shows</h2>
 *
 * <p>Each level lists its entries alphabetically (numbers in numeric order, so outpost 2 comes
 * before outpost 10). An entry that holds a further level opens it; one that is only a destination
 * navigates there, or asks for its options first if it has required ones. An entry that is both — a
 * town, which is somewhere to go and also holds its plots — opens, and the level it opens lists the
 * place itself first. A level with a single entry that only leads further is skipped straight
 * through, so the player never clicks through a menu with one choice in it.
 *
 * <p>The first page of the top level opens with the player's recent destinations, so a favorite is
 * one click away.
 *
 * <p>Destinations the player may not navigate to are left out, by the same permissions {@code
 * /navigate} checks. Levels are built only when they are opened, and destinations only once they
 * are picked.
 *
 * <p>Not thread-safe: a menu belongs to one player and is driven from their thread.
 */
public final class NavigationMenu {

  /** What separates the levels of an address in the breadcrumb. */
  private static final String SEPARATOR = " › ";

  /** What follows the name of an entry that opens a further level. */
  private static final String OPENS = " »";

  private static final int FULL_PAGE = MenuLayout.CONTENT_ROWS * MenuLayout.CONTENT_COLUMNS;

  /** The first page of the top level: two rows of entries beneath the recents and a gap. */
  private static final int RECENTS_PAGE = 2 * MenuLayout.CONTENT_COLUMNS;

  private final Locale locale;
  private final Messages messages;
  private final MenuSettings settings;
  private final Map<String, ? extends DestinationNode<NavigationTarget>> roots;
  private final Predicate<String> hasPermission;
  private final Predicate<List<String>> canNavigate;
  private final List<Entry> recents;
  private final Deque<Level> levels = new ArrayDeque<>();
  private final Map<Integer, Runnable> clicks = new HashMap<>();
  private MenuAction pending = new MenuAction.None();

  /**
   * Opens a menu on the top level.
   *
   * @param locale the player's locale
   * @param messages renders the menu's words
   * @param settings how the menu looks
   * @param roots the destination-tree roots from every provider, keyed by owner
   * @param rootDisplays how each root's entry is shown, keyed as {@code roots}
   * @param recents the player's recent destinations, newest first
   * @param hasPermission tests whether the player holds a permission node
   * @param canNavigate tests whether the player may navigate to a canonical address
   */
  public NavigationMenu(
      Locale locale,
      Messages messages,
      MenuSettings settings,
      Map<String, ? extends DestinationNode<NavigationTarget>> roots,
      Map<String, Display> rootDisplays,
      List<RecentDestinations.Recent> recents,
      Predicate<String> hasPermission,
      Predicate<List<String>> canNavigate) {
    this.locale = locale;
    this.messages = messages;
    this.settings = settings;
    this.roots = roots;
    this.hasPermission = hasPermission;
    this.canNavigate = canNavigate;
    this.recents = settings.recents() ? recents(recents) : List.of();

    List<Entry> top = new ArrayList<>();
    roots.forEach(
        (key, root) ->
            top.add(
                new Entry(
                    key,
                    List.of(key),
                    rootDisplays.getOrDefault(key, Display.none()),
                    null,
                    () -> root,
                    List.of())));
    top.sort(Entry.ORDER);
    levels.push(elide(new Level(List.of(), top)));
  }

  /**
   * The menu's title.
   *
   * @return the title
   */
  public Component title() {
    return settings.title();
  }

  /**
   * What every slot shows right now. Slots left out are empty.
   *
   * @return the items, by slot
   */
  public Map<Integer, MenuItem> render() {
    clicks.clear();
    Map<Integer, MenuItem> items = new HashMap<>();
    MenuItem filler = new MenuItem(settings.filler(), Component.text(" "), List.of());
    for (int column = 0; column < MenuLayout.COLUMNS; column++) {
      items.put(MenuLayout.slot(0, column), filler);
      items.put(MenuLayout.slot(MenuLayout.ROWS - 1, column), filler);
    }
    for (int row = 1; row < MenuLayout.ROWS - 1; row++) {
      items.put(MenuLayout.slot(row, 0), filler);
      items.put(MenuLayout.slot(row, MenuLayout.COLUMNS - 1), filler);
    }

    Level level = levels.peek();
    if (!level.trail.isEmpty()) {
      items.put(
          MenuLayout.BREADCRUMB,
          new MenuItem(
              icon(MenuIcons.BREADCRUMB),
              name(Component.text(String.join(SEPARATOR, level.trail), CobblestoneColors.PRIMARY)),
              List.of()));
    }

    int firstRow = 0;
    if (showsRecents()) {
      for (int i = 0; i < recents.size(); i++) {
        put(items, MenuLayout.content(0, i), recents.get(i), true);
      }
      for (int column = 1; column < MenuLayout.COLUMNS - 1; column++) {
        items.put(MenuLayout.slot(2, column), filler);
      }
      firstRow = 2;
    }

    List<Entry> page = page(level);
    for (int i = 0; i < page.size(); i++) {
      put(items, MenuLayout.content(firstRow, i), page.get(i), false);
    }
    if (level.entries.isEmpty()) {
      items.put(
          MenuLayout.EMPTY,
          new MenuItem(
              icon(MenuIcons.CLOSE),
              name(messages.renderBare(locale, CobblestoneMessages.MENU_EMPTY)),
              List.of()));
    }

    if (levels.size() > 1) {
      button(items, MenuLayout.BACK, MenuIcons.BACK, CobblestoneMessages.MENU_BACK, List.of());
      clicks.put(
          MenuLayout.BACK,
          () -> {
            levels.pop();
            pending = new MenuAction.Redraw();
          });
    }
    int pages = pages(level);
    List<Component> pageLine =
        List.of(
            line(
                messages.renderBare(locale, CobblestoneMessages.MENU_PAGE, level.page + 1, pages)));
    if (level.page > 0) {
      button(
          items, MenuLayout.PREVIOUS, MenuIcons.PAGE, CobblestoneMessages.MENU_PREVIOUS, pageLine);
      clicks.put(MenuLayout.PREVIOUS, () -> turn(level, -1));
    }
    if (level.page < pages - 1) {
      button(items, MenuLayout.NEXT, MenuIcons.PAGE, CobblestoneMessages.MENU_NEXT, pageLine);
      clicks.put(MenuLayout.NEXT, () -> turn(level, 1));
    }
    button(items, MenuLayout.CLOSE, MenuIcons.CLOSE, CobblestoneMessages.MENU_CLOSE, List.of());
    clicks.put(MenuLayout.CLOSE, () -> pending = new MenuAction.Close());
    return items;
  }

  /**
   * Handles a click on a slot of the menu as it was last {@linkplain #render rendered}.
   *
   * @param slot the slot clicked
   * @return what the platform should do next
   */
  public MenuAction click(int slot) {
    Runnable click = clicks.get(slot);
    if (click == null) {
      return new MenuAction.None();
    }
    pending = new MenuAction.None();
    click.run();
    return pending;
  }

  // -----------------------------------------------------------------------------------------
  // Levels and pages
  // -----------------------------------------------------------------------------------------

  /** Opens the level beneath an entry, skipping through any that offer only one way on. */
  private Level open(Entry entry) {
    DestinationNode<NavigationTarget> node = entry.level.get();
    List<Entry> entries = new ArrayList<>();
    node.children()
        .forEach(
            (key, child) -> {
              Entry seen = entry(entry.address, key, child);
              if (seen != null) {
                entries.add(seen);
              }
            });
    entries.sort(Entry.ORDER);
    if (entry.destination != null) {
      // Somewhere to go in its own right as well as a level: the place itself comes first.
      entries.addFirst(
          new Entry(entry.key, entry.address, entry.display, entry.destination, null, List.of()));
    }
    return elide(new Level(entry.address, entries));
  }

  private Level elide(Level level) {
    while (level.entries.size() == 1) {
      Entry only = level.entries.getFirst();
      if (only.destination != null || only.level == null) {
        break;
      }
      level = open(only);
    }
    return level;
  }

  /**
   * An entry as this player may see it: without its destination if they may not navigate there, and
   * not at all if that leaves nothing.
   */
  private Entry entry(List<String> parent, String key, DestinationChild<NavigationTarget> child) {
    List<String> address = append(parent, key);
    DestinationLeaf<NavigationTarget> destination =
        child.destination().filter(leaf -> allowed(address, leaf)).orElse(null);
    Supplier<? extends DestinationNode<NavigationTarget>> level = child.children().orElse(null);
    if (destination == null && level == null) {
      return null;
    }
    return new Entry(key, address, child.display(), destination, level, List.of());
  }

  private boolean allowed(List<String> address, DestinationLeaf<NavigationTarget> destination) {
    return destination.permissions().stream().allMatch(hasPermission) && canNavigate.test(address);
  }

  private boolean showsRecents() {
    return levels.size() == 1 && levels.peek().page == 0 && !recents.isEmpty();
  }

  private int capacity(int page) {
    return levels.size() == 1 && page == 0 && !recents.isEmpty() ? RECENTS_PAGE : FULL_PAGE;
  }

  private List<Entry> page(Level level) {
    int start = level.page == 0 ? 0 : capacity(0) + (level.page - 1) * FULL_PAGE;
    int end = Math.min(level.entries.size(), start + capacity(level.page));
    return start >= end ? List.of() : level.entries.subList(start, end);
  }

  private int pages(Level level) {
    int beyondFirst = level.entries.size() - capacity(0);
    return beyondFirst <= 0 ? 1 : 1 + (beyondFirst + FULL_PAGE - 1) / FULL_PAGE;
  }

  private void turn(Level level, int by) {
    level.page = Math.max(0, Math.min(pages(level) - 1, level.page + by));
    pending = new MenuAction.Redraw();
  }

  /** The player's recent destinations that still exist and that they may still navigate to. */
  private List<Entry> recents(List<RecentDestinations.Recent> recents) {
    List<Entry> entries = new ArrayList<>();
    for (RecentDestinations.Recent recent : recents) {
      DestinationChild<NavigationTarget> child =
          DestinationResolver.<NavigationTarget>find(roots, recent.address()).orElse(null);
      DestinationLeaf<NavigationTarget> destination =
          child == null ? null : child.destination().orElse(null);
      if (destination != null && allowed(recent.address(), destination)) {
        entries.add(
            new Entry(
                recent.address().getLast(),
                recent.address(),
                child.display(),
                destination,
                null,
                recent.optionTokens()));
      }
    }
    return List.copyOf(entries.subList(0, Math.min(entries.size(), RecentDestinations.CAPACITY)));
  }

  // -----------------------------------------------------------------------------------------
  // Items
  // -----------------------------------------------------------------------------------------

  private void put(Map<Integer, MenuItem> items, int slot, Entry entry, boolean recent) {
    boolean opens = entry.level != null;
    Icon icon =
        entry
            .display
            .icon()
            .orElseGet(() -> icon(opens ? MenuIcons.FOLDER : MenuIcons.DESTINATION));
    Component label = entry.display.label().orElseGet(() -> Component.text(entry.key));
    Component name =
        opens ? name(label.append(Component.text(OPENS, CobblestoneColors.PRIMARY))) : name(label);

    List<Component> lore = new ArrayList<>();
    entry.display.description().forEach(line -> lore.add(line(line)));
    if (recent) {
      List<String> typed = new ArrayList<>(entry.address);
      typed.addAll(entry.optionTokens);
      lore.add(
          line(
              messages.renderBare(
                  locale, CobblestoneMessages.MENU_RECENT, String.join(" ", typed))));
    }
    if (opens) {
      lore.add(line(messages.renderBare(locale, CobblestoneMessages.MENU_OPEN)));
      clicks.put(
          slot,
          () -> {
            levels.push(open(entry));
            pending = new MenuAction.Redraw();
          });
    } else if (entry.optionTokens.isEmpty() && OptionForm.asks(entry.destination)) {
      String usage = DestinationResolver.usage(List.of(), entry.destination.options()).trim();
      lore.add(line(messages.renderBare(locale, CobblestoneMessages.MENU_FILL_IN, usage)));
      clicks.put(
          slot, () -> pending = new MenuAction.FillIn(entry.address, entry.destination, label));
    } else {
      lore.add(line(messages.renderBare(locale, CobblestoneMessages.MENU_NAVIGATE)));
      clicks.put(slot, () -> pending = navigate(entry, label));
    }
    items.put(slot, new MenuItem(icon, name, lore));
  }

  /**
   * Navigates to a destination with the options it was last given — or, if those no longer fit it,
   * asks for them afresh.
   */
  private static MenuAction navigate(Entry entry, Component label) {
    if (DestinationResolver.options(entry.address, entry.destination, entry.optionTokens)
        instanceof DestinationResolver.Resolved<NavigationTarget> resolved) {
      return new MenuAction.Navigate(resolved);
    }
    return new MenuAction.FillIn(entry.address, entry.destination, label);
  }

  private void button(
      Map<Integer, MenuItem> items, int slot, String icon, Message0 label, List<Component> lore) {
    items.put(slot, new MenuItem(icon(icon), name(messages.renderBare(locale, label)), lore));
  }

  private Icon icon(String name) {
    return settings.icons().apply(name);
  }

  /** An item name: upright and white unless the text says otherwise. */
  private static Component name(Component text) {
    return Component.empty()
        .decoration(TextDecoration.ITALIC, false)
        .color(NamedTextColor.WHITE)
        .append(text);
  }

  /** A line of an item's lore: upright and gray unless the text says otherwise. */
  private static Component line(Component text) {
    return Component.empty()
        .decoration(TextDecoration.ITALIC, false)
        .color(CobblestoneColors.INFO)
        .append(text);
  }

  private static List<String> append(List<String> address, String key) {
    List<String> out = new ArrayList<>(address.size() + 1);
    out.addAll(address);
    out.add(key);
    return List.copyOf(out);
  }

  /**
   * One entry as the menu shows it.
   *
   * @param key its key, which names it when its display does not
   * @param address its canonical address
   * @param display how it is shown
   * @param destination where it goes, if it is a destination this player may use
   * @param level the level it opens, if any
   * @param optionTokens for a recent destination, the options it was last given
   */
  private record Entry(
      String key,
      List<String> address,
      Display display,
      DestinationLeaf<NavigationTarget> destination,
      Supplier<? extends DestinationNode<NavigationTarget>> level,
      List<String> optionTokens) {

    static final Comparator<Entry> ORDER = Comparator.comparing(Entry::key, NaturalOrder.INSTANCE);
  }

  /** One level of the tree, as the player walked into it, and the page of it they are on. */
  private static final class Level {
    private final List<String> trail;
    private final List<Entry> entries;
    private int page;

    Level(List<String> trail, List<Entry> entries) {
      this.trail = trail;
      this.entries = List.copyOf(entries);
    }
  }
}
