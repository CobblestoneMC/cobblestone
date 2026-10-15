/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cobblestonemc.Cell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioTest {

  @Test
  void aDestinationBoxIsReadAsItsLeastAndGreatestCorners(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(Scenario.FILE_NAME);
    Files.writeString(
        file,
        """
        minecraft:overworld:
          village:
            capture: village
            origin: { x: -152, y: 64, z: 761 }
            destination:
              x: -287
              y: 77
              z: 816
              box: { from: { x: -224, y: 63, z: 881 }, to: { x: -350, y: 90, z: 752 } }
          point:
            capture: point
            origin: { x: 0, y: 64, z: 0 }
            destination: { x: 10, y: 64, z: 0 }
        """);

    List<Scenario> scenarios = Scenario.loadAll(file);

    Scenario village = scenarios.stream().filter(s -> s.id().endsWith("village")).findFirst().get();
    assertEquals(new Cell(-287, 77, 816), village.destination());
    assertArrayEquals(
        new Cell[] {new Cell(-350, 63, 752), new Cell(-224, 90, 881)}, village.destinationBox());

    Scenario point = scenarios.stream().filter(s -> s.id().endsWith("point")).findFirst().get();
    assertNull(point.destinationBox());
  }
}
