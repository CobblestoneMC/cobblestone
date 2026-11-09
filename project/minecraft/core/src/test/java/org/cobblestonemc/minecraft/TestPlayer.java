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

/**
 * A configurable {@link CobblestonePlayer} for movement tests.
 *
 * <p>One that cannot be hurt unless a test asks for {@link #vulnerable()}: a test world is a few
 * blocks floating in the void, so every step in it runs along a drop, and a test of how a step
 * moves would otherwise be measuring the danger of the drop.
 */
public final class TestPlayer implements CobblestonePlayer {

  private final boolean canFly;
  private final boolean canGlide;
  private final boolean hasBoat;
  private final boolean inBoat;
  private final int soulSpeed;
  private final boolean takesDamage;
  private final int depthStrider;

  private TestPlayer(
      boolean canFly, boolean canGlide, boolean hasBoat, boolean inBoat, int soulSpeed) {
    this(canFly, canGlide, hasBoat, inBoat, soulSpeed, false, 0);
  }

  private TestPlayer(
      boolean canFly,
      boolean canGlide,
      boolean hasBoat,
      boolean inBoat,
      int soulSpeed,
      boolean takesDamage,
      int depthStrider) {
    this.canFly = canFly;
    this.canGlide = canGlide;
    this.hasBoat = hasBoat;
    this.inBoat = inBoat;
    this.soulSpeed = soulSpeed;
    this.takesDamage = takesDamage;
    this.depthStrider = depthStrider;
  }

  /** This player, but one that can be hurt, as in survival. */
  public TestPlayer vulnerable() {
    return new TestPlayer(canFly, canGlide, hasBoat, inBoat, soulSpeed, true, depthStrider);
  }

  /** This player, in boots with Depth Strider of the given level. */
  public TestPlayer striding(int depthStrider) {
    return new TestPlayer(canFly, canGlide, hasBoat, inBoat, soulSpeed, takesDamage, depthStrider);
  }

  public static TestPlayer walker() {
    return new TestPlayer(false, false, false, false, 0);
  }

  /** A player wearing an elytra: can glide, but cannot hover or gain height. */
  public static TestPlayer glider() {
    return new TestPlayer(false, true, false, false, 0);
  }

  /** A walker in boots with Soul Speed of the given level. */
  public static TestPlayer soulSpeed(int level) {
    return new TestPlayer(false, false, false, false, level);
  }

  public static TestPlayer create(boolean canFly, boolean hasBoat, boolean inBoat) {
    return new TestPlayer(canFly, false, hasBoat, inBoat, 0);
  }

  @Override
  public UUID uuid() {
    return new UUID(0L, 0L);
  }

  @Override
  public boolean hasPermission(String node) {
    return true;
  }

  @Override
  public boolean canFly() {
    return canFly;
  }

  @Override
  public boolean canGlide() {
    return canGlide;
  }

  @Override
  public boolean hasBoatInInventory() {
    return hasBoat;
  }

  @Override
  public boolean isInBoat() {
    return inBoat;
  }

  @Override
  public int soulSpeedLevel() {
    return soulSpeed;
  }

  @Override
  public boolean takesDamage() {
    return takesDamage;
  }

  @Override
  public int depthStriderLevel() {
    return depthStrider;
  }

  @Override
  public Optional<Position<MinecraftWorld>> lastRiddenHorse() {
    return Optional.empty();
  }

  @Override
  public Locale locale() {
    return Locale.ENGLISH;
  }
}
