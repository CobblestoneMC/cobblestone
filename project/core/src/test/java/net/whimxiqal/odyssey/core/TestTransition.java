/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import net.whimxiqal.odyssey.api.DomainRegion;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.Transition;

/**
 * A simple test {@link Transition} with no state transformation and no instruction.
 *
 * @param origin the entry region
 * @param destination the arrival position
 * @param cost the traversal cost
 * @param stepType the step type
 */
record TestTransition(
    DomainRegion<TestDomain> origin,
    Position<TestDomain> destination,
    double cost,
    TestStep stepType) implements Transition<TestStep, Void, TestDomain> {

  @Override
  public Void instruction() {
    return null;
  }
}
