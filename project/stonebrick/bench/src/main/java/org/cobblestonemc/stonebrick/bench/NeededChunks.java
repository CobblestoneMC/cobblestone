/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.cobblestonemc.Cell;
import org.yaml.snakeyaml.Yaml;

/**
 * The terrain each scenario needs, as a capsule around its route.
 *
 * <p>Nobody decides this by hand. A run reports every chunk it read outside the capture, that
 * region is folded into the capsule, and the next capture widens to cover it — repeated until a run
 * touches nothing new, usually two or three rounds. The converged capsule is exactly what the
 * search reaches for, which is the thing a human guessing a radius can only approximate.
 *
 * <p><b>A capsule, not a circle.</b> Every chunk within a radius of the origin-to-destination
 * segment. A circle is the same idea and is quadratically wasteful on the routes that matter: a 3
 * 000-block route needs an enclosing circle of radius 1 500, about 27 000 chunks, where the
 * corridor the search actually touches is nearer 3 000. When the route is short the segment
 * collapses and the capsule <em>is</em> a circle, so nothing is lost at the other end.
 *
 * <p><b>Union only, never pruned.</b> A better heuristic explores less, and shrinking the capture
 * to match would mean two versions of the algorithm were measured against different worlds. The
 * needed region only ever grows, so the corpus is stable across exactly the iteration this harness
 * exists to support.
 *
 * @param world the world key
 * @param from one end of the route
 * @param to the other end
 * @param radiusChunks how far either side of the segment is needed
 */
public record NeededChunks(String world, Cell from, Cell to, int radiusChunks) {

  /** The file the manifest lives in, beside the scenarios. */
  public static final String FILE_NAME = "needed-chunks.yml";

  /** The radius a scenario starts with before any run has measured it. */
  public static final int INITIAL_RADIUS = 4;

  /**
   * Returns the capsule a scenario starts with: its route, and a small margin.
   *
   * @param scenario the scenario
   * @return the initial capsule
   */
  public static NeededChunks initial(Scenario scenario) {
    return new NeededChunks(
        scenario.world(), scenario.origin(), scenario.destination(), INITIAL_RADIUS);
  }

  /**
   * Returns a capsule also covering the given chunk, widening the radius if it must.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @return the widened capsule, or this one if it already covers the chunk
   */
  public NeededChunks including(int chunkX, int chunkZ) {
    double distance = distanceToSegment(chunkX, chunkZ);
    int needed = (int) Math.ceil(distance);
    return needed <= radiusChunks ? this : new NeededChunks(world, from, to, needed);
  }

  /**
   * Returns whether the capsule covers a chunk.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @return {@code true} if covered
   */
  public boolean covers(int chunkX, int chunkZ) {
    return distanceToSegment(chunkX, chunkZ) <= radiusChunks;
  }

  /** Distance in chunks from a chunk to the route segment, measured in the XZ plane. */
  private double distanceToSegment(int chunkX, int chunkZ) {
    // Floored chunk coordinates, matching exactly what /copier copy capsule is given. Measuring
    // from fractional chunk positions instead would put the endpoints up to one chunk away from
    // where the copier puts them, so a strip near each end would be forever "missing" here and
    // never requested there.
    double ax = from.x() >> 4;
    double az = from.z() >> 4;
    double bx = to.x() >> 4;
    double bz = to.z() >> 4;
    double dx = bx - ax;
    double dz = bz - az;
    double lengthSquared = dx * dx + dz * dz;
    double t =
        lengthSquared == 0
            ? 0
            : Math.clamp(((chunkX - ax) * dx + (chunkZ - az) * dz) / lengthSquared, 0.0, 1.0);
    double nearestX = ax + t * dx;
    double nearestZ = az + t * dz;
    return Math.hypot(chunkX - nearestX, chunkZ - nearestZ);
  }

  /**
   * Returns how many chunks the capsule covers, for the guard in {@link Baselines}.
   *
   * @return the chunk count
   */
  public int chunkCount() {
    int minX = (Math.min(from.x(), to.x()) >> 4) - radiusChunks;
    int maxX = (Math.max(from.x(), to.x()) >> 4) + radiusChunks;
    int minZ = (Math.min(from.z(), to.z()) >> 4) - radiusChunks;
    int maxZ = (Math.max(from.z(), to.z()) >> 4) + radiusChunks;
    int count = 0;
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        if (covers(x, z)) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * Reads the manifest.
   *
   * @param file the manifest file
   * @return the capsule for each scenario id
   * @throws IOException if the file cannot be read
   */
  @SuppressWarnings("unchecked")
  public static Map<String, NeededChunks> read(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return Map.of();
    }
    Map<String, NeededChunks> needed = new LinkedHashMap<>();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Map<String, Object> root = new Yaml().load(reader);
      if (root == null) {
        return Map.of();
      }
      for (Map.Entry<String, Object> entry : root.entrySet()) {
        Map<String, Object> body = (Map<String, Object>) entry.getValue();
        needed.put(
            entry.getKey(),
            new NeededChunks(
                String.valueOf(body.get("world")),
                cell((Map<String, Object>) body.get("from")),
                cell((Map<String, Object>) body.get("to")),
                ((Number) body.get("radiusChunks")).intValue()));
      }
    } catch (RuntimeException e) {
      throw new IOException(file + ": " + e.getMessage(), e);
    }
    return needed;
  }

  private static Cell cell(Map<String, Object> node) {
    return new Cell(
        ((Number) node.get("x")).intValue(),
        ((Number) node.get("y")).intValue(),
        ((Number) node.get("z")).intValue());
  }

  /**
   * Writes the manifest, sorted by scenario id.
   *
   * @param file the manifest file
   * @param needed the capsule for each scenario id
   * @throws IOException if the file cannot be written
   */
  public static void write(Path file, Map<String, NeededChunks> needed) throws IOException {
    StringBuilder text = new StringBuilder();
    text.append("# Terrain each scenario needs, measured by running it. Union only: this file\n");
    text.append("# grows when a search reaches somewhere new and never shrinks, so that two\n");
    text.append("# versions of the algorithm are always compared over the same world.\n");
    text.append("# Regenerate the captures it describes with: ./gradlew captureCorpus\n");
    for (Map.Entry<String, NeededChunks> entry : new TreeMap<>(needed).entrySet()) {
      NeededChunks capsule = entry.getValue();
      text.append(entry.getKey()).append(":\n");
      text.append("  world: ").append(capsule.world()).append('\n');
      text.append(
          "  from: { x: %d, y: %d, z: %d }%n"
              .formatted(capsule.from().x(), capsule.from().y(), capsule.from().z()));
      text.append(
          "  to: { x: %d, y: %d, z: %d }%n"
              .formatted(capsule.to().x(), capsule.to().y(), capsule.to().z()));
      text.append("  radiusChunks: ").append(capsule.radiusChunks()).append('\n');
    }
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
  }
}
