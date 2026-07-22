/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.whimxiqal.odyssey.OdysseyLogger;
import org.yaml.snakeyaml.Yaml;

/**
 * The platform-neutral configuration store. Register all typed {@link ConfigKey}s up front, call
 * {@link #load()} once at startup, then {@link #get(ConfigKey)} anywhere. {@link #reload()} re-reads
 * the file: mutable keys update live; immutable keys that changed emit a warning and keep their old
 * value (they take effect only on restart).
 *
 * <p>Values are parsed from a YAML file on disk. On first run the bundled default resource is copied
 * to that path so admins get the fully-commented template.
 */
public final class ConfigManager {

  private final Path file;
  private final String defaultResource;
  private final OdysseyLogger logger;
  private final Map<String, ConfigKey<?>> keys = new LinkedHashMap<>();
  private final Map<String, Object> values = new ConcurrentHashMap<>();

  /**
   * Creates a config manager.
   *
   * @param file the on-disk YAML file (created from the default resource if absent)
   * @param defaultResource the classpath resource holding the default file (e.g. {@code config.yml})
   * @param logger the logger for load/reload diagnostics (developer-facing, not localized)
   */
  public ConfigManager(Path file, String defaultResource, OdysseyLogger logger) {
    this.file = file;
    this.defaultResource = defaultResource;
    this.logger = logger;
  }

  /**
   * Registers a typed parameter. Must be called before {@link #load()}.
   *
   * @param path the period-delimited, snake_case path
   * @param def the default value
   * @param codec the decoder for the raw YAML node
   * @param mutable whether the key updates live on reload
   * @param <V> the value type
   * @return the registered key
   */
  public <V> ConfigKey<V> register(String path, V def, Codec<V> codec, boolean mutable) {
    if (keys.containsKey(path)) {
      throw new IllegalStateException("duplicate config key: " + path);
    }
    ConfigKey<V> key = new ConfigKey<>(path, def, codec, mutable);
    keys.put(path, key);
    values.put(path, def);
    return key;
  }

  /**
   * Returns the current value of a key.
   *
   * @param key the key
   * @param <V> the value type
   * @return the current value
   */
  public <V> V get(ConfigKey<V> key) {
    Object value = values.get(key.path());
    // The map only ever holds values produced by this key's own codec (or its default).
    @SuppressWarnings("unchecked")
    V typed = value == null ? key.defaultValue() : (V) value;
    return typed;
  }

  /**
   * Loads the file for the first time, copying the bundled default in if the file is absent, then
   * resolving every registered key.
   */
  public void load() {
    ensureFile();
    Map<String, Object> root = read();
    for (ConfigKey<?> key : keys.values()) {
      loadKey(key, root);
    }
  }

  private <V> void loadKey(ConfigKey<V> key, Map<String, Object> root) {
    values.put(key.path(), resolve(key, root, key.defaultValue()));
  }

  /**
   * Re-reads the file. Mutable keys are updated in place; immutable keys whose file value changed
   * from the loaded value are warned about and left unchanged until restart.
   *
   * @return the paths of immutable keys that changed in the file (empty if none) — for surfacing a
   *     "requires restart" notice to the admin who triggered the reload
   */
  public List<String> reload() {
    Map<String, Object> root = read();
    List<String> restartRequired = new ArrayList<>();
    for (ConfigKey<?> key : keys.values()) {
      if (reloadKey(key, root)) {
        restartRequired.add(key.path());
      }
    }
    return List.copyOf(restartRequired);
  }

  /** Reloads one key; returns {@code true} if it is an immutable key that changed. */
  private <V> boolean reloadKey(ConfigKey<V> key, Map<String, Object> root) {
    V current = get(key);
    V loaded = resolve(key, root, current);
    if (Objects.equals(current, loaded)) {
      return false;
    }
    if (key.mutable()) {
      values.put(key.path(), loaded);
      return false;
    }
    logger.warn("Config key '{}' changed but requires a restart to take effect; keeping '{}'.",
        key.path(), current);
    return true;
  }

  /**
   * Resolves a single key from a parsed root map, falling back to {@code fallback} when the node is
   * absent or fails to decode.
   */
  private <V> V resolve(ConfigKey<V> key, Map<String, Object> root, V fallback) {
    Object raw = lookup(root, key.path());
    if (raw == null) {
      return fallback;
    }
    try {
      return key.codec().decode(raw);
    } catch (RuntimeException e) {
      logger.warn("Config key '{}' is malformed ({}); using '{}'.", key.path(), e.getMessage(), fallback);
      return fallback;
    }
  }

  /** Walks a nested map by the period-delimited path; returns {@code null} if any hop is missing. */
  private static Object lookup(Map<String, Object> root, String path) {
    Object node = root;
    int start = 0;
    while (start <= path.length()) {
      int dot = path.indexOf('.', start);
      String segment = dot < 0 ? path.substring(start) : path.substring(start, dot);
      if (!(node instanceof Map<?, ?> map)) {
        return null;
      }
      node = map.get(segment);
      if (node == null) {
        return null;
      }
      if (dot < 0) {
        return node;
      }
      start = dot + 1;
    }
    return node;
  }

  private Map<String, Object> read() {
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Object loaded = new Yaml().load(reader);
      if (loaded == null) {
        return Map.of();
      }
      if (!(loaded instanceof Map<?, ?> map)) {
        logger.warn("Config file '{}' is not a YAML mapping; using defaults.", file);
        return Map.of();
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) map;
      return typed;
    } catch (IOException e) {
      logger.error("Failed to read config file '{}'; using defaults.", e, file);
      return Map.of();
    }
  }

  private void ensureFile() {
    if (Files.exists(file)) {
      return;
    }
    try (InputStream in = ConfigManager.class.getClassLoader().getResourceAsStream(defaultResource)) {
      if (in == null) {
        logger.error("Bundled default resource '{}' is missing; cannot create config file.",
            new IllegalStateException(defaultResource), defaultResource);
        return;
      }
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      Files.copy(in, file);
    } catch (IOException e) {
      logger.error("Failed to write default config to '{}'.", e, file);
    }
  }
}
