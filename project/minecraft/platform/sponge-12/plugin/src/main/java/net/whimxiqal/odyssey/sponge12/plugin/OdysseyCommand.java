/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.whimxiqal.odyssey.plugin.Permissions;
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
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCompletion;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.CommandContext;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.command.parameter.managed.Flag;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;

/** The {@code /odyssey} (alias {@code /ody}) admin/utility command tree. */
final class OdysseyCommand {

  private OdysseyCommand() {}

  static Command.Parameterized build(
      ConfigManager config,
      ConfigKeys keys,
      Messages messages,
      Log4jOdysseyLogger log,
      WaypointDao waypoints,
      PortalTransitionDao portals,
      TripManager<Entity, SpongeTripAgent, ServerLocation> trips,
      SearchRegistry<ServerLocation> searches) {
    Parameter.Value<Integer> cancelId = Parameter.integerNumber().key("id").optional().build();
    Parameter.Value<String> name = Parameter.string().key("name").build();
    Parameter.Value<String> unsetName =
        Parameter.string()
            .key("name")
            .completer(
                (ctx, input) ->
                    suggestWaypoints(
                        ctx,
                        input,
                        waypoints,
                        ctx.hasPermission(Permissions.WAYPOINT_GLOBAL.value())))
            .build();
    // A valueless switch (-global), gated on the admin node. Flag.of(Parameter, …) would instead
    // build a flag that takes an argument, which is not what `-global` means here.
    Flag global =
        Flag.builder().alias("global").setPermission(Permissions.WAYPOINT_GLOBAL.value()).build();

    Command.Parameterized reload =
        Command.builder()
            .permission(Permissions.RELOAD.value())
            .executor(ctx -> reload(ctx, config, keys, messages, log))
            .build();
    Command.Parameterized cancel =
        Command.builder()
            .permission(Permissions.NAVIGATE.value())
            .addParameter(cancelId)
            .executor(ctx -> cancel(ctx, messages, trips, searches, cancelId))
            .build();
    Command.Parameterized tripsCmd =
        Command.builder()
            .permission(Permissions.NAVIGATE.value())
            .executor(ctx -> trips(ctx, messages, trips))
            .build();
    Command.Parameterized portalsClear =
        Command.builder()
            .permission(Permissions.PORTALS.value())
            .executor(ctx -> clearPortals(ctx, messages, portals))
            .build();
    Command.Parameterized portalsCmd =
        Command.builder()
            .permission(Permissions.PORTALS.value())
            .addChild(portalsClear, "clear")
            .executor(ctx -> help(ctx, messages))
            .build();
    Command.Parameterized waypointSet =
        Command.builder()
            .addFlag(global)
            .addParameter(name)
            .executor(ctx -> setWaypoint(ctx, waypoints, messages, name))
            .build();
    Command.Parameterized waypointUnset =
        Command.builder()
            .addFlag(global)
            .addParameter(unsetName)
            .executor(ctx -> unsetWaypoint(ctx, waypoints, messages, unsetName))
            .build();
    Command.Parameterized waypointList =
        Command.builder().executor(ctx -> listWaypoints(ctx, messages, waypoints)).build();
    Command.Parameterized waypointCmd =
        Command.builder()
            .permission(Permissions.WAYPOINT.value())
            .addChild(waypointSet, "set")
            .addChild(waypointUnset, "unset")
            .addChild(waypointList, "list")
            .executor(ctx -> help(ctx, messages))
            .build();

    return Command.builder()
        .shortDescription(net.kyori.adventure.text.Component.text("Odyssey admin and utilities"))
        .addChild(reload, "reload")
        .addChild(cancel, "cancel")
        .addChild(tripsCmd, "trips")
        .addChild(portalsCmd, "portals")
        .addChild(waypointCmd, "waypoint")
        .executor(ctx -> help(ctx, messages))
        .build();
  }

  private static CommandResult help(CommandContext ctx, Messages messages) {
    Audience audience = ctx.cause().audience();
    Locale locale = localeOf(ctx, messages);
    messages.send(audience, locale, OdysseyMessages.HELP_HEADER);
    SpongeCommandHelp.line(
        audience, messages, locale, "/odyssey reload", "command.odyssey.help.reload");
    SpongeCommandHelp.line(
        audience, messages, locale, "/odyssey cancel [id|all]", "command.odyssey.help.cancel");
    SpongeCommandHelp.line(
        audience, messages, locale, "/odyssey trips", "command.odyssey.help.trips");
    SpongeCommandHelp.line(
        audience,
        messages,
        locale,
        "/odyssey waypoint set|unset|list <name> [-global]",
        "command.odyssey.help.waypoint");
    SpongeCommandHelp.line(
        audience, messages, locale, "/odyssey portals clear", "command.odyssey.help.portals");
    return CommandResult.success();
  }

  private static CommandResult reload(
      CommandContext ctx,
      ConfigManager config,
      ConfigKeys keys,
      Messages messages,
      Log4jOdysseyLogger log) {
    Audience audience = ctx.cause().audience();
    Locale locale = localeOf(ctx, messages);
    List<String> restartRequired = config.reload();
    messages.setShowPrefix(config.get(keys.messagesShowPrefix));
    log.setLevel(config.get(keys.loggingLevel));
    messages.send(audience, locale, OdysseyMessages.RELOAD_SUCCESS);
    if (!restartRequired.isEmpty()) {
      messages.send(
          audience,
          locale,
          OdysseyMessages.RELOAD_RESTART_REQUIRED,
          String.join(", ", restartRequired));
    }
    return CommandResult.success();
  }

  private static CommandResult cancel(
      CommandContext ctx,
      Messages messages,
      TripManager<Entity, SpongeTripAgent, ServerLocation> trips,
      SearchRegistry<ServerLocation> searches,
      Parameter.Value<Integer> idParam) {
    Locale locale = localeOf(ctx, messages);
    Optional<ServerPlayer> player = ctx.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      messages.send(ctx.cause().audience(), locale, OdysseyMessages.PLAYERS_ONLY);
      return CommandResult.success();
    }
    UUID uuid = player.get().uniqueId();
    Optional<Integer> id = ctx.one(idParam);
    if (id.isPresent()) {
      if (trips.cancel(uuid, id.get())) {
        messages.send(player.get(), locale, OdysseyMessages.CANCEL_TRIP, id.get());
      } else {
        messages.send(player.get(), locale, OdysseyMessages.CANCEL_NOT_FOUND, id.get());
      }
      return CommandResult.success();
    }
    int cancelled = trips.trips(uuid).size();
    trips.stopAll(uuid);
    cancelled += searches.cancelAll(uuid);
    if (cancelled == 0) {
      messages.send(player.get(), locale, OdysseyMessages.CANCEL_NOTHING);
    } else {
      messages.send(player.get(), locale, OdysseyMessages.CANCEL_DONE, cancelled);
    }
    return CommandResult.success();
  }

  private static CommandResult trips(
      CommandContext ctx,
      Messages messages,
      TripManager<Entity, SpongeTripAgent, ServerLocation> trips) {
    Locale locale = localeOf(ctx, messages);
    Optional<ServerPlayer> player = ctx.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      messages.send(ctx.cause().audience(), locale, OdysseyMessages.PLAYERS_ONLY);
      return CommandResult.success();
    }
    List<Trip<Entity, SpongeTripAgent, ServerLocation>> active =
        trips.trips(player.get().uniqueId());
    if (active.isEmpty()) {
      messages.send(player.get(), locale, OdysseyMessages.TRIPS_NONE);
      return CommandResult.success();
    }
    messages.send(player.get(), locale, OdysseyMessages.TRIPS_HEADER, active.size());
    for (Trip<Entity, SpongeTripAgent, ServerLocation> trip : active) {
      messages.send(
          player.get(),
          locale,
          OdysseyMessages.TRIPS_ENTRY,
          trip.id(),
          trip.destination(),
          messages.formatDuration(locale, trip.remainingSeconds()));
    }
    return CommandResult.success();
  }

  private static CommandResult clearPortals(
      CommandContext ctx, Messages messages, PortalTransitionDao portals) {
    int removed = portals.clear();
    messages.send(
        ctx.cause().audience(), localeOf(ctx, messages), OdysseyMessages.PORTALS_CLEARED, removed);
    return CommandResult.success();
  }

  private static CommandResult setWaypoint(
      CommandContext ctx,
      WaypointDao waypoints,
      Messages messages,
      Parameter.Value<String> nameParam) {
    Locale locale = localeOf(ctx, messages);
    Optional<ServerPlayer> player = ctx.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      messages.send(ctx.cause().audience(), locale, OdysseyMessages.PLAYERS_ONLY);
      return CommandResult.success();
    }
    boolean global = ctx.hasFlag("global");
    if (global && !player.get().hasPermission(Permissions.WAYPOINT_GLOBAL.value())) {
      messages.send(player.get(), locale, OdysseyMessages.NO_PERMISSION);
      return CommandResult.success();
    }
    String name = ctx.requireOne(nameParam);
    ServerLocation location = player.get().serverLocation();
    String world = player.get().world().key().asString();
    Waypoint waypoint =
        global
            ? Waypoint.global(name, world, location.blockX(), location.blockY(), location.blockZ())
            : Waypoint.personal(
                player.get().uniqueId(),
                name,
                world,
                location.blockX(),
                location.blockY(),
                location.blockZ());
    try {
      waypoints.put(waypoint);
    } catch (DataStoreException e) {
      messages.send(player.get(), locale, OdysseyMessages.WAYPOINT_STORE_ERROR);
      return CommandResult.success();
    }
    messages.send(player.get(), locale, OdysseyMessages.WAYPOINT_SET, name);
    return CommandResult.success();
  }

  private static CommandResult unsetWaypoint(
      CommandContext ctx,
      WaypointDao waypoints,
      Messages messages,
      Parameter.Value<String> nameParam) {
    Locale locale = localeOf(ctx, messages);
    Optional<ServerPlayer> player = ctx.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      messages.send(ctx.cause().audience(), locale, OdysseyMessages.PLAYERS_ONLY);
      return CommandResult.success();
    }
    boolean global = ctx.hasFlag("global");
    if (global && !player.get().hasPermission(Permissions.WAYPOINT_GLOBAL.value())) {
      messages.send(player.get(), locale, OdysseyMessages.NO_PERMISSION);
      return CommandResult.success();
    }
    String name = ctx.requireOne(nameParam);
    boolean removed;
    try {
      removed =
          waypoints.remove(global ? Optional.empty() : Optional.of(player.get().uniqueId()), name);
    } catch (DataStoreException e) {
      messages.send(player.get(), locale, OdysseyMessages.WAYPOINT_STORE_ERROR);
      return CommandResult.success();
    }
    messages.send(
        player.get(),
        locale,
        removed ? OdysseyMessages.WAYPOINT_UNSET : OdysseyMessages.WAYPOINT_NOT_FOUND,
        name);
    return CommandResult.success();
  }

  private static CommandResult listWaypoints(
      CommandContext ctx, Messages messages, WaypointDao waypoints) {
    Locale locale = localeOf(ctx, messages);
    Optional<ServerPlayer> player = ctx.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      messages.send(ctx.cause().audience(), locale, OdysseyMessages.PLAYERS_ONLY);
      return CommandResult.success();
    }
    List<Waypoint> personal = waypoints.ownedBy(player.get().uniqueId());
    List<Waypoint> global = waypoints.global();
    if (personal.isEmpty() && global.isEmpty()) {
      messages.send(player.get(), locale, OdysseyMessages.WAYPOINT_LIST_NONE);
      return CommandResult.success();
    }
    messages.send(
        player.get(),
        locale,
        OdysseyMessages.WAYPOINT_LIST_HEADER,
        personal.size() + global.size());
    for (Waypoint waypoint : personal) {
      messages.send(
          player.get(),
          locale,
          OdysseyMessages.WAYPOINT_LIST_ENTRY,
          waypoint.name(),
          where(waypoint));
    }
    for (Waypoint waypoint : global) {
      messages.send(
          player.get(),
          locale,
          OdysseyMessages.WAYPOINT_LIST_GLOBAL,
          waypoint.name(),
          where(waypoint));
    }
    return CommandResult.success();
  }

  private static List<CommandCompletion> suggestWaypoints(
      CommandContext ctx, String input, WaypointDao waypoints, boolean includeGlobal) {
    Optional<ServerPlayer> player = ctx.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      return List.of();
    }
    String prefix = input.toLowerCase(Locale.ROOT);
    List<CommandCompletion> completions = new java.util.ArrayList<>();
    List<Waypoint> candidates =
        new java.util.ArrayList<>(waypoints.ownedBy(player.get().uniqueId()));
    if (includeGlobal) {
      candidates.addAll(waypoints.global());
    }
    for (Waypoint waypoint : candidates) {
      if (waypoint.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
        completions.add(CommandCompletion.of(waypoint.name()));
      }
    }
    return completions;
  }

  private static String where(Waypoint waypoint) {
    return waypoint.world() + " " + waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z();
  }

  private static Locale localeOf(CommandContext ctx, Messages messages) {
    return ctx.cause()
        .first(ServerPlayer.class)
        .map(ServerPlayer::locale)
        .orElse(messages.defaultLocale());
  }
}
