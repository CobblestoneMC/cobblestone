/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.plugin.message.Messages;
import net.whimxiqal.odyssey.plugin.message.OdysseyColors;
import org.bukkit.command.CommandSender;

/**
 * Renders a colored help line: the command syntax (fixed, never translated — commands are the same
 * in every language) accented in the brand color, followed by a localized description. This keeps
 * the literal command out of the translation bundle, which only carries the human-readable
 * descriptions.
 */
final class CommandHelp {

  private CommandHelp() {}

  /**
   * Sends one help line to a sender.
   *
   * @param sender the recipient
   * @param messages the message renderer (for the localized description)
   * @param locale the recipient's locale
   * @param syntax the fixed command syntax (e.g. {@code /odyssey reload})
   * @param descriptionKey the message key of the localized description
   */
  static void line(
      CommandSender sender,
      Messages messages,
      Locale locale,
      String syntax,
      String descriptionKey) {
    sender.sendMessage(
        Component.text(syntax, OdysseyColors.PRIMARY)
            .append(
                Component.text("  " + messages.raw(locale, descriptionKey), OdysseyColors.INFO)));
  }
}
