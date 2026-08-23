/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.message;

/** A message that takes one parameter, referenced as {@code {1}} in the template. */
public final class Message1 extends Message {

  /**
   * Creates a one-parameter message.
   *
   * @param key the bundle key
   * @param category the tone
   */
  public Message1(String key, MessageCategory category) {
    super(key, category);
  }

  /**
   * Creates an {@link MessageCategory#INFO} message.
   *
   * @param key the bundle key
   * @return the message
   */
  public static Message1 info(String key) {
    return new Message1(key, MessageCategory.INFO);
  }

  /**
   * Creates a {@link MessageCategory#SUCCESS} message.
   *
   * @param key the bundle key
   * @return the message
   */
  public static Message1 success(String key) {
    return new Message1(key, MessageCategory.SUCCESS);
  }

  /**
   * Creates an {@link MessageCategory#ERROR} message.
   *
   * @param key the bundle key
   * @return the message
   */
  public static Message1 error(String key) {
    return new Message1(key, MessageCategory.ERROR);
  }
}
