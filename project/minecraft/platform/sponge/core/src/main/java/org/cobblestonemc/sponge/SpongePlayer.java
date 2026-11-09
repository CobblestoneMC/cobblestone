/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.cobblestonemc.Position;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.data.value.Value;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.EntityType;
import org.spongepowered.api.entity.EntityTypes;
import org.spongepowered.api.entity.living.player.gamemode.GameMode;
import org.spongepowered.api.entity.living.player.gamemode.GameModes;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.item.ItemType;
import org.spongepowered.api.item.ItemTypes;
import org.spongepowered.api.item.enchantment.Enchantment;
import org.spongepowered.api.item.enchantment.EnchantmentType;
import org.spongepowered.api.item.enchantment.EnchantmentTypes;
import org.spongepowered.api.item.inventory.ItemStack;
import org.spongepowered.api.item.inventory.Slot;
import org.spongepowered.api.util.Ticks;

/** An {@link CobblestonePlayer} wrapping a Sponge {@link ServerPlayer} (re-resolved by UUID). */
final class SpongePlayer implements CobblestonePlayer {

  private static final Set<ItemType> BOATS =
      Set.of(
          ItemTypes.OAK_BOAT.get(),
          ItemTypes.SPRUCE_BOAT.get(),
          ItemTypes.BIRCH_BOAT.get(),
          ItemTypes.JUNGLE_BOAT.get(),
          ItemTypes.ACACIA_BOAT.get(),
          ItemTypes.DARK_OAK_BOAT.get(),
          ItemTypes.MANGROVE_BOAT.get(),
          ItemTypes.CHERRY_BOAT.get(),
          ItemTypes.BAMBOO_RAFT.get(),
          ItemTypes.OAK_CHEST_BOAT.get(),
          ItemTypes.SPRUCE_CHEST_BOAT.get(),
          ItemTypes.BIRCH_CHEST_BOAT.get(),
          ItemTypes.JUNGLE_CHEST_BOAT.get(),
          ItemTypes.ACACIA_CHEST_BOAT.get(),
          ItemTypes.DARK_OAK_CHEST_BOAT.get(),
          ItemTypes.MANGROVE_CHEST_BOAT.get(),
          ItemTypes.CHERRY_CHEST_BOAT.get(),
          ItemTypes.BAMBOO_CHEST_RAFT.get());

  private final UUID uuid;
  private final String name;

  SpongePlayer(ServerPlayer player) {
    this.uuid = player.uniqueId();
    this.name = player.name();
  }

  private Optional<ServerPlayer> player() {
    return Sponge.server().player(uuid);
  }

  @Override
  public UUID uuid() {
    return uuid;
  }

  @Override
  public boolean hasPermission(String node) {
    return player().map(player -> player.hasPermission(node)).orElse(false);
  }

  @Override
  public boolean canFly() {
    return player().map(player -> player.get(Keys.CAN_FLY).orElse(false)).orElse(false);
  }

  @Override
  public boolean canGlide() {
    // Worn, and not broken: an elytra with one point of durability left will not open.
    return player()
        .map(ServerPlayer::chest)
        .filter(chest -> chest.type().equals(ItemTypes.ELYTRA.get()))
        .map(chest -> chest.get(Keys.ITEM_DURABILITY).orElse(Integer.MAX_VALUE) > 1)
        .orElse(false);
  }

  @Override
  public int rocketFlightDuration() {
    Optional<ServerPlayer> player = player();
    if (player.isEmpty()) {
      return 0;
    }
    int longest = 0;
    for (Slot slot : player.get().inventory().slots()) {
      ItemStack stack = slot.peek();
      if (stack.type().equals(ItemTypes.FIREWORK_ROCKET.get())) {
        long flight = stack.get(Keys.FIREWORK_FLIGHT_MODIFIER).map(Ticks::ticks).orElse(1L);
        longest = Math.max(longest, (int) Math.max(1, Math.min(3, flight)));
      }
    }
    return longest;
  }

  @Override
  public boolean hasBoatInInventory() {
    Optional<ServerPlayer> player = player();
    if (player.isEmpty()) {
      return false;
    }
    for (Slot slot : player.get().inventory().slots()) {
      ItemStack stack = slot.peek();
      if (BOATS.contains(stack.type())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isInBoat() {
    return player()
        .flatMap(player -> player.vehicle().map(Value::get))
        .map(Entity::type)
        .map(SpongePlayer::isBoat)
        .orElse(false);
  }

  @Override
  public int soulSpeedLevel() {
    return enchantment(ServerPlayer::feet, EnchantmentTypes.SOUL_SPEED.get());
  }

  @Override
  public boolean takesDamage() {
    return player()
        .map(
            player -> {
              GameMode mode = player.gameMode().get();
              return (mode.equals(GameModes.SURVIVAL.get())
                      || mode.equals(GameModes.ADVENTURE.get()))
                  && !player.get(Keys.INVULNERABLE).orElse(false);
            })
        .orElse(true);
  }

  @Override
  public int depthStriderLevel() {
    return enchantment(ServerPlayer::feet, EnchantmentTypes.DEPTH_STRIDER.get());
  }

  /** The level of an enchantment on what the player wears in one slot, or 0 without it. */
  private int enchantment(Function<ServerPlayer, ItemStack> worn, EnchantmentType type) {
    return player().flatMap(player -> worn.apply(player).get(Keys.APPLIED_ENCHANTMENTS)).stream()
        .flatMap(List::stream)
        .filter(enchantment -> enchantment.type().equals(type))
        .mapToInt(Enchantment::level)
        .max()
        .orElse(0);
  }

  static boolean isBoat(EntityType<?> type) {
    return type.equals(EntityTypes.BOAT.get()) || type.equals(EntityTypes.CHEST_BOAT.get());
  }

  @Override
  public Optional<Position<MinecraftWorld>> lastRiddenHorse() {
    // Tracked by a plugin-layer listener; unknown here.
    return Optional.empty();
  }

  @Override
  public Locale locale() {
    return player().map(ServerPlayer::locale).orElse(null);
  }

  @Override
  public String toString() {
    return String.format("Player(%s, %s)", name, uuid);
  }
}
