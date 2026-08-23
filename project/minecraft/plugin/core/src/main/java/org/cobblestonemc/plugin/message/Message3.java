/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.message;

/** A message that takes three parameters, referenced as {@code {1}}, {@code {2}}, {@code {3}}. */
public final class Message3 extends Message {

  /**
   * Creates a three-parameter message.
   *
   * @param key the bundle key
   * @param category the tone
   */
  public Message3(String key, MessageCategory category) {
    super(key, category);
  }

  /**
   * Creates an {@link MessageCategory#INFO} message.
   *
   * @param key the bundle key
   * @return the message
   */
  public static Message3 info(String key) {
    return new Message3(key, MessageCategory.INFO);
  }

  /**
   * Creates a {@link MessageCategory#SUCCESS} message.
   *
   * @param key the bundle key
   * @return the message
   */
  public static Message3 success(String key) {
    return new Message3(key, MessageCategory.SUCCESS);
  }

  /**
   * Creates an {@link MessageCategory#ERROR} message.
   *
   * @param key the bundle key
   * @return the message
   */
  public static Message3 error(String key) {
    return new Message3(key, MessageCategory.ERROR);
  }
}
