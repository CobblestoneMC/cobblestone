/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.api.Step;
import org.junit.jupiter.api.Test;

class SearchIntegrationTest {

  private final Scheduler scheduler = new InlineScheduler();
  private final OdysseyApi api = new OdysseyApiImpl();

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
    List<CorridorMode> modes = List.of(new CorridorMode(false));
    List<Transition<TestStep, TestDomain>> transitions = List.of();

    NavigationResult<Position<TestDomain>, TestStep> result =
        api.navigate(
                new TestOdysseyLogger(),
                scheduler,
                new TestAgent(),
                new Position<>(new Cell(0, 0, 0), domain),
                new SingleDestination<>(new CellRegion<>(new Cell(5, 0, 0), domain)),
                ModesProvider.of(modes),
                transitions,
                List.of(),
                Heuristics.zero(),
                SearchSettings.defaults())
            .future()
            .join();

    Path<Position<TestDomain>, TestStep> path = requireSuccess(result);
    assertEquals(5.0, path.cost(), 1e-9);
    assertEquals(5, path.steps().size());
    assertEquals(new Cell(5, 0, 0), last(path).position().cell());
    assertSame(domain, last(path).position().domain());
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
    List<CorridorMode> modes = List.of(new CorridorMode(false));

    NavigationResult<Position<TestDomain>, TestStep> result =
        api.navigate(
                new TestOdysseyLogger(),
                scheduler,
                new TestAgent(),
                new Position<>(new Cell(0, 0, 0), overworld),
                new SingleDestination<>(new CellRegion<>(new Cell(0, 0, 0), nether)),
                ModesProvider.of(modes),
                List.of(portal),
                List.of(),
                Heuristics.zero(),
                SearchSettings.defaults())
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
    List<CorridorMode> modes = List.of(new CorridorMode(false));
    List<Transition<TestStep, TestDomain>> noTransitions = List.of();

    NavigationResult<Position<TestDomain>, TestStep> result =
        api.navigate(
                new TestOdysseyLogger(),
                scheduler,
                new TestAgent(),
                new Position<>(new Cell(0, 0, 0), overworld),
                new SingleDestination<>(new CellRegion<>(new Cell(0, 0, 0), nether)),
                ModesProvider.of(modes),
                noTransitions,
                List.of(),
                Heuristics.zero(),
                SearchSettings.defaults())
            .future()
            .join();

    assertEquals(NavigationResult.Failure.class, result.getClass());
  }
}
