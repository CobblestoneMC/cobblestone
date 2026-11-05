/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cobblestonemc.api.TraversalKey;
import org.cobblestonemc.api.TraversalState;
import org.junit.jupiter.api.Test;

class TraversalStateTest {

  private enum Vehicle {
    BOAT,
    HORSE
  }

  private static final TraversalKey<Vehicle> VEHICLE = new TraversalKey<>("vehicle");
  private static final TraversalKey<Boolean> BOAT_CONSUMED = new TraversalKey<>("boat_consumed");
  private static final TraversalKey<Integer> SPENT =
      TraversalKey.ranked("spent", Integer::compare);

  @Test
  void aStateWithLessSpentDominates() {
    TraversalState three = TraversalState.DEFAULT.with(SPENT, 3);
    TraversalState five = TraversalState.DEFAULT.with(SPENT, 5);

    assertTrue(three.dominates(five));
    assertFalse(five.dominates(three));
    assertTrue(three.dominates(three));
  }

  @Test
  void havingSpentNothingIsBest() {
    TraversalState five = TraversalState.DEFAULT.with(SPENT, 5);

    assertTrue(TraversalState.DEFAULT.dominates(five));
    assertFalse(five.dominates(TraversalState.DEFAULT));
  }

  @Test
  void differentConditionsDoNotDominateEachOther() {
    TraversalState boat = TraversalState.DEFAULT.with(VEHICLE, Vehicle.BOAT);
    TraversalState horse = TraversalState.DEFAULT.with(VEHICLE, Vehicle.HORSE);

    assertFalse(boat.dominates(horse));
    assertFalse(horse.dominates(boat));
    assertFalse(boat.dominates(TraversalState.DEFAULT));
    assertFalse(TraversalState.DEFAULT.dominates(boat));
    assertFalse(boat.with(SPENT, 3).dominates(TraversalState.DEFAULT.with(SPENT, 5)));
    assertFalse(TraversalState.DEFAULT.with(SPENT, 3).dominates(horse.with(SPENT, 5)));
    assertTrue(boat.with(SPENT, 3).dominates(boat.with(SPENT, 5)));
  }

  @Test
  void defaultIsEmpty() {
    assertTrue(TraversalState.DEFAULT.isEmpty());
    assertNull(TraversalState.DEFAULT.get(VEHICLE));
    assertFalse(TraversalState.DEFAULT.contains(VEHICLE));
  }

  @Test
  void withStoresTypedValueWithoutMutatingOriginal() {
    TraversalState state = TraversalState.DEFAULT.with(VEHICLE, Vehicle.HORSE);
    assertEquals(Vehicle.HORSE, state.get(VEHICLE));
    assertTrue(state.contains(VEHICLE));
    // original untouched (immutability)
    assertTrue(TraversalState.DEFAULT.isEmpty());
  }

  @Test
  void independentlyBuiltStatesAreEqual() {
    TraversalState a = TraversalState.DEFAULT.with(VEHICLE, Vehicle.BOAT).with(BOAT_CONSUMED, true);
    TraversalState b = TraversalState.DEFAULT.with(BOAT_CONSUMED, true).with(VEHICLE, Vehicle.BOAT);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, TraversalState.DEFAULT.with(VEHICLE, Vehicle.HORSE));
  }

  @Test
  void withoutRemovesKeyAndCollapsesToDefault() {
    TraversalState state = TraversalState.DEFAULT.with(VEHICLE, Vehicle.HORSE);
    TraversalState removed = state.without(VEHICLE);
    assertFalse(removed.contains(VEHICLE));
    assertEquals(TraversalState.DEFAULT, removed);
    // removing an absent key returns the same instance
    assertSame(state, state.without(BOAT_CONSUMED));
  }
}
