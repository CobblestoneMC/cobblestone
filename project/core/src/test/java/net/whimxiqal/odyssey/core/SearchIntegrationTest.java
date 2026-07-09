/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.CellRegion;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.OdysseyApi;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.api.SingleDestination;
import net.whimxiqal.odyssey.api.Transition;
import org.junit.jupiter.api.Test;

class SearchIntegrationTest {

  private final OdysseyApi api = new OdysseyApiImpl(new InlineScheduler());

  private Path<TestStep, Void, TestDomain> requireSuccess(
      NavigationResult<TestStep, Void, TestDomain> result) {
    if (result instanceof NavigationResult.Success<TestStep, Void, TestDomain> success) {
      return success.path();
    }
    throw new AssertionError("expected a successful navigation, got: " + result);
  }

  @Test
  void singleDomainStraightLine() {
    TestDomain domain = new TestDomain("overworld");
    List<CorridorMode> modes = List.of(new CorridorMode(false));
    List<Transition<TestStep, Void, TestDomain>> transitions = List.of();

    NavigationResult<TestStep, Void, TestDomain> result = api.navigate(
        new TestAgent(),
        new Position<>(new Cell(0, 0, 0), domain),
        new SingleDestination<>(new CellRegion<>(new Cell(5, 0, 0), domain)),
        modes,
        transitions,
        SearchSettings.defaults()).future().join();

    Path<TestStep, Void, TestDomain> path = requireSuccess(result);
    assertEquals(5.0, path.cost(), 1e-9);
    assertEquals(5, path.steps().size());
    assertEquals(new Cell(5, 0, 0), path.last().position().cell());
    assertSame(domain, path.last().position().domain());
  }

  @Test
  void crossesDomainThroughTransition() {
    TestDomain overworld = new TestDomain("overworld");
    TestDomain nether = new TestDomain("nether");
    TestTransition portal = new TestTransition(
        new CellRegion<>(new Cell(3, 0, 0), overworld),
        new Position<>(new Cell(0, 0, 0), nether),
        10.0,
        TestStep.TELEPORT);
    List<CorridorMode> modes = List.of(new CorridorMode(false));

    NavigationResult<TestStep, Void, TestDomain> result = api.navigate(
        new TestAgent(),
        new Position<>(new Cell(0, 0, 0), overworld),
        new SingleDestination<>(new CellRegion<>(new Cell(0, 0, 0), nether)),
        modes,
        List.of(portal),
        SearchSettings.defaults()).future().join();

    Path<TestStep, Void, TestDomain> path = requireSuccess(result);
    // 3 corridor steps in the overworld + 1 teleport step arriving in the nether.
    assertEquals(4, path.steps().size());
    assertEquals(13.0, path.cost(), 1e-9);
    assertEquals(TestStep.TELEPORT, path.last().stepType());
    assertSame(nether, path.last().position().domain());
    assertEquals(new Cell(0, 0, 0), path.last().position().cell());
  }

  @Test
  void unreachableDestinationDomainFails() {
    TestDomain overworld = new TestDomain("overworld");
    TestDomain nether = new TestDomain("nether");
    List<CorridorMode> modes = List.of(new CorridorMode(false));
    List<Transition<TestStep, Void, TestDomain>> noTransitions = List.of();

    NavigationResult<TestStep, Void, TestDomain> result = api.navigate(
        new TestAgent(),
        new Position<>(new Cell(0, 0, 0), overworld),
        new SingleDestination<>(new CellRegion<>(new Cell(0, 0, 0), nether)),
        modes,
        noTransitions,
        SearchSettings.defaults()).future().join();

    assertEquals(NavigationResult.Failure.class, result.getClass());
  }
}
