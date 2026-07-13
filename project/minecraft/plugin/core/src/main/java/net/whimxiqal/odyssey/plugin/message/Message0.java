/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.message;

/**
 * A message that takes no parameters.
 */
public final class Message0 extends Message {

  /**
   * Creates a parameterless message.
   *
   * @param key the bundle key
   * @param category the tone
   */
  public Message0(String key, MessageCategory category) {
    super(key, category);
  }

  /**
   * Creates an {@link MessageCategory#INFO} message.
   *
   * @param key the bundle key
   * @return the message
   */
  public static Message0 info(String key) {
    return new Message0(key, MessageCategory.INFO);
  }

  /**
   * Creates a {@link MessageCategory#SUCCESS} message.
   *
   * @param key the bundle key
   * @return the message
   */
  public static Message0 success(String key) {
    return new Message0(key, MessageCategory.SUCCESS);
  }

  /**
   * Creates an {@link MessageCategory#ERROR} message.
   *
   * @param key the bundle key
   * @return the message
   */
  public static Message0 error(String key) {
    return new Message0(key, MessageCategory.ERROR);
  }
}
