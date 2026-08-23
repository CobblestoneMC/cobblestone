/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import java.util.Locale;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.message.CobblestoneColors;
import org.cobblestonemc.plugin.message.Messages;

/**
 * Renders a colored help line: the command syntax (fixed, never translated) accented in the brand
 * color, followed by a localized description. Keeps the literal command out of the translation
 * bundle, which only carries the human-readable descriptions.
 */
final class SpongeCommandHelp {

  private SpongeCommandHelp() {}

  /**
   * Sends one help line to an audience.
   *
   * @param audience the recipient
   * @param messages the message renderer (for the localized description)
   * @param locale the recipient's locale
   * @param syntax the fixed command syntax (e.g. {@code /cobblestone reload})
   * @param descriptionKey the message key of the localized description
   */
  static void line(
      Audience audience, Messages messages, Locale locale, String syntax, String descriptionKey) {
    audience.sendMessage(
        Component.text(syntax, CobblestoneColors.PRIMARY)
            .append(
                Component.text(
                    "  " + messages.raw(locale, descriptionKey), CobblestoneColors.INFO)));
  }
}
