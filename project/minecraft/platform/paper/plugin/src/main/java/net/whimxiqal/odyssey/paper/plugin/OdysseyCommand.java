/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import net.whimxiqal.odyssey.plugin.config.ConfigKeys;
import net.whimxiqal.odyssey.plugin.config.ConfigManager;
import net.whimxiqal.odyssey.plugin.message.Messages;
import net.whimxiqal.odyssey.plugin.message.OdysseyMessages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The {@code /odyssey} admin/utility command tree. For the Phase 6a foundation it carries only
 * {@code reload}; waypoints, portals, cancel, and trips join it in later sub-phases.
 */
final class OdysseyCommand {

  private static final String PERMISSION_RELOAD = "odyssey.admin.reload";

  private OdysseyCommand() {
  }

  /**
   * Builds the {@code /odyssey} command node.
   *
   * @param config the config manager (for reload)
   * @param keys the registered config keys (to re-read after reload)
   * @param messages the message renderer
   * @return the command node
   */
  static LiteralCommandNode<CommandSourceStack> build(
      ConfigManager config, ConfigKeys keys, Messages messages) {
    return Commands.literal("odyssey")
        .executes(ctx -> {
          CommandSender sender = ctx.getSource().getSender();
          messages.send(sender, localeOf(sender, messages), OdysseyMessages.ODYSSEY_USAGE);
          return Command.SINGLE_SUCCESS;
        })
        .then(Commands.literal("reload")
            .executes(ctx -> reload(ctx.getSource().getSender(), config, keys, messages)))
        .build();
  }

  private static int reload(
      CommandSender sender, ConfigManager config, ConfigKeys keys, Messages messages) {
    Locale locale = localeOf(sender, messages);
    if (!sender.hasPermission(PERMISSION_RELOAD)) {
      messages.send(sender, locale, OdysseyMessages.NO_PERMISSION);
      return Command.SINGLE_SUCCESS;
    }
    List<String> restartRequired = config.reload();
    messages.setShowPrefix(config.get(keys.messagesShowPrefix));
    messages.send(sender, locale, OdysseyMessages.RELOAD_SUCCESS);
    if (!restartRequired.isEmpty()) {
      messages.send(sender, locale, OdysseyMessages.RELOAD_RESTART_REQUIRED,
          String.join(", ", restartRequired));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static Locale localeOf(CommandSender sender, Messages messages) {
    return sender instanceof Player player ? player.locale() : messages.defaultLocale();
  }
}
