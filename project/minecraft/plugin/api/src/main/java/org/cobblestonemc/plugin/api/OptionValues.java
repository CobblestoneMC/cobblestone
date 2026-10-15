/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The parsed values of a destination's {@link DestinationOption}s, as typed after its key. Keyed by
 * the option instances themselves, so a destination reads back exactly the options it declared.
 */
public final class OptionValues {

  private static final OptionValues EMPTY = new OptionValues(Map.of());

  private final Map<DestinationOption<?>, Object> values;

  private OptionValues(Map<DestinationOption<?>, Object> values) {
    this.values = values;
  }

  /**
   * No values at all.
   *
   * @return the empty set of values
   */
  public static OptionValues empty() {
    return EMPTY;
  }

  /**
   * The given values.
   *
   * @param values each option's parsed value; options that were not given are absent
   * @return the values
   */
  public static OptionValues of(Map<? extends DestinationOption<?>, ?> values) {
    return new OptionValues(new IdentityHashMap<>(values));
  }

  /**
   * Returns the value given for a required option.
   *
   * @param option the option
   * @param <T> the value type
   * @return the value
   * @throws NoSuchElementException if no value was given for it
   */
  public <T> T get(DestinationOption<T> option) {
    return find(option)
        .orElseThrow(() -> new NoSuchElementException("no value for option " + option.name()));
  }

  /**
   * Returns the value given for an option, if one was.
   *
   * @param option the option
   * @param <T> the value type
   * @return the value, or empty if it was not given
   */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> find(DestinationOption<T> option) {
    return Optional.ofNullable((T) values.get(option));
  }
}
