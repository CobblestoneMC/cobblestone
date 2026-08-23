/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.cobblestonemc.Cell;
import org.cobblestonemc.Position;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftWorld;

/** An {@link CobblestonePlayer} wrapping a Bukkit {@link Player}. */
final class PaperPlayer implements CobblestonePlayer {

  private final UUID uuid;
  private final String name;

  PaperPlayer(Player player) {
    this.uuid = player.getUniqueId();
    this.name = player.getName();
  }

  private Optional<Player> player() {
    return Optional.ofNullable(Bukkit.getPlayer(uuid));
  }

  @Override
  public boolean canBreak(Cell cell) {
    // v1: unconstrained. Region-protection plugins refine this via an integration hook later.
    return true;
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
    return player().map(Player::getAllowFlight).orElse(false);
  }

  @Override
  public boolean canGlide() {
    final Optional<Player> player = player();
    if (player.isEmpty()) {
      return false;
    }
    ItemStack chest = player.get().getInventory().getChestplate();
    if (chest == null || chest.getType() != Material.ELYTRA) {
      return false;
    }
    for (ItemStack item : player.get().getInventory().getContents()) {
      if (item != null && item.getType() == Material.FIREWORK_ROCKET) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean hasBoatInInventory() {
    final Optional<Player> player = player();
    if (player.isEmpty()) {
      return false;
    }
    for (ItemStack item : player.get().getInventory().getContents()) {
      if (item != null && item.getType().name().endsWith("_BOAT")) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isInBoat() {
    return player().map(player -> player.getVehicle() instanceof Boat).orElse(false);
  }

  @Override
  public Optional<Position<MinecraftWorld>> lastRiddenHorse() {
    // Tracked by a plugin-layer listener (Phase 6); unknown here.
    return Optional.empty();
  }

  @Override
  public Locale locale() {
    return player().map(Player::locale).orElse(null);
  }

  @Override
  public String toString() {
    return String.format("Player(%s, %s)", name, uuid);
  }
}
