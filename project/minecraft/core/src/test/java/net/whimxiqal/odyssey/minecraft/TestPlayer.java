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
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.Position;

/** A configurable {@link OdysseyPlayer} for mode/assembly tests. */
public final class TestPlayer implements OdysseyPlayer {

  private final boolean canFly;
  private final boolean hasBoat;
  private final boolean inBoat;
  private final boolean canBreak;

  private TestPlayer(boolean canFly, boolean hasBoat, boolean inBoat, boolean canBreak) {
    this.canFly = canFly;
    this.hasBoat = hasBoat;
    this.inBoat = inBoat;
    this.canBreak = canBreak;
  }

  public static TestPlayer walker() {
    return new TestPlayer(false, false, false, true);
  }

  public static TestPlayer create(
      boolean canFly, boolean hasBoat, boolean inBoat, boolean canBreak) {
    return new TestPlayer(canFly, hasBoat, inBoat, canBreak);
  }

  @Override
  public boolean canBreak(Cell cell) {
    return canBreak;
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
  public boolean hasBoatInInventory() {
    return hasBoat;
  }

  @Override
  public boolean isInBoat() {
    return inBoat;
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
