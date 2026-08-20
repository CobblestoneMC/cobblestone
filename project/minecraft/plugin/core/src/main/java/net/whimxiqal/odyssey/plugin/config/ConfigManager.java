/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.whimxiqal.odyssey.OdysseyLogger;
import org.yaml.snakeyaml.Yaml;

/**
 * The platform-neutral configuration store. Register all typed {@link ConfigKey}s up front via
 * {@link #key}, call {@link #load()} once at startup, then {@link #get(ConfigKey)} anywhere. {@link
 * #reload()} re-reads the file: mutable keys update live; immutable keys that changed emit a
 * warning and keep their old value (they take effect only on restart).
 *
 * <p>Values are parsed from a YAML file on disk. On first run the file is <em>generated</em> from
 * the registry — defaults, prose, and permitted values all come from the registrations, so the
 * documented file and the running values cannot drift, and each platform emits exactly the keys it
 * supports.
 */
public final class ConfigManager {

  private final Path file;
  private final OdysseyLogger logger;
  private final Map<String, ConfigKey<?>> keys = new LinkedHashMap<>();
  private final Map<String, List<String>> sectionComments = new LinkedHashMap<>();
  private final Map<String, Object> values = new ConcurrentHashMap<>();
  private List<String> header = List.of();

  /**
   * Creates a config manager.
   *
   * @param file the on-disk YAML file (generated from the registry if absent)
   * @param logger the logger for load/reload diagnostics (developer-facing, not localized)
   */
  public ConfigManager(Path file, OdysseyLogger logger) {
    this.file = file;
    this.logger = logger;
  }

  /**
   * Sets the banner comment for the top of the generated file.
   *
   * @param text the banner prose; each line becomes one comment line
   */
  public void header(String text) {
    this.header = lines(text);
  }

  /**
   * Documents an intermediate section of the generated file (e.g. {@code search.algorithm}).
   *
   * @param path the period-delimited section path
   * @param text the section prose; each line becomes one comment line
   */
  public void section(String path, String text) {
    sectionComments.put(path, lines(text));
  }

  /**
   * Starts registering a typed parameter. Finish with {@link Builder#register()}. All registration
   * must happen before {@link #load()}.
   *
   * @param path the period-delimited, snake_case path
   * @param def the default value
   * @param codec the decoder for the raw YAML node
   * @param <V> the value type
   * @return a builder for the key's documentation and mutability
   */
  public <V> Builder<V> key(String path, V def, Codec<V> codec) {
    return new Builder<>(this, path, def, codec);
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
   * Loads the file for the first time, generating it from the registry if absent, then resolving
   * every registered key.
   */
  public void load() {
    ensureFile();
    Map<String, Object> root = read();
    for (ConfigKey<?> key : keys.values()) {
      loadKey(key, root);
    }
    warnUnknownKeys(root);
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
    warnUnknownKeys(root);
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
    logger.warn(
        "Config key '{}' changed but requires a restart to take effect; keeping '{}'.",
        key.path(),
        current);
    return true;
  }

  /**
   * Resolves a single key from a parsed root map, falling back to {@code fallback} when the node is
   * absent, fails to decode, or names a value this platform does not support.
   */
  private <V> V resolve(ConfigKey<V> key, Map<String, Object> root, V fallback) {
    Object raw = lookup(root, key.path());
    if (raw == null) {
      return fallback;
    }
    V decoded;
    try {
      decoded = key.codec().decode(raw);
    } catch (RuntimeException e) {
      logger.warn(
          "Config key '{}' is malformed ({}); using '{}'.", key.path(), e.getMessage(), fallback);
      return fallback;
    }
    if (!key.permitted().isEmpty() && !key.permitted().contains(decoded)) {
      // Usually a config copied from another platform: a real value, just not one this platform
      // can honor. Say so rather than quietly substituting something that means something else.
      logger.warn(
          "Config key '{}' is set to '{}', which this platform does not support (supported: {});"
              + " using '{}'.",
          key.path(),
          raw,
          key.permitted(),
          fallback);
      return fallback;
    }
    return decoded;
  }

  /** Warns about leaf entries in the file that match no registered key. */
  private void warnUnknownKeys(Map<String, Object> root) {
    List<String> unknown = new ArrayList<>();
    collectLeaves(root, "", unknown);
    unknown.removeIf(keys::containsKey);
    if (unknown.isEmpty()) {
      return;
    }
    logger.warn(
        "Config file '{}' has {} setting(s) Odyssey does not recognize (ignored): {}. They may"
            + " belong to another platform, or to a version that renamed them.",
        file,
        unknown.size(),
        String.join(", ", new TreeSet<>(unknown)));
  }

  private static void collectLeaves(Map<?, ?> node, String prefix, List<String> out) {
    for (Map.Entry<?, ?> entry : node.entrySet()) {
      String path =
          prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + '.' + entry.getKey();
      if (entry.getValue() instanceof Map<?, ?> child) {
        collectLeaves(child, path, out);
      } else {
        out.add(path);
      }
    }
  }

  /**
   * Walks a nested map by the period-delimited path; returns {@code null} if any hop is missing.
   */
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
    try {
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      Files.writeString(file, template(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      logger.error("Failed to write default config to '{}'.", e, file);
    }
  }

  /** Registers the key; called by {@link Builder#register()}. */
  private <V> ConfigKey<V> add(ConfigKey<V> key) {
    if (keys.containsKey(key.path())) {
      throw new IllegalStateException("duplicate config key: " + key.path());
    }
    keys.put(key.path(), key);
    values.put(key.path(), key.defaultValue());
    return key;
  }

  /** Renders the template that first-run generation writes. */
  String template() {
    return ConfigTemplate.render(header, sectionComments, keys.values());
  }

  private static List<String> lines(String text) {
    return List.of(text.stripTrailing().split("\n", -1));
  }

  /**
   * Collects a key's documentation and mutability before registration. Every key must declare prose
   * and must choose {@link #mutable()} or {@link #requiresRestart()} — both are emitted into the
   * generated file, so leaving either implicit would ship an undocumented setting.
   *
   * @param <V> the value type
   */
  public static final class Builder<V> {

    private final ConfigManager manager;
    private final String path;
    private final V def;
    private final Codec<V> codec;
    private List<String> comment = List.of();
    private List<V> permitted = List.of();
    private Boolean mutable;

    private Builder(ConfigManager manager, String path, V def, Codec<V> codec) {
      this.manager = manager;
      this.path = path;
      this.def = def;
      this.codec = codec;
    }

    /**
     * Sets the documentation emitted above this key.
     *
     * @param text the prose; each line becomes one comment line
     * @return this builder
     */
    public Builder<V> comment(String text) {
      this.comment = lines(text);
      return this;
    }

    /**
     * Restricts this key to a set of values, for platform-specific subsets of an enum. The
     * permitted values are listed in the generated file and enforced on load.
     *
     * @param values the permitted values (must include the default)
     * @return this builder
     */
    public Builder<V> permitted(List<V> values) {
      this.permitted = List.copyOf(values);
      return this;
    }

    /**
     * Marks the key as updating live on {@code /odyssey reload}.
     *
     * @return this builder
     */
    public Builder<V> mutable() {
      this.mutable = true;
      return this;
    }

    /**
     * Marks the key as taking effect only on a full restart.
     *
     * @return this builder
     */
    public Builder<V> requiresRestart() {
      this.mutable = false;
      return this;
    }

    /**
     * Registers the key.
     *
     * @return the registered key
     */
    public ConfigKey<V> register() {
      if (comment.isEmpty()) {
        throw new IllegalStateException("config key '" + path + "' has no comment");
      }
      if (mutable == null) {
        throw new IllegalStateException(
            "config key '" + path + "' must declare mutable() or requiresRestart()");
      }
      if (!permitted.isEmpty() && !permitted.contains(def)) {
        throw new IllegalStateException(
            "config key '" + path + "' default " + def + " is not among " + permitted);
      }
      return manager.add(new ConfigKey<>(path, def, codec, mutable, comment, permitted));
    }
  }
}
