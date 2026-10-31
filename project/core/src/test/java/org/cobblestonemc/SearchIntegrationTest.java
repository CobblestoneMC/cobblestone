/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.cobblestonemc.api.NavigationResult;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.api.Step;
import org.cobblestonemc.api.TraversalKey;
import org.cobblestonemc.api.TraversalState;
import org.junit.jupiter.api.Test;

class SearchIntegrationTest {

  private final SearchScheduler scheduler = new InlineSearchScheduler();
  private final CobblestoneApi api = new CobblestoneApiImpl();

  private Path<Position<TestDomain>, TestStep> requireSuccess(
      NavigationResult<Position<TestDomain>, TestStep> result) {
    if (result
        instanceof
        NavigationResult.Success<Position<TestDomain>, TestStep>(
            Path<Position<TestDomain>, TestStep> path)) {
      return path;
    }
    throw new AssertionError("expected a successful navigation, got: " + result);
  }

  private static Step<Position<TestDomain>, TestStep> last(
      Path<Position<TestDomain>, TestStep> path) {
    List<Step<Position<TestDomain>, TestStep>> steps = path.steps();
    return steps.get(steps.size() - 1);
  }

  @Test
  void singleDomainStraightLine() {
    TestDomain domain = new TestDomain("overworld");
    List<Transition<TestStep, TestDomain>> transitions = List.of();

    NavigationResult<Position<TestDomain>, TestStep> result =
        api.navigate(
                new TestCobblestoneLogger(),
                scheduler,
                new Position<>(new Cell(0, 0, 0), domain),
                new SingleDestination<>(new CellRegion<>(new Cell(5, 0, 0), domain)),
                new CorridorBehavior(false),
                transitions,
                List.of(),
                Heuristics.zero(),
                SearchSettings.defaults(),
                SearchObserver.none())
            .future()
            .join();

    Path<Position<TestDomain>, TestStep> path = requireSuccess(result);
    assertEquals(5.0, path.cost(), 1e-9);
    assertEquals(5, path.steps().size());
    assertEquals(new Cell(5, 0, 0), last(path).position().cell());
    assertSame(domain, last(path).position().domain());
  }

  @Test
  void startsInTheBehaviorsInitialState() {
    TestDomain domain = new TestDomain("overworld");
    TraversalKey<Boolean> mounted = new TraversalKey<>("mounted");
    TraversalState start = TraversalState.DEFAULT.with(mounted, Boolean.TRUE);
    List<TraversalState> seenAtOrigin = new CopyOnWriteArrayList<>();
    CorridorBehavior corridor = new CorridorBehavior(false);
    MovementBehavior<TestStep, TestDomain> behavior =
        new MovementBehavior<>() {
          @Override
          public FutureOr<Collection<Movement<TestStep>>> movements(
              Cell from, TestDomain d, TraversalState state, Cell goal) {
            if (from.equals(new Cell(0, 0, 0))) {
              seenAtOrigin.add(state);
            }
            return corridor.movements(from, d, state, goal);
          }

          @Override
          public TraversalState initialState() {
            return start;
          }
        };

    requireSuccess(
        api.navigate(
                new TestCobblestoneLogger(),
                scheduler,
                new Position<>(new Cell(0, 0, 0), domain),
                new SingleDestination<>(new CellRegion<>(new Cell(3, 0, 0), domain)),
                behavior,
                List.of(),
                List.of(),
                Heuristics.zero(),
                SearchSettings.defaults(),
                SearchObserver.none())
            .future()
            .join());

    assertEquals(List.of(start), seenAtOrigin);
  }

  @Test
  void crossesDomainThroughTransition() {
    TestDomain overworld = new TestDomain("overworld");
    TestDomain nether = new TestDomain("nether");
    TestTransition portal =
        new TestTransition(
            new CellRegion<>(new Cell(3, 0, 0), overworld),
            new Position<>(new Cell(0, 0, 0), nether),
            10.0,
            TestStep.TELEPORT);

    NavigationResult<Position<TestDomain>, TestStep> result =
        api.navigate(
                new TestCobblestoneLogger(),
                scheduler,
                new Position<>(new Cell(0, 0, 0), overworld),
                new SingleDestination<>(new CellRegion<>(new Cell(0, 0, 0), nether)),
                new CorridorBehavior(false),
                List.of(portal),
                List.of(),
                Heuristics.zero(),
                SearchSettings.defaults(),
                SearchObserver.none())
            .future()
            .join();

    Path<Position<TestDomain>, TestStep> path = requireSuccess(result);
    // 3 corridor steps in the overworld + 1 teleport step arriving in the nether.
    assertEquals(4, path.steps().size());
    assertEquals(13.0, path.cost(), 1e-9);
    assertEquals(TestStep.TELEPORT, last(path).payload());
    assertSame(nether, last(path).position().domain());
    assertEquals(new Cell(0, 0, 0), last(path).position().cell());
  }

  @Test
  void unreachableDestinationDomainFails() {
    TestDomain overworld = new TestDomain("overworld");
    TestDomain nether = new TestDomain("nether");
    List<Transition<TestStep, TestDomain>> noTransitions = List.of();

    NavigationResult<Position<TestDomain>, TestStep> result =
        api.navigate(
                new TestCobblestoneLogger(),
                scheduler,
                new Position<>(new Cell(0, 0, 0), overworld),
                new SingleDestination<>(new CellRegion<>(new Cell(0, 0, 0), nether)),
                new CorridorBehavior(false),
                noTransitions,
                List.of(),
                Heuristics.zero(),
                SearchSettings.defaults(),
                SearchObserver.none())
            .future()
            .join();

    assertEquals(NavigationResult.Failure.class, result.getClass());
  }
}
