/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.whimxiqal.odyssey.plugin.config.ConfigKeys;
import net.whimxiqal.odyssey.plugin.config.ConfigManager;
import net.whimxiqal.odyssey.plugin.data.DataStoreException;
import net.whimxiqal.odyssey.plugin.data.Waypoint;
import net.whimxiqal.odyssey.plugin.data.WaypointDao;
import net.whimxiqal.odyssey.plugin.message.Messages;
import net.whimxiqal.odyssey.plugin.message.OdysseyMessages;
import net.whimxiqal.odyssey.plugin.trip.Trip;
import net.whimxiqal.odyssey.plugin.trip.TripManager;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The {@code /odyssey} admin/utility command tree. For Phase 6b it carries {@code reload} and the
 * {@code waypoint set/unset} subcommands; portals, cancel, and trips join it in later sub-phases.
 */
final class OdysseyCommand {

  private static final String PERMISSION_RELOAD = "odyssey.admin.reload";
  private static final String PERMISSION_WAYPOINT_GLOBAL = "odyssey.admin.waypoint.global";

  private OdysseyCommand() {
  }

  /**
   * Builds the {@code /odyssey} command node.
   *
   * @param config the config manager (for reload)
   * @param keys the registered config keys (to re-read after reload)
   * @param messages the message renderer
   * @param waypoints the waypoint DAO (for {@code waypoint set/unset})
   * @param trips the trip manager (for {@code cancel}/{@code trips})
   * @param searches the search registry (for {@code cancel})
   * @return the command node
   */
  static LiteralCommandNode<CommandSourceStack> build(
      ConfigManager config, ConfigKeys keys, Messages messages, WaypointDao waypoints,
      TripManager<Location> trips, SearchRegistry searches) {
    return Commands.literal("odyssey")
        .executes(ctx -> {
          CommandSender sender = ctx.getSource().getSender();
          // TODO write a splash message for user, containing version number
          messages.send(sender, localeOf(sender, messages), OdysseyMessages.ODYSSEY_USAGE, "/odyssey <subcommand>");
          return Command.SINGLE_SUCCESS;
        })
        .then(Commands.literal("reload")
            .executes(ctx -> reload(ctx.getSource().getSender(), config, keys, messages)))
        .then(Commands.literal("cancel")
            .executes(ctx -> cancel(ctx.getSource().getSender(), messages, trips, searches)))
        .then(Commands.literal("trips")
            .executes(ctx -> trips(ctx.getSource().getSender(), messages, trips)))
        .then(Commands.literal("waypoint")
            .then(Commands.literal("set")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> setWaypoint(ctx, waypoints, messages, false))
                    .then(Commands.literal("-global")
                        .executes(ctx -> setWaypoint(ctx, waypoints, messages, true)))))
            .then(Commands.literal("unset")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> unsetWaypoint(ctx, waypoints, messages, false))
                    .then(Commands.literal("-global")
                        .executes(ctx -> unsetWaypoint(ctx, waypoints, messages, true))))))
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

  private static int cancel(
      CommandSender sender, Messages messages, TripManager<Location> trips, SearchRegistry searches) {
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, OdysseyMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    UUID uuid = player.getUniqueId();
    int cancelled = trips.trips(uuid).size();
    trips.stopAll(uuid);
    cancelled += searches.cancelAll(uuid);
    if (cancelled == 0) {
      messages.send(player, locale, OdysseyMessages.CANCEL_NOTHING);
    } else {
      messages.send(player, locale, OdysseyMessages.CANCEL_DONE, cancelled);
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int trips(CommandSender sender, Messages messages, TripManager<Location> trips) {
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, OdysseyMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    List<Trip<Location>> active = trips.trips(player.getUniqueId());
    if (active.isEmpty()) {
      messages.send(player, locale, OdysseyMessages.TRIPS_NONE);
      return Command.SINGLE_SUCCESS;
    }
    messages.send(player, locale, OdysseyMessages.TRIPS_HEADER, active.size());
    for (Trip<Location> trip : active) {
      messages.send(player, locale, OdysseyMessages.TRIPS_ENTRY, trip.navigatorId());
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int setWaypoint(
      CommandContext<CommandSourceStack> ctx, WaypointDao waypoints, Messages messages, boolean global) {
    CommandSender sender = ctx.getSource().getSender();
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, OdysseyMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    if (global && !player.hasPermission(PERMISSION_WAYPOINT_GLOBAL)) {
      messages.send(sender, locale, OdysseyMessages.NO_PERMISSION);
      return Command.SINGLE_SUCCESS;
    }
    String name = StringArgumentType.getString(ctx, "name");
    Location location = player.getLocation();
    String world = player.getWorld().getKey().asString();
    Waypoint waypoint = global
        ? Waypoint.global(name, world, location.getBlockX(), location.getBlockY(), location.getBlockZ())
        : Waypoint.personal(player.getUniqueId(), name, world,
            location.getBlockX(), location.getBlockY(), location.getBlockZ());
    try {
      waypoints.put(waypoint);
    } catch (DataStoreException e) {
      messages.send(sender, locale, OdysseyMessages.WAYPOINT_STORE_ERROR);
      return Command.SINGLE_SUCCESS;
    }
    messages.send(sender, locale, OdysseyMessages.WAYPOINT_SET, name);
    return Command.SINGLE_SUCCESS;
  }

  private static int unsetWaypoint(
      CommandContext<CommandSourceStack> ctx, WaypointDao waypoints, Messages messages, boolean global) {
    CommandSender sender = ctx.getSource().getSender();
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, OdysseyMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    if (global && !player.hasPermission(PERMISSION_WAYPOINT_GLOBAL)) {
      messages.send(sender, locale, OdysseyMessages.NO_PERMISSION);
      return Command.SINGLE_SUCCESS;
    }
    String name = StringArgumentType.getString(ctx, "name");
    boolean removed;
    try {
      removed = waypoints.remove(global ? Optional.empty() : Optional.of(player.getUniqueId()), name);
    } catch (DataStoreException e) {
      messages.send(sender, locale, OdysseyMessages.WAYPOINT_STORE_ERROR);
      return Command.SINGLE_SUCCESS;
    }
    messages.send(sender, locale,
        removed ? OdysseyMessages.WAYPOINT_UNSET : OdysseyMessages.WAYPOINT_NOT_FOUND, name);
    return Command.SINGLE_SUCCESS;
  }

  private static Locale localeOf(CommandSender sender, Messages messages) {
    return sender instanceof Player player ? player.locale() : messages.defaultLocale();
  }
}
