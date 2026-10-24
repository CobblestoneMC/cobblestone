/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.plugin.api.Icon;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.menu.MenuAction;
import org.cobblestonemc.plugin.menu.MenuItem;
import org.cobblestonemc.plugin.menu.MenuLayout;
import org.cobblestonemc.plugin.menu.MenuPlatform;
import org.cobblestonemc.plugin.menu.MenuSettings;
import org.cobblestonemc.plugin.menu.NavigationMenu;
import org.cobblestonemc.plugin.menu.NavigationMenus;
import org.cobblestonemc.plugin.menu.RecentDestinations;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.sponge.plugin.api.SpongeIcon;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.item.ItemType;
import org.spongepowered.api.item.ItemTypes;
import org.spongepowered.api.item.inventory.ContainerTypes;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.menu.ClickTypes;
import org.spongepowered.api.item.inventory.menu.InventoryMenu;
import org.spongepowered.api.item.inventory.type.ViewableInventory;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.profile.property.ProfileProperty;
import org.spongepowered.api.registry.RegistryTypes;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.plugin.PluginContainer;

/**
 * The navigation menu on Sponge: a read-only double chest drawn from a {@link NavigationMenu},
 * whose clicks it hands back to it. A destination that needs more is finished in chat: the player
 * is handed the command to complete.
 */
final class SpongeNavigationMenus implements MenuPlatform<ServerPlayer> {

  private final PluginContainer plugin;
  private final NavigateCommand navigate;
  private final Messages messages;
  private final NavigationMenus<ServerPlayer> menus;

  SpongeNavigationMenus(
      PluginContainer plugin,
      SpongeIntegrationRegistry integrations,
      NavigateCommand navigate,
      RecentDestinations recents,
      Messages messages,
      Supplier<MenuSettings> settings,
      CobblestoneLogger log) {
    this.plugin = plugin;
    this.navigate = navigate;
    this.messages = messages;
    this.menus =
        new NavigationMenus<>(
            this, integrations::destinationProviders, recents, messages, settings, log);
  }

  /**
   * Opens the navigation menu for a player, on its top level. Call on the main thread.
   *
   * @param player the player
   */
  void open(ServerPlayer player) {
    NavigationMenu menu = menus.menu(player);
    ViewableInventory inventory =
        ViewableInventory.builder()
            .type(ContainerTypes.GENERIC_9X6)
            .completeStructure()
            .plugin(plugin)
            .build();
    draw(inventory, menu);
    InventoryMenu view = inventory.asMenu();
    view.setReadOnly(true);
    view.setTitle(menu.title());
    view.registerSlotClick(
        (cause, container, slot, slotIndex, clickType) -> {
          // A double click arrives after the two single clicks it is made of, which already
          // counted.
          if (clickType == ClickTypes.DOUBLE_CLICK.get()) {
            return false;
          }
          menus.click(player, menu, slotIndex, () -> draw(inventory, menu));
          return false;
        });
    view.open(player);
  }

  @Override
  public Locale locale(ServerPlayer player) {
    return player.locale();
  }

  @Override
  public UUID id(ServerPlayer player) {
    return player.uniqueId();
  }

  @Override
  public Predicate<String> hasPermission(ServerPlayer player) {
    return player::hasPermission;
  }

  @Override
  public Predicate<List<String>> canNavigate(ServerPlayer player) {
    return NavigateCommand.canNavigate(player);
  }

  @Override
  public void navigate(
      ServerPlayer player,
      DestinationResolver.Resolved<NavigationTarget> pick,
      NavigationTarget destination) {
    navigate.navigate(player, pick, destination);
  }

  /** Hands the player the command for a destination, to finish typing in chat. */
  @Override
  public void fillIn(ServerPlayer player, MenuAction.FillIn fillIn) {
    Locale locale = player.locale();
    String usage = DestinationResolver.usage(fillIn.address(), fillIn.destination().options());
    player.sendMessage(
        messages
            .render(locale, CobblestoneMessages.MENU_FILL_IN_CHAT, usage)
            .clickEvent(
                ClickEvent.suggestCommand("/navigate " + String.join(" ", fillIn.address()) + " "))
            .hoverEvent(
                HoverEvent.showText(
                    messages.render(locale, CobblestoneMessages.MENU_FILL_IN_CHAT_HOVER))));
  }

  /** Waits for the next tick: an inventory must not be closed from within its own click. */
  @Override
  public void closeThen(ServerPlayer player, Runnable then) {
    Sponge.server()
        .scheduler()
        .submit(
            Task.builder()
                .plugin(plugin)
                .execute(
                    () -> {
                      player.closeInventory();
                      then.run();
                    })
                .build());
  }

  private static void draw(ViewableInventory inventory, NavigationMenu menu) {
    Map<Integer, MenuItem> items = menu.render();
    for (int slot = 0; slot < MenuLayout.SIZE; slot++) {
      MenuItem item = items.get(slot);
      ItemStack stack = item == null ? ItemStack.empty() : stack(item);
      inventory.slot(slot).ifPresent(at -> at.set(stack));
    }
  }

  /** Makes a menu item into an item stack. */
  static ItemStack stack(MenuItem item) {
    ItemStack stack = base(item.icon());
    stack.offer(Keys.CUSTOM_NAME, item.name());
    stack.offer(Keys.LORE, item.lore());
    stack.offer(Keys.HIDE_ATTRIBUTES, true);
    stack.offer(Keys.HIDE_ENCHANTMENTS, true);
    stack.offer(Keys.HIDE_MISCELLANEOUS, true);
    return stack;
  }

  private static ItemStack base(Icon icon) {
    return switch (icon) {
      case SpongeIcon sponge -> sponge.stack().asMutableCopy();
      case Icon.Item item -> ItemStack.of(itemType(item.key()).orElseGet(ItemTypes.PAPER));
      case Icon.Head head -> {
        // The id only needs to be stable, so a client can cache the skin it downloads.
        UUID id = UUID.nameUUIDFromBytes(head.texture().getBytes(StandardCharsets.UTF_8));
        yield skull(
            GameProfile.of(id)
                .withProperty(ProfileProperty.of(ProfileProperty.TEXTURES, head.texture())));
      }
      case Icon.PlayerHead head ->
          skull(
              Sponge.server()
                  .player(head.player())
                  .map(ServerPlayer::profile)
                  .orElseGet(() -> GameProfile.of(head.player())));
      default -> ItemStack.of(ItemTypes.PAPER);
    };
  }

  private static Optional<ItemType> itemType(String key) {
    try {
      return Sponge.game().registry(RegistryTypes.ITEM_TYPE).findValue(ResourceKey.resolve(key));
    } catch (IllegalArgumentException e) {
      return Optional.empty(); // not a valid key at all
    }
  }

  private static ItemStack skull(GameProfile profile) {
    ItemStack stack = ItemStack.of(ItemTypes.PLAYER_HEAD);
    stack.offer(Keys.GAME_PROFILE, profile);
    return stack;
  }
}
