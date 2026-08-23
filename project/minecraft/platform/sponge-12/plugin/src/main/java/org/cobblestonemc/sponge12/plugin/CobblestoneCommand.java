/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import org.cobblestonemc.plugin.Permissions;
import org.cobblestonemc.plugin.config.ConfigKeys;
import org.cobblestonemc.plugin.config.ConfigManager;
import org.cobblestonemc.plugin.data.DataStoreException;
import org.cobblestonemc.plugin.data.Location;
import org.cobblestonemc.plugin.data.LocationDao;
import org.cobblestonemc.plugin.data.PortalTransitionDao;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.trip.Trip;
import org.cobblestonemc.plugin.trip.TripManager;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCompletion;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.CommandContext;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.command.parameter.managed.Flag;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;

/** The {@code /cobblestone} (alias {@code /ody}) admin/utility command tree. */
final class CobblestoneCommand {

  private CobblestoneCommand() {}

  static Command.Parameterized build(
      ConfigManager config,
      ConfigKeys keys,
      Messages messages,
      Log4JCobblestoneLogger log,
      LocationDao locations,
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
                    suggestLocations(
                        ctx,
                        input,
                        locations,
                        ctx.hasPermission(Permissions.LOCATION_GLOBAL.value())))
            .build();
    // A valueless switch (-global), gated on the admin node. Flag.of(Parameter, …) would instead
    // build a flag that takes an argument, which is not what `-global` means here.
    Flag global =
        Flag.builder().alias("global").setPermission(Permissions.LOCATION_GLOBAL.value()).build();

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
    Command.Parameterized locationSet =
        Command.builder()
            .addFlag(global)
            .addParameter(name)
            .executor(ctx -> setLocation(ctx, locations, messages, name))
            .build();
    Command.Parameterized locationUnset =
        Command.builder()
            .addFlag(global)
            .addParameter(unsetName)
            .executor(ctx -> unsetLocation(ctx, locations, messages, unsetName))
            .build();
    Command.Parameterized locationList =
        Command.builder().executor(ctx -> listLocations(ctx, messages, locations)).build();
    Command.Parameterized locationCmd =
        Command.builder()
            .permission(Permissions.LOCATION.value())
            .addChild(locationSet, "set")
            .addChild(locationUnset, "unset")
            .addChild(locationList, "list")
            .executor(ctx -> help(ctx, messages))
            .build();

    return Command.builder()
        .shortDescription(
            net.kyori.adventure.text.Component.text("Cobblestone admin and utilities"))
        .addChild(reload, "reload")
        .addChild(cancel, "cancel")
        .addChild(tripsCmd, "trips")
        .addChild(portalsCmd, "portals")
        .addChild(locationCmd, "location")
        .executor(ctx -> help(ctx, messages))
        .build();
  }

  private static CommandResult help(CommandContext ctx, Messages messages) {
    Audience audience = ctx.cause().audience();
    Locale locale = localeOf(ctx, messages);
    messages.send(audience, locale, CobblestoneMessages.HELP_HEADER);
    SpongeCommandHelp.line(
        audience, messages, locale, "/cobblestone reload", "command.cobblestone.help.reload");
    SpongeCommandHelp.line(
        audience,
        messages,
        locale,
        "/cobblestone cancel [id|all]",
        "command.cobblestone.help.cancel");
    SpongeCommandHelp.line(
        audience, messages, locale, "/cobblestone trips", "command.cobblestone.help.trips");
    SpongeCommandHelp.line(
        audience,
        messages,
        locale,
        "/cobblestone location set|unset|list <name> [-global]",
        "command.cobblestone.help.location");
    SpongeCommandHelp.line(
        audience,
        messages,
        locale,
        "/cobblestone portals clear",
        "command.cobblestone.help.portals");
    return CommandResult.success();
  }

  private static CommandResult reload(
      CommandContext ctx,
      ConfigManager config,
      ConfigKeys keys,
      Messages messages,
      Log4JCobblestoneLogger log) {
    Audience audience = ctx.cause().audience();
    Locale locale = localeOf(ctx, messages);
    List<String> restartRequired = config.reload();
    messages.setShowPrefix(config.get(keys.messagesShowPrefix));
    log.setLevel(config.get(keys.loggingLevel));
    messages.send(audience, locale, CobblestoneMessages.RELOAD_SUCCESS);
    if (!restartRequired.isEmpty()) {
      messages.send(
          audience,
          locale,
          CobblestoneMessages.RELOAD_RESTART_REQUIRED,
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
      messages.send(ctx.cause().audience(), locale, CobblestoneMessages.PLAYERS_ONLY);
      return CommandResult.success();
    }
    UUID uuid = player.get().uniqueId();
    Optional<Integer> id = ctx.one(idParam);
    if (id.isPresent()) {
      if (trips.cancel(uuid, id.get())) {
        messages.send(player.get(), locale, CobblestoneMessages.CANCEL_TRIP, id.get());
      } else {
        messages.send(player.get(), locale, CobblestoneMessages.CANCEL_NOT_FOUND, id.get());
      }
      return CommandResult.success();
    }
    int cancelled = trips.trips(uuid).size();
    trips.stopAll(uuid);
    cancelled += searches.cancelAll(uuid);
    if (cancelled == 0) {
      messages.send(player.get(), locale, CobblestoneMessages.CANCEL_NOTHING);
    } else {
      messages.send(player.get(), locale, CobblestoneMessages.CANCEL_DONE, cancelled);
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
      messages.send(ctx.cause().audience(), locale, CobblestoneMessages.PLAYERS_ONLY);
      return CommandResult.success();
    }
    List<Trip<Entity, SpongeTripAgent, ServerLocation>> active =
        trips.trips(player.get().uniqueId());
    if (active.isEmpty()) {
      messages.send(player.get(), locale, CobblestoneMessages.TRIPS_NONE);
      return CommandResult.success();
    }
    messages.send(player.get(), locale, CobblestoneMessages.TRIPS_HEADER, active.size());
    for (Trip<Entity, SpongeTripAgent, ServerLocation> trip : active) {
      messages.send(
          player.get(),
          locale,
          CobblestoneMessages.TRIPS_ENTRY,
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
        ctx.cause().audience(),
        localeOf(ctx, messages),
        CobblestoneMessages.PORTALS_CLEARED,
        removed);
    return CommandResult.success();
  }

  private static CommandResult setLocation(
      CommandContext ctx,
      LocationDao locations,
      Messages messages,
      Parameter.Value<String> nameParam) {
    Locale locale = localeOf(ctx, messages);
    Optional<ServerPlayer> player = ctx.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      messages.send(ctx.cause().audience(), locale, CobblestoneMessages.PLAYERS_ONLY);
      return CommandResult.success();
    }
    boolean global = ctx.hasFlag("global");
    if (global && !player.get().hasPermission(Permissions.LOCATION_GLOBAL.value())) {
      messages.send(player.get(), locale, CobblestoneMessages.NO_PERMISSION);
      return CommandResult.success();
    }
    String name = ctx.requireOne(nameParam);
    ServerLocation bukkitLocation = player.get().serverLocation();
    String world = player.get().world().key().asString();
    Location location =
        global
            ? Location.global(
                name,
                world,
                bukkitLocation.blockX(),
                bukkitLocation.blockY(),
                bukkitLocation.blockZ())
            : Location.personal(
                player.get().uniqueId(),
                name,
                world,
                bukkitLocation.blockX(),
                bukkitLocation.blockY(),
                bukkitLocation.blockZ());
    try {
      locations.put(location);
    } catch (DataStoreException e) {
      messages.send(player.get(), locale, CobblestoneMessages.LOCATION_STORE_ERROR);
      return CommandResult.success();
    }
    messages.send(player.get(), locale, CobblestoneMessages.LOCATION_SET, name);
    return CommandResult.success();
  }

  private static CommandResult unsetLocation(
      CommandContext ctx,
      LocationDao locations,
      Messages messages,
      Parameter.Value<String> nameParam) {
    Locale locale = localeOf(ctx, messages);
    Optional<ServerPlayer> player = ctx.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      messages.send(ctx.cause().audience(), locale, CobblestoneMessages.PLAYERS_ONLY);
      return CommandResult.success();
    }
    boolean global = ctx.hasFlag("global");
    if (global && !player.get().hasPermission(Permissions.LOCATION_GLOBAL.value())) {
      messages.send(player.get(), locale, CobblestoneMessages.NO_PERMISSION);
      return CommandResult.success();
    }
    String name = ctx.requireOne(nameParam);
    boolean removed;
    try {
      removed =
          locations.remove(global ? Optional.empty() : Optional.of(player.get().uniqueId()), name);
    } catch (DataStoreException e) {
      messages.send(player.get(), locale, CobblestoneMessages.LOCATION_STORE_ERROR);
      return CommandResult.success();
    }
    messages.send(
        player.get(),
        locale,
        removed ? CobblestoneMessages.LOCATION_UNSET : CobblestoneMessages.LOCATION_NOT_FOUND,
        name);
    return CommandResult.success();
  }

  private static CommandResult listLocations(
      CommandContext ctx, Messages messages, LocationDao locations) {
    Locale locale = localeOf(ctx, messages);
    Optional<ServerPlayer> player = ctx.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      messages.send(ctx.cause().audience(), locale, CobblestoneMessages.PLAYERS_ONLY);
      return CommandResult.success();
    }
    List<Location> personal = locations.ownedBy(player.get().uniqueId());
    List<Location> global = locations.global();
    if (personal.isEmpty() && global.isEmpty()) {
      messages.send(player.get(), locale, CobblestoneMessages.LOCATION_LIST_NONE);
      return CommandResult.success();
    }
    messages.send(
        player.get(),
        locale,
        CobblestoneMessages.LOCATION_LIST_HEADER,
        personal.size() + global.size());
    for (Location location : personal) {
      messages.send(
          player.get(),
          locale,
          CobblestoneMessages.LOCATION_LIST_ENTRY,
          location.name(),
          where(location));
    }
    for (Location location : global) {
      messages.send(
          player.get(),
          locale,
          CobblestoneMessages.LOCATION_LIST_GLOBAL,
          location.name(),
          where(location));
    }
    return CommandResult.success();
  }

  private static List<CommandCompletion> suggestLocations(
      CommandContext ctx, String input, LocationDao locations, boolean includeGlobal) {
    Optional<ServerPlayer> player = ctx.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      return List.of();
    }
    String prefix = input.toLowerCase(Locale.ROOT);
    List<CommandCompletion> completions = new java.util.ArrayList<>();
    List<Location> candidates =
        new java.util.ArrayList<>(locations.ownedBy(player.get().uniqueId()));
    if (includeGlobal) {
      candidates.addAll(locations.global());
    }
    for (Location location : candidates) {
      if (location.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
        completions.add(CommandCompletion.of(location.name()));
      }
    }
    return completions;
  }

  private static String where(Location location) {
    return location.world() + " " + location.x() + ", " + location.y() + ", " + location.z();
  }

  private static Locale localeOf(CommandContext ctx, Messages messages) {
    return ctx.cause()
        .first(ServerPlayer.class)
        .map(ServerPlayer::locale)
        .orElse(messages.defaultLocale());
  }
}
