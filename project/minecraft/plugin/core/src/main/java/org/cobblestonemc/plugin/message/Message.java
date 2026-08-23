/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.message;

/**
 * A localized message descriptor: a bundle key plus a {@link MessageCategory}. The concrete arity
 * subtypes ({@link Message0}, {@link Message1}, {@link Message2}, {@link Message3}) exist so the
 * {@link Messages} render/send methods enforce, at compile time, that the right number of
 * parameters is supplied.
 *
 * <p>The template string in the bundle references parameters with one-indexed braces: {@code {1}}
 * is the first parameter, {@code {2}} the second, and so on. {@link Messages} substitutes each in
 * the {@link CobblestoneColors#SECONDARY secondary} color.
 */
public abstract sealed class Message permits Message0, Message1, Message2, Message3 {

  private final String key;
  private final MessageCategory category;

  Message(String key, MessageCategory category) {
    this.key = key;
    this.category = category;
  }

  /**
   * Returns the bundle key.
   *
   * @return the key
   */
  public String key() {
    return key;
  }

  /**
   * Returns the message tone (drives base color).
   *
   * @return the category
   */
  public MessageCategory category() {
    return category;
  }
}
