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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.cobblestonemc.stonebrick.platform.StonebrickPlayer;
import org.yaml.snakeyaml.Yaml;

/**
 * What a player can do about the terrain, named and reusable across scenarios.
 *
 * <p>A scenario is a pair of positions; a loadout is everything else about the traveller. Keeping
 * them separate means a route does not have to be re-marked in-game to be measured for a player who
 * can fly, and it makes the interesting comparison — the same terrain under different capabilities
 * — a cross product rather than a copied entry.
 *
 * <p>Called a loadout rather than an agent because {@code agent} already names the thing being
 * navigated in the API, and what varies here is what it is carrying.
 *
 * <p><b>Capabilities, not mode lists.</b> Production derives its modes from the player through
 * {@code MinecraftModes#providerFor}; a bench that named modes directly would skip that derivation
 * and could not catch a bug in it. Exclusions are separate because production has them separately,
 * as an operator setting rather than a fact about the player.
 */
public record Loadout(
    String name,
    String description,
    Scenario.AgentSpec agent,
    Set<MinecraftStepType> excludedModes,
    Set<String> only) {

  /**
   * Returns whether this loadout is run on a scenario.
   *
   * <p>Not every loadout is worth every route: a boat changes nothing in a cave or the nether, so
   * running it there only doubles the cost of a sweep. A loadout that names tags in {@code only}
   * runs on scenarios carrying at least one of them; one that names none runs everywhere.
   *
   * @param scenario the scenario
   * @return {@code true} if this loadout should be run on it
   */
  public boolean appliesTo(Scenario scenario) {
    if (only.isEmpty()) {
      return true;
    }
    for (String tag : only) {
      if (scenario.tags().contains(tag)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns a player with this loadout's capabilities.
   *
   * @return the player
   */
  public StonebrickPlayer player() {
    return StonebrickPlayer.builder()
        .canFly(agent.canFly())
        .canGlide(agent.canGlide())
        .hasBoat(agent.hasBoat())
        .inBoat(agent.inBoat())
        .permissions(agent.permissions())
        .build();
  }

  /** The file a corpus keeps its loadouts in. */
  public static final String FILE_NAME = "agents.yml";

  /**
   * Returns the loadout used when a corpus defines none: plain survival, free to dig.
   *
   * <p>Matches what the bench did before loadouts existed, so the numbers stay comparable.
   *
   * @return the fallback loadout
   */
  public static Loadout fallback() {
    return new Loadout("default", "plain survival", Scenario.AgentSpec.plain(), Set.of(), Set.of());
  }

  /**
   * Reads every loadout in a file, in declaration order.
   *
   * @param file the loadouts file
   * @return the loadouts, or a single fallback if the file does not exist
   * @throws IOException if the file cannot be read or is malformed
   */
  public static List<Loadout> loadAll(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return List.of(fallback());
    }
    List<Loadout> loadouts = new ArrayList<>();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Map<String, Object> root = new Yaml().load(reader);
      if (root == null) {
        return List.of(fallback());
      }
      for (Map.Entry<String, Object> entry : root.entrySet()) {
        loadouts.add(from(entry.getKey(), asMap(entry.getValue(), entry.getKey(), file)));
      }
    } catch (RuntimeException e) {
      throw new IOException(file + ": " + e.getMessage(), e);
    }
    if (loadouts.isEmpty()) {
      throw new IOException(file + ": defines no loadouts");
    }
    return List.copyOf(loadouts);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object node, String name, Path file) throws IOException {
    if (node == null) {
      return Map.of();
    }
    if (!(node instanceof Map<?, ?> map)) {
      throw new IOException(file + ": loadout '" + name + "' is not a mapping");
    }
    return (Map<String, Object>) map;
  }

  private static Loadout from(String name, Map<String, Object> body) throws IOException {
    Set<MinecraftStepType> excluded = new LinkedHashSet<>();
    Object modes = body.get("excludedModes");
    if (modes instanceof Iterable<?> values) {
      for (Object value : values) {
        String mode = String.valueOf(value).strip().toUpperCase(Locale.ROOT);
        try {
          excluded.add(MinecraftStepType.valueOf(mode));
        } catch (IllegalArgumentException e) {
          throw new IOException("loadout '" + name + "': no such mode '" + mode + "'", e);
        }
      }
    }
    return new Loadout(
        name,
        String.valueOf(body.getOrDefault("description", "")),
        new Scenario.AgentSpec(
            flag(body, "canFly"),
            flag(body, "canGlide"),
            flag(body, "hasBoat"),
            flag(body, "inBoat"),
            Set.copyOf(strings(body.get("permissions")))),
        Set.copyOf(excluded),
        Set.copyOf(strings(body.get("only"))));
  }

  private static boolean flag(Map<String, Object> body, String key) {
    return body.get(key) instanceof Boolean value && value;
  }

  private static List<String> strings(Object node) {
    List<String> values = new ArrayList<>();
    if (node instanceof Iterable<?> items) {
      for (Object item : items) {
        values.add(String.valueOf(item));
      }
    }
    return values;
  }
}
