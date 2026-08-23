/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

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
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.cobblestonemc.plugin.Permissions;
import org.cobblestonemc.plugin.config.ConfigKeys;
import org.cobblestonemc.plugin.config.ConfigManager;
import org.cobblestonemc.plugin.data.DataStoreException;
import org.cobblestonemc.plugin.data.LocationDao;
import org.cobblestonemc.plugin.data.PortalTransitionDao;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.trip.Trip;
import org.cobblestonemc.plugin.trip.TripManager;

/**
 * The {@code /cobblestone} admin/utility command tree. For Phase 6b it carries {@code reload} and
 * the {@code location set/unset} subcommands; portals, cancel, and trips join it in later
 * sub-phases.
 */
final class CobblestoneCommand {

  private CobblestoneCommand() {}

  /**
   * Builds the {@code /cobblestone} command node.
   *
   * @param config the config manager (for reload)
   * @param keys the registered config keys (to re-read after reload)
   * @param messages the message renderer
   * @param locations the location DAO (for {@code location set/unset})
   * @param portals the portal-transition DAO (for {@code portals clear})
   * @param trips the trip manager (for {@code cancel}/{@code trips})
   * @param searches the search registry (for {@code cancel})
   * @return the command node
   */
  static LiteralCommandNode<CommandSourceStack> build(
      ConfigManager config,
      ConfigKeys keys,
      Messages messages,
      JulCobblestoneLogger log,
      LocationDao locations,
      PortalTransitionDao portals,
      TripManager<Entity, PaperTripAgent, Location> trips,
      SearchRegistry<Location> searches) {
    return Commands.literal("cobblestone")
        .executes(ctx -> showHelp(ctx.getSource().getSender(), messages))
        .then(
            Commands.literal("help")
                .executes(ctx -> showHelp(ctx.getSource().getSender(), messages)))
        .then(
            Commands.literal("?").executes(ctx -> showHelp(ctx.getSource().getSender(), messages)))
        .then(
            Commands.literal("reload")
                .requires(source -> source.getSender().hasPermission(Permissions.RELOAD.value()))
                .executes(ctx -> reload(ctx.getSource().getSender(), config, keys, messages, log)))
        .then(
            Commands.literal("cancel")
                .requires(source -> source.getSender().hasPermission(Permissions.NAVIGATE.value()))
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
                .requires(source -> source.getSender().hasPermission(Permissions.NAVIGATE.value()))
                .executes(ctx -> trips(ctx.getSource().getSender(), messages, trips)))
        .then(
            Commands.literal("portals")
                .requires(source -> source.getSender().hasPermission(Permissions.PORTALS.value()))
                .executes(ctx -> showHelp(ctx.getSource().getSender(), messages))
                .then(
                    Commands.literal("clear")
                        .executes(
                            ctx -> clearPortals(ctx.getSource().getSender(), messages, portals))))
        .then(
            Commands.literal("location")
                .requires(source -> source.getSender().hasPermission(Permissions.LOCATION.value()))
                .executes(ctx -> showHelp(ctx.getSource().getSender(), messages))
                .then(
                    Commands.literal("set")
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> setLocation(ctx, locations, messages, false))
                                .then(
                                    Commands.literal("-global")
                                        .requires(
                                            source ->
                                                source
                                                    .getSender()
                                                    .hasPermission(
                                                        Permissions.LOCATION_GLOBAL.value()))
                                        .executes(
                                            ctx -> setLocation(ctx, locations, messages, true)))))
                .then(
                    Commands.literal("unset")
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .suggests(
                                    (ctx, builder) ->
                                        suggestLocations(
                                            ctx.getSource().getSender(),
                                            builder,
                                            locations,
                                            ctx.getSource()
                                                .getSender()
                                                .hasPermission(
                                                    Permissions.LOCATION_GLOBAL.value())))
                                .executes(ctx -> unsetLocation(ctx, locations, messages, false))
                                .then(
                                    Commands.literal("-global")
                                        .requires(
                                            source ->
                                                source
                                                    .getSender()
                                                    .hasPermission(
                                                        Permissions.LOCATION_GLOBAL.value()))
                                        .executes(
                                            ctx -> unsetLocation(ctx, locations, messages, true)))))
                .then(
                    Commands.literal("list")
                        .executes(
                            ctx ->
                                listLocations(ctx.getSource().getSender(), messages, locations))))
        .build();
  }

  private static int reload(
      CommandSender sender,
      ConfigManager config,
      ConfigKeys keys,
      Messages messages,
      JulCobblestoneLogger log) {
    Locale locale = localeOf(sender, messages);
    final List<String> restartRequired = config.reload();
    messages.setShowPrefix(config.get(keys.messagesShowPrefix));
    log.setLevel(config.get(keys.loggingLevel));
    messages.send(sender, locale, CobblestoneMessages.RELOAD_SUCCESS);
    if (!restartRequired.isEmpty()) {
      messages.send(
          sender,
          locale,
          CobblestoneMessages.RELOAD_RESTART_REQUIRED,
          String.join(", ", restartRequired));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int showHelp(CommandSender sender, Messages messages) {
    Locale locale = localeOf(sender, messages);
    messages.send(sender, locale, CobblestoneMessages.HELP_HEADER);
    CommandHelp.line(
        sender, messages, locale, "/cobblestone reload", "command.cobblestone.help.reload");
    CommandHelp.line(
        sender,
        messages,
        locale,
        "/cobblestone cancel [id|all]",
        "command.cobblestone.help.cancel");
    CommandHelp.line(
        sender, messages, locale, "/cobblestone trips", "command.cobblestone.help.trips");
    CommandHelp.line(
        sender,
        messages,
        locale,
        "/cobblestone location set|unset|list <name> [-global]",
        "command.cobblestone.help.location");
    CommandHelp.line(
        sender, messages, locale, "/cobblestone portals clear", "command.cobblestone.help.portals");
    return Command.SINGLE_SUCCESS;
  }

  private static int listLocations(CommandSender sender, Messages messages, LocationDao locations) {
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, CobblestoneMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    var personal = locations.ownedBy(player.getUniqueId());
    var global = locations.global();
    if (personal.isEmpty() && global.isEmpty()) {
      messages.send(player, locale, CobblestoneMessages.LOCATION_LIST_NONE);
      return Command.SINGLE_SUCCESS;
    }
    messages.send(
        player, locale, CobblestoneMessages.LOCATION_LIST_HEADER, personal.size() + global.size());
    for (var location : personal) {
      messages.send(
          player,
          locale,
          CobblestoneMessages.LOCATION_LIST_ENTRY,
          location.name(),
          location(location));
    }
    for (var location : global) {
      messages.send(
          player,
          locale,
          CobblestoneMessages.LOCATION_LIST_GLOBAL,
          location.name(),
          location(location));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static String location(org.cobblestonemc.plugin.data.Location location) {
    return location.world() + " " + location.x() + ", " + location.y() + ", " + location.z();
  }

  private static CompletableFuture<Suggestions> suggestLocations(
      CommandSender sender,
      SuggestionsBuilder builder,
      LocationDao locations,
      boolean includeGlobal) {
    if (sender instanceof Player player) {
      String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
      var candidates = new ArrayList<>(locations.ownedBy(player.getUniqueId()));
      if (includeGlobal) {
        candidates.addAll(locations.global());
      }
      for (var location : candidates) {
        if (location.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
          builder.suggest(location.name());
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
      messages.send(sender, locale, CobblestoneMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    UUID uuid = player.getUniqueId();
    int cancelled = trips.trips(uuid).size();
    trips.stopAll(uuid);
    cancelled += searches.cancelAll(uuid);
    if (cancelled == 0) {
      messages.send(player, locale, CobblestoneMessages.CANCEL_NOTHING);
    } else {
      messages.send(player, locale, CobblestoneMessages.CANCEL_DONE, cancelled);
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
      messages.send(sender, locale, CobblestoneMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    if (trips.cancel(player.getUniqueId(), id)) {
      messages.send(player, locale, CobblestoneMessages.CANCEL_TRIP, id);
    } else {
      messages.send(player, locale, CobblestoneMessages.CANCEL_NOT_FOUND, id);
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int trips(
      CommandSender sender,
      Messages messages,
      TripManager<Entity, PaperTripAgent, Location> trips) {
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, CobblestoneMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    List<Trip<Entity, PaperTripAgent, Location>> active = trips.trips(player.getUniqueId());
    if (active.isEmpty()) {
      messages.send(player, locale, CobblestoneMessages.TRIPS_NONE);
      return Command.SINGLE_SUCCESS;
    }
    messages.send(player, locale, CobblestoneMessages.TRIPS_HEADER, active.size());
    for (Trip<Entity, PaperTripAgent, Location> trip : active) {
      messages.send(
          player,
          locale,
          CobblestoneMessages.TRIPS_ENTRY,
          trip.id(),
          trip.destination(),
          messages.formatDuration(locale, trip.remainingSeconds()));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int clearPortals(
      CommandSender sender, Messages messages, PortalTransitionDao portals) {
    Locale locale = localeOf(sender, messages);
    int removed = portals.clear();
    messages.send(sender, locale, CobblestoneMessages.PORTALS_CLEARED, removed);
    return Command.SINGLE_SUCCESS;
  }

  private static int setLocation(
      CommandContext<CommandSourceStack> ctx,
      LocationDao locations,
      Messages messages,
      boolean global) {
    CommandSender sender = ctx.getSource().getSender();
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, CobblestoneMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    String name = StringArgumentType.getString(ctx, "name");
    Location bukkitLocation = player.getLocation();
    String world = player.getWorld().getKey().asString();
    var location =
        global
            ? org.cobblestonemc.plugin.data.Location.global(
                name,
                world,
                bukkitLocation.getBlockX(),
                bukkitLocation.getBlockY(),
                bukkitLocation.getBlockZ())
            : org.cobblestonemc.plugin.data.Location.personal(
                player.getUniqueId(),
                name,
                world,
                bukkitLocation.getBlockX(),
                bukkitLocation.getBlockY(),
                bukkitLocation.getBlockZ());
    try {
      locations.put(location);
    } catch (DataStoreException e) {
      messages.send(sender, locale, CobblestoneMessages.LOCATION_STORE_ERROR);
      return Command.SINGLE_SUCCESS;
    }
    messages.send(sender, locale, CobblestoneMessages.LOCATION_SET, name);
    return Command.SINGLE_SUCCESS;
  }

  private static int unsetLocation(
      CommandContext<CommandSourceStack> ctx,
      LocationDao locations,
      Messages messages,
      boolean global) {
    CommandSender sender = ctx.getSource().getSender();
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, CobblestoneMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }
    String name = StringArgumentType.getString(ctx, "name");
    boolean removed;
    try {
      removed =
          locations.remove(global ? Optional.empty() : Optional.of(player.getUniqueId()), name);
    } catch (DataStoreException e) {
      messages.send(sender, locale, CobblestoneMessages.LOCATION_STORE_ERROR);
      return Command.SINGLE_SUCCESS;
    }
    messages.send(
        sender,
        locale,
        removed ? CobblestoneMessages.LOCATION_UNSET : CobblestoneMessages.LOCATION_NOT_FOUND,
        name);
    return Command.SINGLE_SUCCESS;
  }

  private static Locale localeOf(CommandSender sender, Messages messages) {
    return sender instanceof Player player ? player.locale() : messages.defaultLocale();
  }
}
