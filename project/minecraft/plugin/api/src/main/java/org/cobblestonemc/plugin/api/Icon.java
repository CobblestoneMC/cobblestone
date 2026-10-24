/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The item that stands for a destination, or a level of them, in the navigation menu.
 *
 * <p>The kinds here are platform-neutral: an item named by its key, a head wearing a skin texture,
 * and a player's own head. Each platform's API adds one wrapping its native item stack, for an
 * integration that wants full control over what is shown (a custom model, a dyed leather piece).
 * Admins usually write an icon as text in a config file; {@link #parse} reads that form.
 */
public interface Icon {

  /** How {@link #parse} recognizes a head texture: {@code head:<texture value>}. */
  String HEAD_PREFIX = "head:";

  /**
   * The item with the given key, such as {@code minecraft:skeleton_skull}. A key without a
   * namespace is taken to be in {@code minecraft}.
   *
   * @param key the item's key
   * @return the icon
   */
  static Icon item(String key) {
    return new Item(key);
  }

  /**
   * A player head wearing the given skin texture — the base64 {@code textures} property value that
   * head databases such as minecraft-heads.com publish.
   *
   * @param texture the texture value
   * @return the icon
   */
  static Icon head(String texture) {
    return new Head(texture);
  }

  /**
   * The head of the given player, wearing their own skin.
   *
   * @param player the player's unique id
   * @return the icon
   */
  static Icon playerHead(UUID player) {
    return new PlayerHead(player);
  }

  /**
   * Reads an icon written as text: {@code head:<texture value>} for a textured head, otherwise an
   * item key such as {@code minecraft:oak_sign}.
   *
   * @param text the text, as an admin wrote it
   * @return the icon, or empty if the text is blank
   */
  static Optional<Icon> parse(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    String trimmed = text.trim();
    if (trimmed.regionMatches(true, 0, HEAD_PREFIX, 0, HEAD_PREFIX.length())) {
      String texture = trimmed.substring(HEAD_PREFIX.length()).trim();
      return texture.isEmpty() ? Optional.empty() : Optional.of(head(texture));
    }
    return Optional.of(item(trimmed));
  }

  /**
   * Reads an icon written as text, as {@link #parse(String)} does, falling back on another when the
   * text is blank — for an icon read from a config file that may leave it out.
   *
   * @param text the text, as an admin wrote it
   * @param fallback the icon to use when the text is blank
   * @return the icon
   */
  static Icon parse(String text, Icon fallback) {
    return parse(text).orElse(fallback);
  }

  /**
   * An item, by key.
   *
   * @param key the item's key, always namespaced
   */
  record Item(String key) implements Icon {
    /** Canonical constructor; namespaces and lower-cases the key. */
    public Item {
      Objects.requireNonNull(key, "key");
      key = key.trim().toLowerCase(Locale.ROOT);
      if (key.indexOf(':') < 0) {
        key = "minecraft:" + key;
      }
    }
  }

  /**
   * A head wearing a skin texture.
   *
   * @param texture the base64 {@code textures} property value
   */
  record Head(String texture) implements Icon {
    /** Canonical constructor. */
    public Head {
      Objects.requireNonNull(texture, "texture");
    }
  }

  /**
   * A player's own head.
   *
   * @param player the player's unique id
   */
  record PlayerHead(UUID player) implements Icon {
    /** Canonical constructor. */
    public PlayerHead {
      Objects.requireNonNull(player, "player");
    }
  }
}
