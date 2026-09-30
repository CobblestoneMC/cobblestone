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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cobblestonemc.Cell;
import org.cobblestonemc.stonebrick.platform.IoMode;
import org.cobblestonemc.stonebrick.platform.IoProfile;
import org.yaml.snakeyaml.Yaml;

/**
 * One benchmark case: a capture, a start, a goal, and the limits to run under.
 *
 * <p>Scenarios are the cheap half of the corpus — a file — and captures are the expensive half, so
 * most of the catalogue varies the agent or the destination over terrain that has already been paid
 * for. Two scenarios over one coastline, one with a boat and one without, cost one capture.
 *
 * @param id the scenario's name, and the key its baseline is stored under
 * @param description what it is for, in one line
 * @param tags free-form labels, for selecting subsets of the corpus
 * @param tier which corpus this belongs to
 * @param capture the capture directory name, relative to the corpus root
 * @param world the world key to search in
 * @param origin where the agent starts
 * @param destination the cell sought
 * @param destinationRadius how far from {@code destination} counts as arrival
 * @param settings the search limits
 * @param io what a chunk read costs
 * @param onMissingCapture what to do about reads outside the capture
 * @param expectedOutcome the outcome the scenario asserts, or {@code null} to assert nothing
 * @param ungenerated chunks to present as never generated, whatever the capture holds
 * @param heuristic which estimate the fine search runs on
 */
public record Scenario(
    String id,
    String description,
    Set<String> tags,
    Tier tier,
    String capture,
    String world,
    Cell origin,
    Cell destination,
    int destinationRadius,
    SearchLimits settings,
    IoProfile io,
    MissingCapturePolicy onMissingCapture,
    String expectedOutcome,
    Set<Long> ungenerated,
    Heuristic heuristic) {

  /** Which estimate the fine search runs on. */
  public enum Heuristic {
    /**
     * No estimate at all, turning the search into Dijkstra.
     *
     * <p>Not for production: it expands everything. It is the only way to get a route's <i>true</i>
     * optimal cost, which is what {@code bench verify} measures the coarse estimates against.
     */
    ZERO,

    /** Today's production heuristic: remaining distance times the recent per-block cost. */
    RUNNING_AVERAGE,

    /** The coarse layer's estimate, from a backward search over section profiles. */
    COARSE
  }

  /** Which corpus a scenario belongs to. */
  public enum Tier {
    /**
     * Small enough to live in version control and gate every pull request.
     *
     * <p>The budget is a file count rather than a byte count, because that is what stops being
     * pleasant first.
     */
    CI,

    /**
     * Too large for the repository: run by hand, on a machine that has the capture.
     *
     * <p>These are the scenarios whose value <em>is</em> their size — the twelve-thousand-block
     * routes that exist to find out what happens at that distance. Making them small would remove
     * the only thing they test.
     */
    LOCAL
  }

  /** What to do when the search reads terrain the capture does not contain. */
  public enum MissingCapturePolicy {
    /**
     * Fail the run, naming the region and the command that would capture it.
     *
     * <p>The default, because uncaptured terrain is indistinguishable from a wall: the alternative
     * is a run that completes, succeeds, and reports a number about a world that does not exist.
     */
    ERROR,

    /**
     * Treat it as impassable, which is what a live server does for ungenerated chunks.
     *
     * <p>A legitimate thing to measure — but only when a scenario says it means to.
     */
    WALL
  }

  /**
   * A traveller's capabilities, which decide which modes the search is given.
   *
   * <p>Lives on {@link Loadout} rather than on a scenario: the route and the traveller vary
   * independently, and every scenario is run under every loadout.
   */
  public record AgentSpec(
      boolean canFly, boolean canGlide, boolean hasBoat, boolean inBoat, Set<String> permissions) {

    /** Returns a plain survival player with no special capabilities. */
    public static AgentSpec plain() {
      return new AgentSpec(false, false, false, false, Set.of());
    }
  }

  /** The search limits, mirroring {@code SearchSettings}. */
  public record SearchLimits(double heuristicWeight, int maxCellsVisited, long maxWallClockMillis) {

    /** Returns the production defaults. */
    public static SearchLimits defaults() {
      return new SearchLimits(1.5, 200_000, 60_000);
    }
  }

  /** The file every scenario lives in, keyed by id. */
  public static final String FILE_NAME = "scenarios.yml";

  /**
   * Reads every scenario from a corpus's {@code scenarios.yml}.
   *
   * <p>Keyed by world, then by name. Scenarios are grouped by the terrain they touch because that
   * is how they are thought about and captured — and because a name then only has to be unique
   * within its world, so two dimensions may each have a {@code deep-cave} without one having to be
   * renamed for the other's sake.
   *
   * <p>A scenario's id is therefore {@code &lt;world&gt;/&lt;name&gt;}: the short world path for
   * {@code minecraft:}, the full key otherwise. That id is what baselines and the chunk manifest
   * are keyed by, so it has to be unique and stable, which the world prefix makes it.
   *
   * <p>The file is rewritten in world-then-name order every time, so a mark taken in-game produces
   * a one-entry diff rather than a reshuffle.
   *
   * @param file the scenarios file
   * @return the scenarios, ordered by id
   * @throws IOException if the file cannot be read or parsed
   */
  @SuppressWarnings("unchecked")
  public static List<Scenario> loadAll(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    Map<String, Object> root;
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      root = new Yaml().load(reader);
    } catch (RuntimeException e) {
      throw new IOException(file + ": " + e.getMessage(), e);
    }
    if (root == null) {
      return List.of();
    }
    List<Scenario> scenarios = new ArrayList<>();
    for (Map.Entry<String, Object> world : root.entrySet()) {
      if (!(world.getValue() instanceof Map)) {
        throw new IOException(
            file + ": '" + world.getKey() + "' should hold scenarios, keyed by name");
      }
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) world.getValue()).entrySet()) {
        if (!(entry.getValue() instanceof Map)) {
          throw new IOException(file + ": '" + entry.getKey() + "' is not a scenario");
        }
        Map<String, Object> body =
            new java.util.LinkedHashMap<>((Map<String, Object>) entry.getValue());
        body.put("id", idOf(world.getKey(), entry.getKey()));
        body.put("world", world.getKey());
        try {
          scenarios.add(from(body, file));
        } catch (RuntimeException e) {
          throw new IOException(file + ": " + entry.getKey() + ": " + e.getMessage(), e);
        }
      }
    }
    scenarios.sort(java.util.Comparator.comparing(Scenario::id));
    return scenarios;
  }

  /**
   * Returns the id a scenario is known by: {@code &lt;world&gt;/&lt;name&gt;}.
   *
   * <p>The namespace is dropped for {@code minecraft:}, which is nearly every world, so the common
   * case reads as {@code overworld/deep-cave} rather than {@code minecraft:overworld/deep-cave}.
   *
   * @param world the world key
   * @param name the scenario's name within that world
   * @return the id
   */
  public static String idOf(String world, String name) {
    String prefix = world.startsWith("minecraft:") ? world.substring("minecraft:".length()) : world;
    return prefix + "/" + name;
  }

  /**
   * Returns this scenario run under a different heuristic.
   *
   * <p>A variant is an override rather than a second entry in {@code scenarios.yml}, so that the
   * A/B compares the same route under two estimates instead of two routes that have to be kept in
   * step by hand.
   *
   * @param value the heuristic to run
   * @return the variant, or this scenario if nothing changed
   */
  public Scenario withHeuristic(Heuristic value) {
    return value == heuristic
        ? this
        : new Scenario(
            id,
            description,
            tags,
            tier,
            capture,
            world,
            origin,
            destination,
            destinationRadius,
            settings,
            io,
            onMissingCapture,
            expectedOutcome,
            ungenerated,
            value);
  }

  /**
   * Returns this scenario run at a different heuristic weight.
   *
   * @param weight the weight to run at
   * @return the variant, or this scenario if nothing changed
   */
  public Scenario withHeuristicWeight(double weight) {
    if (weight == settings.heuristicWeight()) {
      return this;
    }
    SearchLimits tuned =
        new SearchLimits(weight, settings.maxCellsVisited(), settings.maxWallClockMillis());
    return new Scenario(
        id,
        description,
        tags,
        tier,
        capture,
        world,
        origin,
        destination,
        destinationRadius,
        tuned,
        io,
        onMissingCapture,
        expectedOutcome,
        ungenerated,
        heuristic);
  }

  /**
   * Returns the id one run is recorded under: {@code <world>/<name>@<loadout>}.
   *
   * <p>Separate from {@link #id()} because terrain is shared across loadouts and results are not:
   * the capture and the needed-chunks capsule are keyed by the route, the baseline by the pair.
   *
   * @param loadout the loadout being run
   * @return the run id
   */
  public String runId(Loadout loadout) {
    return id + "@" + loadout.name();
  }

  /**
   * Returns this scenario run against a different simulated disk.
   *
   * <p>An override rather than a per-scenario edit, so the same route can be measured on a free
   * disk and a slow one without the corpus having to carry both.
   *
   * @param profile the disk to run against
   * @return the variant
   */
  public Scenario withIo(IoProfile profile) {
    return new Scenario(
        id,
        description,
        tags,
        tier,
        capture,
        world,
        origin,
        destination,
        destinationRadius,
        settings,
        profile,
        onMissingCapture,
        expectedOutcome,
        ungenerated,
        heuristic);
  }

  /**
   * Returns this scenario started somewhere else.
   *
   * <p>For asking what a route costs from partway along it, which is what verifying a cost-to-goal
   * estimate needs.
   *
   * @param cell where to start
   * @return the variant
   */
  public Scenario withOrigin(Cell cell) {
    return new Scenario(
        id,
        description,
        tags,
        tier,
        capture,
        world,
        cell,
        destination,
        destinationRadius,
        settings,
        io,
        onMissingCapture,
        expectedOutcome,
        ungenerated,
        heuristic);
  }

  /**
   * Returns this scenario run under different limits.
   *
   * @param limits the limits to run under
   * @return the variant
   */
  public Scenario withLimits(SearchLimits limits) {
    return new Scenario(
        id,
        description,
        tags,
        tier,
        capture,
        world,
        origin,
        destination,
        destinationRadius,
        limits,
        io,
        onMissingCapture,
        expectedOutcome,
        ungenerated,
        heuristic);
  }

  private static Scenario from(Map<String, Object> root, Path file) throws IOException {
    String id = string(root, "id", "unnamed");
    Map<String, Object> agent = map(root, "agent");
    Map<String, Object> limits = map(root, "settings");
    Map<String, Object> destination = requireMap(root, "destination", file);

    String ioName = string(root, "io", "zero");
    IoProfile profile = IoProfile.byName(ioName);
    if (profile == null) {
      throw new IOException(file + ": unknown io profile '" + ioName + "'");
    }
    // A scenario names a disk, not a mode: how the delay is spent is a property of the run, and
    // forcing every scenario to say VIRTUAL would be noise in fifty files.
    profile = profile.withMode(profile.mode() == IoMode.ZERO ? IoMode.ZERO : IoMode.VIRTUAL);

    return new Scenario(
        id,
        string(root, "description", ""),
        new LinkedHashSet<>(stringList(root, "tags")),
        Tier.valueOf(string(root, "tier", "CI").toUpperCase(java.util.Locale.ROOT)),
        require(root, "capture", file),
        // Top level first: the world belongs to the scenario, not to one of its endpoints. The
        // per-position fallbacks are for files written before the format collapsed into one.
        string(
            root,
            "world",
            string(destination, "world", string(requireMap(root, "origin", file), "world", ""))),
        cell(requireMap(root, "origin", file), file),
        cell(destination, file),
        integer(destination, "radius", 0),
        new SearchLimits(
            number(limits, "heuristicWeight", 1.5),
            integer(limits, "maxCellsVisited", 200_000),
            (long) number(limits, "maxWallClockMillis", 60_000)),
        profile,
        MissingCapturePolicy.valueOf(
            string(root, "onMissingCapture", "ERROR").toUpperCase(java.util.Locale.ROOT)),
        string(root, "expect", "success"),
        ungenerated(root, file),
        Heuristic.valueOf(
            string(root, "heuristic", "RUNNING_AVERAGE")
                .toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_')));
  }

  /**
   * Chunks the scenario declares as ungenerated, packed as {@code (x &lt;&lt; 32) | z}.
   *
   * <p>Declared rather than achieved by capturing a region with holes in it. A live server does
   * meet ungenerated terrain — at a world border, or under a policy that will not generate — and a
   * corpus that captures everything would never exercise it. Saying so in the scenario keeps the
   * terrain complete and the case tested, and makes it obvious to a reader which run is about that
   * and which is not.
   */
  private static Set<Long> ungenerated(Map<String, Object> root, Path file) throws IOException {
    Object value = root.get("ungenerated");
    if (!(value instanceof List<?> list)) {
      return Set.of();
    }
    Set<Long> chunks = new java.util.LinkedHashSet<>();
    for (Object item : list) {
      String text = String.valueOf(item).trim();
      String[] parts = text.split(",");
      if (parts.length != 2) {
        throw new IOException(file + ": ungenerated chunk '" + text + "' is not 'x,z'");
      }
      try {
        chunks.add(
            ((long) Integer.parseInt(parts[0].trim()) << 32)
                | (Integer.parseInt(parts[1].trim()) & 0xFFFF_FFFFL));
      } catch (NumberFormatException e) {
        throw new IOException(file + ": ungenerated chunk '" + text + "' is not 'x,z'", e);
      }
    }
    return Set.copyOf(chunks);
  }

  private static Cell cell(Map<String, Object> node, Path file) throws IOException {
    if (!node.containsKey("x") || !node.containsKey("y") || !node.containsKey("z")) {
      throw new IOException(file + ": a position needs x, y and z");
    }
    return new Cell(integer(node, "x", 0), integer(node, "y", 0), integer(node, "z", 0));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> root, String key) {
    Object value = root.get(key);
    return value instanceof Map ? (Map<String, Object>) value : Map.of();
  }

  private static Map<String, Object> requireMap(Map<String, Object> root, String key, Path file)
      throws IOException {
    Map<String, Object> value = map(root, key);
    if (value.isEmpty()) {
      throw new IOException(file + ": missing '" + key + "'");
    }
    return value;
  }

  private static String require(Map<String, Object> root, String key, Path file)
      throws IOException {
    Object value = root.get(key);
    if (value == null) {
      throw new IOException(file + ": missing '" + key + "'");
    }
    return value.toString();
  }

  private static String string(Map<String, Object> root, String key, String fallback) {
    Object value = root.get(key);
    return value == null ? fallback : value.toString();
  }

  private static boolean bool(Map<String, Object> root, String key, boolean fallback) {
    Object value = root.get(key);
    return value instanceof Boolean flag ? flag : fallback;
  }

  private static int integer(Map<String, Object> root, String key, int fallback) {
    Object value = root.get(key);
    return value instanceof Number number ? number.intValue() : fallback;
  }

  private static double number(Map<String, Object> root, String key, double fallback) {
    Object value = root.get(key);
    return value instanceof Number number ? number.doubleValue() : fallback;
  }

  @SuppressWarnings("unchecked")
  private static List<String> stringList(Map<String, Object> root, String key) {
    Object value = root.get(key);
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> strings = new ArrayList<>(list.size());
    for (Object item : (List<Object>) list) {
      strings.add(String.valueOf(item));
    }
    return strings;
  }
}
