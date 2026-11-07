/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.cobblestonemc.Position;

/** A human player, wrapping a platform's native player type. */
public interface CobblestonePlayer {

  /**
   * Returns the player's unique id.
   *
   * @return the uuid
   */
  UUID uuid();

  /**
   * Returns whether the player holds the given permission node.
   *
   * @param node the permission node
   * @return {@code true} if held
   */
  boolean hasPermission(String node);

  /**
   * Returns whether the player may fly (creative/spectator/allow-flight).
   *
   * @return {@code true} if the player can fly
   */
  boolean canFly();

  /**
   * Returns whether the player can glide: an elytra worn that is not broken. Rockets are counted
   * apart, by {@link #rocketFlightDuration()}: without one a glider can still glide down.
   *
   * @return {@code true} if the player can elytra-glide
   */
  boolean canGlide();

  /**
   * Returns the longest Flight Duration among the firework rockets the player holds, or 0 without
   * one. A rocket boosts a glide, and from the ground launches one along a line as long as its
   * flight lasts.
   *
   * @return the Flight Duration, 1 to 3, or 0
   */
  default int rocketFlightDuration() {
    return 0;
  }

  /**
   * Returns whether the player can be hurt: in survival or adventure mode, and not made
   * invulnerable. A route charges for danger only to a player it could harm.
   *
   * @return {@code true} if the player takes damage
   */
  default boolean takesDamage() {
    return true;
  }

  /**
   * Returns the level of Depth Strider on the boots the player wears, or 0 without it. Each level,
   * up to three, takes a third of the gap between swimming and walking speed away.
   *
   * @return the Depth Strider level
   */
  default int depthStriderLevel() {
    return 0;
  }

  /**
   * Returns whether the player has a boat available to place.
   *
   * @return {@code true} if a boat is in the inventory
   */
  boolean hasBoatInInventory();

  boolean isInBoat();

  /**
   * Returns the level of Soul Speed on the boots the player wears, or 0 without it.
   *
   * @return the Soul Speed level
   */
  int soulSpeedLevel();

  /**
   * Returns the position of the player's most recently ridden horse, if known — used to build the
   * horse mount transition.
   *
   * @return the last-ridden-horse position, or empty
   */
  Optional<Position<MinecraftWorld>> lastRiddenHorse();

  /**
   * Returns the player's locale, for message localization.
   *
   * @return the locale
   */
  Locale locale();
}
