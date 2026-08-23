/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * How a trip should be displayed: which navigator (by id) and any per-trip overrides for its
 * appearance (typed by {@link NavigatorSettingKey}). Whatever a key does not set falls back to the
 * navigator's configured default.
 *
 * <p>Build these through the navigator's own settings type (e.g. {@code TrailNavigatorSettings}),
 * which knows its keys; use {@link #defaults()} for "the server's default navigator, unstyled".
 */
public final class NavigatorSettings {

  private final String navigatorId; // null = the server's configured default navigator
  private final Map<NavigatorSettingKey<?>, Object> values;

  private NavigatorSettings(String navigatorId, Map<NavigatorSettingKey<?>, Object> values) {
    this.navigatorId = navigatorId;
    this.values = values;
  }

  /** The default navigator, with no overrides. */
  public static NavigatorSettings defaults() {
    return new NavigatorSettings(null, Map.of());
  }

  /** A builder for the navigator with the given id. */
  public static Builder builder(String navigatorId) {
    return new Builder(navigatorId);
  }

  /** The navigator id to display with, or empty for the server's configured default. */
  public Optional<String> navigatorId() {
    return Optional.ofNullable(navigatorId);
  }

  /** The value set for {@code key}, or empty to fall back to the navigator's configured default. */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> get(NavigatorSettingKey<T> key) {
    return Optional.ofNullable((T) values.get(key));
  }

  /** A builder of {@link NavigatorSettings}. */
  public static final class Builder {

    private final String navigatorId;
    private final Map<NavigatorSettingKey<?>, Object> values = new HashMap<>();

    private Builder(String navigatorId) {
      this.navigatorId = navigatorId;
    }

    /** Sets a typed override. */
    public <T> Builder set(NavigatorSettingKey<T> key, T value) {
      values.put(key, value);
      return this;
    }

    /** Builds the immutable settings. */
    public NavigatorSettings build() {
      return new NavigatorSettings(navigatorId, Map.copyOf(values));
    }
  }
}
