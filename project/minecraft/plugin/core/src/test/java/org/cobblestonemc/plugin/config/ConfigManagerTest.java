/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Covers the parts of the config layer that keep the file and the running values in step: template
 * generation from the registry, platform-restricted values, and the unrecognized-key warning.
 */
class ConfigManagerTest {

  @TempDir Path directory;

  /** Captures warnings so tests can assert on what an admin would actually be told. */
  private static final class RecordingLogger extends CobblestoneLogger {

    private final List<String> warnings = new ArrayList<>();

    @Override
    public void trace(String message, Object... args) {}

    @Override
    public void debug(String message, Object... args) {}

    @Override
    public void info(String message, Object... args) {}

    @Override
    public void warn(String message, Object... args) {
      warnings.add(render(message, args));
    }

    @Override
    public void error(String message, Throwable throwable, Object... args) {}

    private static String render(String message, Object... args) {
      StringBuilder out = new StringBuilder();
      int index = 0;
      int from = 0;
      int at;
      while ((at = message.indexOf("{}", from)) >= 0) {
        out.append(message, from, at).append(index < args.length ? args[index++] : "{}");
        from = at + 2;
      }
      return out.append(message.substring(from)).toString();
    }
  }

  private ConfigManager manager(RecordingLogger logger) {
    return new ConfigManager(directory.resolve("config.yml"), logger);
  }

  @Test
  void generatesFileFromRegistryAndReadsItBack() throws IOException {
    RecordingLogger logger = new RecordingLogger();
    ConfigManager manager = manager(logger);
    manager.header("Cobblestone — test");
    manager.section("search", "Search behavior.");
    manager.section("search.chunks", "Where terrain comes from.");
    ConfigKey<ChunkLoadPolicy> policy =
        manager
            .key(
                "search.chunks.policy",
                ChunkLoadPolicy.ALLOW_LOAD,
                Codec.ofEnum(ChunkLoadPolicy.class))
            .comment("How far Cobblestone may go to obtain a chunk.")
            .permitted(List.of(ChunkLoadPolicy.LOADED_ONLY, ChunkLoadPolicy.ALLOW_LOAD))
            .requiresRestart()
            .register();
    ConfigKey<Integer> budget =
        manager
            .key("search.chunks.max_load_requests", 256, Codec.ofInt())
            .comment("How many tickets at once.")
            .mutable()
            .register();
    ConfigKey<List<String>> particles =
        manager
            .key("navigators.trail.particles", List.of("SCRAPE", "WAX_OFF"), Codec.ofStringList())
            .comment("Particle types.")
            .mutable()
            .register();
    manager.section("navigators", "Navigators.");
    manager.section("navigators.trail", "The trail.");

    manager.load();

    // The generated file must parse, and must round-trip every default back through the codecs.
    String text = Files.readString(directory.resolve("config.yml"), StandardCharsets.UTF_8);
    assertTrue(text.contains("# Cobblestone — test"), text);
    assertTrue(text.contains("# Options: loaded_only, allow_load."), text);
    assertTrue(text.contains("# (requires restart)"), text);
    // A mutable key carries no restart note.
    assertTrue(text.contains("max_load_requests: 256"), text);

    Object parsed = new Yaml().load(text);
    assertTrue(parsed instanceof Map, "generated template must be a YAML mapping");
    assertEquals(ChunkLoadPolicy.ALLOW_LOAD, manager.get(policy));
    assertEquals(256, manager.get(budget));
    assertEquals(List.of("SCRAPE", "WAX_OFF"), manager.get(particles));
    assertTrue(logger.warnings.isEmpty(), logger.warnings.toString());
  }

  @Test
  void rejectsValueThePlatformDoesNotSupport() throws IOException {
    Files.writeString(
        directory.resolve("config.yml"),
        "search:\n  chunks:\n    policy: allow_load_and_generate\n",
        StandardCharsets.UTF_8);
    RecordingLogger logger = new RecordingLogger();
    ConfigManager manager = manager(logger);
    ConfigKey<ChunkLoadPolicy> policy =
        manager
            .key(
                "search.chunks.policy",
                ChunkLoadPolicy.ALLOW_LOAD,
                Codec.ofEnum(ChunkLoadPolicy.class))
            .comment("How far Cobblestone may go to obtain a chunk.")
            .permitted(List.of(ChunkLoadPolicy.LOADED_ONLY, ChunkLoadPolicy.ALLOW_LOAD))
            .requiresRestart()
            .register();

    manager.load();

    // A real value, just not one this platform honors: keep the default and say so.
    assertEquals(ChunkLoadPolicy.ALLOW_LOAD, manager.get(policy));
    assertEquals(1, logger.warnings.size(), logger.warnings.toString());
    assertTrue(logger.warnings.get(0).contains("does not support"), logger.warnings.get(0));
  }

  @Test
  void warnsAboutUnrecognizedKeys() throws IOException {
    Files.writeString(
        directory.resolve("config.yml"),
        "search:\n  chunks:\n    max_load_requests: 8\n  from_another_platform: true\n",
        StandardCharsets.UTF_8);
    RecordingLogger logger = new RecordingLogger();
    ConfigManager manager = manager(logger);
    manager
        .key("search.chunks.max_load_requests", 256, Codec.ofInt())
        .comment("How many tickets at once.")
        .mutable()
        .register();

    manager.load();

    assertEquals(1, logger.warnings.size(), logger.warnings.toString());
    assertTrue(
        logger.warnings.get(0).contains("search.from_another_platform"), logger.warnings.get(0));
  }

  @Test
  void requiresDocumentationAndMutability() {
    ConfigManager manager = manager(new RecordingLogger());
    assertThrows(
        IllegalStateException.class,
        () -> manager.key("a.b", 1, Codec.ofInt()).mutable().register(),
        "a key with no comment must not register");
    assertThrows(
        IllegalStateException.class,
        () -> manager.key("a.c", 1, Codec.ofInt()).comment("x").register(),
        "a key that declares neither mutable() nor requiresRestart() must not register");
    assertThrows(
        IllegalStateException.class,
        () ->
            manager
                .key("a.d", 5, Codec.ofInt())
                .comment("x")
                .permitted(List.of(1, 2))
                .mutable()
                .register(),
        "a default outside the permitted set must not register");
  }
}
