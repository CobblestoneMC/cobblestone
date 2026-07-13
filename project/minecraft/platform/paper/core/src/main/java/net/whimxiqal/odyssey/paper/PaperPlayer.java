/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.minecraft.api.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.OdysseyPlayer;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** An {@link OdysseyPlayer} wrapping a Bukkit {@link Player}. */
final class PaperPlayer implements OdysseyPlayer {

  private final Player player;

  PaperPlayer(Player player) {
    this.player = player;
  }

  @Override
  public boolean canBreak(Cell cell) {
    // v1: unconstrained. Region-protection plugins refine this via an integration hook later.
    return true;
  }

  @Override
  public UUID uuid() {
    return player.getUniqueId();
  }

  @Override
  public boolean hasPermission(String node) {
    return player.hasPermission(node);
  }

  @Override
  public boolean canFly() {
    return player.getAllowFlight();
  }

  @Override
  public boolean hasBoatInInventory() {
    for (ItemStack item : player.getInventory().getContents()) {
      if (item != null && item.getType().name().endsWith("_BOAT")) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isInBoat() {
    Entity vehicle = player.getVehicle();
    return vehicle instanceof Boat;
  }

  @Override
  public Optional<Position<MinecraftWorld>> lastRiddenHorse() {
    // Tracked by a plugin-layer listener (Phase 6); unknown here.
    return Optional.empty();
  }

  @Override
  public Locale locale() {
    return player.locale();
  }
}
