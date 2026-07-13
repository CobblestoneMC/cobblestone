/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.config;

/**
 * Odyssey's registered configuration parameters, grouped to mirror {@code config.yml}. More sections
 * (search, chunks, navigators, trips, data, metrics, …) are added as their subsystems land in later
 * sub-phases; the foundational keys needed to bootstrap the plugin and its messages live here.
 *
 * <p>Hold an instance for the lifetime of the plugin: construct it once with the plugin's
 * {@link ConfigManager} (which registers every key), then read keys via {@code manager.get(...)}.
 */
public final class ConfigKeys {

  /** The locale used for console/system messages (BCP-47 language tag). Immutable. */
  public final ConfigKey<String> localeDefault;

  /** Whether the {@code [✦]} prefix badge precedes every player message. Mutable. */
  public final ConfigKey<Boolean> messagesShowPrefix;

  /**
   * Registers every foundational key on the given manager.
   *
   * @param manager the config manager to populate (before {@link ConfigManager#load()})
   */
  public ConfigKeys(ConfigManager manager) {
    this.localeDefault = manager.register(
        "locale.default", "en", Codec.ofString(), false);
    this.messagesShowPrefix = manager.register(
        "messages.show_prefix", true, Codec.ofBoolean(), true);
  }
}
