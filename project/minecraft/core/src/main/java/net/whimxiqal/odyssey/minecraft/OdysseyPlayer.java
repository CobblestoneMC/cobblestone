/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.whimxiqal.odyssey.Position;

/**
 * A human player. Each platform implements this as a thin wrapper around its native player type (no
 * downcasting). The capability accessors here drive mode-list assembly and transition building.
 */
public interface OdysseyPlayer extends MinecraftAgent {

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
   * Returns whether the player can glide with an elytra right now — an elytra equipped and at least
   * one firework rocket to sustain it. Used to model thin (1-block-tall) flight for reaching an end
   * gateway.
   *
   * @return {@code true} if the player can elytra-glide
   */
  boolean canGlide();

  /**
   * Returns whether the player has a boat available to place.
   *
   * @return {@code true} if a boat is in the inventory
   */
  boolean hasBoatInInventory();

  boolean isInBoat();

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
