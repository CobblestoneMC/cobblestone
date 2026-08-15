/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.plugin.config.ConfigKeys;
import net.whimxiqal.odyssey.plugin.config.ConfigManager;
import net.whimxiqal.odyssey.plugin.data.DataStoreException;
import net.whimxiqal.odyssey.plugin.data.PortalTransitionDao;
import net.whimxiqal.odyssey.plugin.data.Waypoint;
import net.whimxiqal.odyssey.plugin.data.WaypointDao;
import net.whimxiqal.odyssey.plugin.message.Messages;
import net.whimxiqal.odyssey.plugin.message.OdysseyMessages;
import net.whimxiqal.odyssey.plugin.search.SearchRegistry;
import net.whimxiqal.odyssey.plugin.trip.Trip;
import net.whimxiqal.odyssey.plugin.trip.TripManager;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The {@code /odyssey} admin/utility command tree. For Phase 6b it carries {@code reload} and the
 * {@code waypoint set/unset} subcommands; portals, cancel, and trips join it in later sub-phases.
 */
final class OdysseyCommand {

  private static final String PERMISSION_RELOAD = "odyssey.admin.reload";
  private static final String PERMISSION_WAYPOINT_GLOBAL = "odyssey.admin.waypoint.global";
  private static final String PERMISSION_PORTALS = "odyssey.admin.portals";

  private OdysseyCommand() {}

  /**
   * Builds the {@code /odyssey} command node.
   *
   * @param config the config manager (for reload)
   * @param keys the registered config keys (to re-read after reload)
   * @param messages the message renderer
   * @param waypoints the waypoint DAO (for {@code waypoint set/unset})
   * @param portals the portal-transition DAO (for {@code portals clear})
   * @param trips the trip manager (for {@code cancel}/{@code trips})
   * @param searches the search registry (for {@code cancel})
   * @return the command node
   */
  static LiteralCommandNode<CommandSourceStack> build(
      ConfigManager config,
      ConfigKeys keys,
      Messages messages,
      JulOdysseyLogger log,
      WaypointDao waypoints,
      PortalTransitionDao portals,
      TripManager<Entity, PaperTripAgent, Location> trips,
      SearchRegistry<Location> searches) {
    return Commands.literal("odyssey")
        .executes(ctx -> showHelp(ctx.getSource().getSender(), messages))
        .then(
            Commands.literal("help")
                .executes(ctx -> showHelp(ctx.getSource().getSender(), messages)))
        .then(
            Commands.literal("?").executes(ctx -> showHelp(ctx.getSource().getSender(), messages)))
        .then(
            Commands.literal("reload")
                .executes(ctx -> reload(ctx.getSource().getSender(), config, keys, messages, log)))
        .then(
            Commands.literal("cancel")
                .executes(ctx -> cancelAll(ctx.getSource().getSender(), messages, trips, searches))
                .then(
                    Commands.literal("all")
                        .executes(
                            ctx ->
                                cancelAll(ctx.getSource().getSender(), messages, trips, searches)))
                .then(
                    Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(
                            ctx ->
                                cancelTrip(
                                    ctx.getSource().getSender(),
                                    messages,
                                    trips,
                                    IntegerArgumentType.getInteger(ctx, "id")))))
        .then(
            Commands.literal("trips")
                .executes(ctx -> trips(ctx.getSource().getSender(), messages, trips)))
        .then(
            Commands.literal("portals")
                .executes(ctx -> showHelp(ctx.getSource().getSender(), messages))
                .then(
                    Commands.literal("clear")
                        .executes(
                            ctx -> clearPortals(ctx.getSource().getSender(), messages, portals))))
        .then(
            Commands.literal("waypoint")
                .executes(ctx -> showHelp(ctx.getSource().getSender(), messages))
                .then(
                    Commands.literal("set")
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> setWaypoint(ctx, waypoints, messages, false))
                                .then(
                                    Commands.literal("-global")
                                        .executes(
                                            ctx -> setWaypoint(ctx, waypoints, messages, true)))))
                .then(
                    Commands.literal("unset")
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .suggests(
                                    (ctx, builder) ->
                                        suggestWaypoints(
                                            ctx.getSource().getSender(), builder, waypoints))
                                .executes(ctx -> unsetWaypoint(ctx, waypoints, messages, false))
                                .then(
                                    Commands.literal("-global")
                                        .executes(
                                            ctx -> unsetWaypoint(ctx, waypoints, messages, true)))))
                .then(
                    Commands.literal("list")
                        .executes(
                            ctx ->
                                listWaypoints(ctx.getSource().getSender(), messages, waypoints))))
        .build();
  }

  private static int reload(
      CommandSender sender,
      ConfigManager config,
      ConfigKeys keys,
      Messages messages,
      JulOdysseyLogger log) {
    Locale locale = localeOf(sender, messages);
    if (!sender.hasPermission(PERMISSION_RELOAD)) {
      messages.send(sender, locale, OdysseyMessages.NO_PERMISSION);
      return Command.SINGLE_SUCCESS;
    }
    final List<String> restartRequired = config.reload();
    messages.setShowPrefix(config.get(keys.messagesShowPrefix));
    log.setLevel(config.get(keys.loggingLevel));
    messages.send(sender, locale, OdysseyMessages.RELOAD_SUCCESS);
    if (!restartRequired.isEmpty()) {
      messages.send(
          sender,
          locale,
          OdysseyMessages.RELOAD_RESTART_REQUIRED,
          String.join(", ", restartRequired));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int showHelp(CommandSender sender, Messages messages) {
    Locale locale = localeOf(sender, messages);
    messages.send(sender, locale, OdysseyMessages.HELP_HEADER);
    CommandHelp.line(sender, messages, locale, "/odyssey reload", "command.odyssey.help.reload");
    CommandHelp.line(
        sender, messages, locale, "/odyssey cancel [id|all]", "command.odyssey.help.cancel");
    CommandHelp.line(sender, messages, locale, "/odyssey trips", "command.odyssey.help.trips");
    CommandHelp.line(
        sender,
        messages,
        locale,
        "/odyssey waypoint set|unset|list <name> [-global]",
        "command.odyssey.help.waypoint");
    CommandHelp.line(
        sender, messages, locale, "/odyssey portals clear", "command.odyssey.help.portals");
    return Command.SINGLE_SUCCESS;
  }

  private static int listWaypoints(CommandSender sender, Messages messages, WaypointDao waypoints) {
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, OdysseyMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    List<Waypoint> personal = waypoints.ownedBy(player.getUniqueId());
    List<Waypoint> global = waypoints.global();
    if (personal.isEmpty() && global.isEmpty()) {
      messages.send(player, locale, OdysseyMessages.WAYPOINT_LIST_NONE);
      return Command.SINGLE_SUCCESS;
    }
    messages.send(
        player, locale, OdysseyMessages.WAYPOINT_LIST_HEADER, personal.size() + global.size());
    for (Waypoint waypoint : personal) {
      messages.send(
          player, locale, OdysseyMessages.WAYPOINT_LIST_ENTRY, waypoint.name(), location(waypoint));
    }
    for (Waypoint waypoint : global) {
      messages.send(
          player,
          locale,
          OdysseyMessages.WAYPOINT_LIST_GLOBAL,
          waypoint.name(),
          location(waypoint));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static String location(Waypoint waypoint) {
    return waypoint.world() + " " + waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z();
  }

  private static CompletableFuture<Suggestions> suggestWaypoints(
      CommandSender sender, SuggestionsBuilder builder, WaypointDao waypoints) {
    if (sender instanceof Player player) {
      String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
      List<Waypoint> candidates = new ArrayList<>(waypoints.ownedBy(player.getUniqueId()));
      candidates.addAll(waypoints.global());
      for (Waypoint waypoint : candidates) {
        if (waypoint.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
          builder.suggest(waypoint.name());
        }
      }
    }
    return builder.buildFuture();
  }

  private static int cancelAll(
      CommandSender sender,
      Messages messages,
      TripManager<Entity, PaperTripAgent, Location> trips,
      SearchRegistry<Location> searches) {
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

  private static int cancelTrip(
      CommandSender sender,
      Messages messages,
      TripManager<Entity, PaperTripAgent, Location> trips,
      int id) {
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, OdysseyMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    if (trips.cancel(player.getUniqueId(), id)) {
      messages.send(player, locale, OdysseyMessages.CANCEL_TRIP, id);
    } else {
      messages.send(player, locale, OdysseyMessages.CANCEL_NOT_FOUND, id);
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int trips(
      CommandSender sender,
      Messages messages,
      TripManager<Entity, PaperTripAgent, Location> trips) {
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, OdysseyMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    List<Trip<Entity, PaperTripAgent, Location>> active = trips.trips(player.getUniqueId());
    if (active.isEmpty()) {
      messages.send(player, locale, OdysseyMessages.TRIPS_NONE);
      return Command.SINGLE_SUCCESS;
    }
    messages.send(player, locale, OdysseyMessages.TRIPS_HEADER, active.size());
    for (Trip<Entity, PaperTripAgent, Location> trip : active) {
      messages.send(
          player,
          locale,
          OdysseyMessages.TRIPS_ENTRY,
          trip.id(),
          trip.destination(),
          messages.formatDuration(locale, trip.remainingSeconds()));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int clearPortals(
      CommandSender sender, Messages messages, PortalTransitionDao portals) {
    Locale locale = localeOf(sender, messages);
    if (!sender.hasPermission(PERMISSION_PORTALS)) {
      messages.send(sender, locale, OdysseyMessages.NO_PERMISSION);
      return Command.SINGLE_SUCCESS;
    }
    int removed = portals.clear();
    messages.send(sender, locale, OdysseyMessages.PORTALS_CLEARED, removed);
    return Command.SINGLE_SUCCESS;
  }

  private static int setWaypoint(
      CommandContext<CommandSourceStack> ctx,
      WaypointDao waypoints,
      Messages messages,
      boolean global) {
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
    Waypoint waypoint =
        global
            ? Waypoint.global(
                name, world, location.getBlockX(), location.getBlockY(), location.getBlockZ())
            : Waypoint.personal(
                player.getUniqueId(),
                name,
                world,
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());
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
      CommandContext<CommandSourceStack> ctx,
      WaypointDao waypoints,
      Messages messages,
      boolean global) {
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
      removed =
          waypoints.remove(global ? Optional.empty() : Optional.of(player.getUniqueId()), name);
    } catch (DataStoreException e) {
      messages.send(sender, locale, OdysseyMessages.WAYPOINT_STORE_ERROR);
      return Command.SINGLE_SUCCESS;
    }
    messages.send(
        sender,
        locale,
        removed ? OdysseyMessages.WAYPOINT_UNSET : OdysseyMessages.WAYPOINT_NOT_FOUND,
        name);
    return Command.SINGLE_SUCCESS;
  }

  private static Locale localeOf(CommandSender sender, Messages messages) {
    return sender instanceof Player player ? player.locale() : messages.defaultLocale();
  }
}
