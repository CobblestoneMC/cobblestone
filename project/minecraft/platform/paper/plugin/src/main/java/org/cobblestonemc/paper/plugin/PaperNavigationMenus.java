/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.paper.plugin.api.PaperIcon;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.menu.MenuAction;
import org.cobblestonemc.plugin.menu.MenuItem;
import org.cobblestonemc.plugin.menu.MenuLayout;
import org.cobblestonemc.plugin.menu.MenuSettings;
import org.cobblestonemc.plugin.menu.NavigationMenu;
import org.cobblestonemc.plugin.menu.NavigationMenus;
import org.cobblestonemc.plugin.menu.RecentDestinations;
import org.cobblestonemc.plugin.message.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * The navigation menu on Paper: a double chest drawn from a {@link NavigationMenu}, whose clicks it
 * hands back to it. Nothing can be taken out of or put into the chest; every click is cancelled and
 * only read.
 */
final class PaperNavigationMenus extends NavigationMenus<Player> implements Listener {

  private final Plugin plugin;
  private final NavigateCommand navigate;
  private final Messages messages;

  PaperNavigationMenus(
      Plugin plugin,
      PaperIntegrationRegistry integrations,
      NavigateCommand navigate,
      RecentDestinations recents,
      Messages messages,
      Supplier<MenuSettings> settings) {
    super(integrations::destinationProviders, recents, messages, settings);
    this.plugin = plugin;
    this.navigate = navigate;
    this.messages = messages;
  }

  /**
   * Opens the navigation menu for a player, on its top level. Call on the player's own thread.
   *
   * @param player the player
   */
  void open(Player player) {
    NavigationMenu menu = menu(player);
    Holder holder = new Holder(menu);
    holder.inventory = Bukkit.createInventory(holder, MenuLayout.SIZE, menu.title());
    holder.draw();
    player.openInventory(holder.inventory);
  }

  @EventHandler(ignoreCancelled = true)
  void onClick(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
      return;
    }
    event.setCancelled(true);
    // A double click arrives after the two single clicks it is made of, which already counted.
    if (event.getClickedInventory() != holder.inventory
        || event.getClick() == ClickType.DOUBLE_CLICK
        || !(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    handle(player, holder.menu.click(event.getRawSlot()), holder::draw);
  }

  @EventHandler(ignoreCancelled = true)
  void onDrag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
      event.setCancelled(true);
    }
  }

  @Override
  protected Locale locale(Player player) {
    return player.locale();
  }

  @Override
  protected UUID id(Player player) {
    return player.getUniqueId();
  }

  @Override
  protected Predicate<String> hasPermission(Player player) {
    return player::hasPermission;
  }

  @Override
  protected Predicate<List<String>> canNavigate(Player player) {
    return NavigateCommand.canNavigate(player);
  }

  @Override
  protected void navigate(Player player, DestinationResolver.Resolved<NavigationTarget> pick) {
    navigate.navigate(player, pick);
  }

  @Override
  protected void fillIn(Player player, MenuAction.FillIn fillIn) {
    PaperOptionDialog.show(plugin, player, fillIn, navigate, messages);
  }

  /** Waits for the player's next tick: an inventory must not be closed from its own click event. */
  @Override
  protected void closeThen(Player player, Runnable then) {
    player
        .getScheduler()
        .run(
            plugin,
            task -> {
              player.closeInventory();
              then.run();
            },
            null);
  }

  /** Makes a menu item into an item stack. */
  static ItemStack stack(MenuItem item) {
    ItemStack stack = base(item.icon());
    stack.editMeta(
        meta -> {
          meta.displayName(item.name());
          meta.lore(item.lore().isEmpty() ? null : item.lore());
          meta.addItemFlags(ItemFlag.values());
        });
    return stack;
  }

  private static ItemStack base(Icon icon) {
    return switch (icon) {
      case PaperIcon paper -> paper.stack();
      case Icon.Item item -> {
        Material material = Material.matchMaterial(item.key());
        yield ItemStack.of(material == null || !material.isItem() ? Material.PAPER : material);
      }
      case Icon.Head head -> {
        // The id only needs to be stable, so a client can cache the skin it downloads.
        PlayerProfile profile =
            Bukkit.createProfile(
                UUID.nameUUIDFromBytes(head.texture().getBytes(StandardCharsets.UTF_8)), null);
        profile.setProperty(new ProfileProperty("textures", head.texture()));
        yield skull(profile);
      }
      case Icon.PlayerHead head -> {
        Player online = Bukkit.getPlayer(head.player());
        yield skull(
            online != null ? online.getPlayerProfile() : Bukkit.createProfile(head.player()));
      }
      default -> ItemStack.of(Material.PAPER);
    };
  }

  private static ItemStack skull(PlayerProfile profile) {
    ItemStack stack = ItemStack.of(Material.PLAYER_HEAD);
    stack.editMeta(SkullMeta.class, meta -> meta.setPlayerProfile(profile));
    return stack;
  }

  /** Marks an inventory as a navigation menu, and holds that menu. */
  private static final class Holder implements InventoryHolder {
    private final NavigationMenu menu;
    private Inventory inventory;

    Holder(NavigationMenu menu) {
      this.menu = menu;
    }

    void draw() {
      Map<Integer, MenuItem> items = menu.render();
      ItemStack[] contents = new ItemStack[MenuLayout.SIZE];
      items.forEach((slot, item) -> contents[slot] = stack(item));
      inventory.setContents(contents);
    }

    @Override
    public @NotNull Inventory getInventory() {
      return inventory;
    }
  }
}
