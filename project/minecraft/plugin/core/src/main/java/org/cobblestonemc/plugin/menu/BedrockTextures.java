/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.cobblestonemc.plugin.api.Icon;

/**
 * Where a Bedrock client keeps the picture of a Java item, for the image on a Bedrock form's
 * button: a path into the client's own resources, such as {@code textures/items/compass_item}. Java
 * and Bedrock name many textures differently, and a block's item is a 3D model with no one picture,
 * so this is a table of the items the menu is likely to show rather than a rule. A head has no
 * texture of its own at all.
 */
public final class BedrockTextures {

  private static final Map<String, String> TEXTURES = load();

  private BedrockTextures() {}

  /**
   * The texture that pictures an icon on Bedrock.
   *
   * @param icon the icon
   * @return the texture's path, or empty if Bedrock has none known for it
   */
  public static Optional<String> of(Icon icon) {
    return icon instanceof Icon.Item(String key)
        ? Optional.ofNullable(TEXTURES.get(key))
        : Optional.empty();
  }

  private static Map<String, String> load() {
    Properties properties = new Properties();
    try (InputStream in =
        BedrockTextures.class.getResourceAsStream("bedrock-textures.properties")) {
      if (in == null) {
        throw new IllegalStateException("bedrock-textures.properties is missing");
      }
      properties.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Map<String, String> textures = new HashMap<>();
    properties.forEach(
        (item, texture) -> textures.put(new Icon.Item((String) item).key(), (String) texture));
    return Map.copyOf(textures);
  }
}
