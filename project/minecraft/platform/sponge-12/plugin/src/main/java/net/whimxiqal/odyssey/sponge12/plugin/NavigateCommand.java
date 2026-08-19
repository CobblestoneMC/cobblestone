/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.whimxiqal.odyssey.OdysseyLogger;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.api.FailureReason;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftSearchSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import net.whimxiqal.odyssey.plugin.api.PlatformDestinationTree;
import net.whimxiqal.odyssey.plugin.api.TripOutcome;
import net.whimxiqal.odyssey.plugin.command.FlagParser;
import net.whimxiqal.odyssey.plugin.command.NavigationFlags;
import net.whimxiqal.odyssey.plugin.destination.DestinationResolver;
import net.whimxiqal.odyssey.plugin.destination.NavigationPermissions;
import net.whimxiqal.odyssey.plugin.message.Messages;
import net.whimxiqal.odyssey.plugin.message.OdysseyMessages;
import net.whimxiqal.odyssey.plugin.search.SearchGate;
import net.whimxiqal.odyssey.plugin.search.SearchRegistry;
import net.whimxiqal.odyssey.plugin.trip.GuideSearch;
import net.whimxiqal.odyssey.plugin.trip.LiveSearch;
import net.whimxiqal.odyssey.sponge12.SpongeNavigationServiceImpl;
import net.whimxiqal.odyssey.sponge12.api.SingleCellWorldRegion;
import net.whimxiqal.odyssey.sponge12.plugin.api.DestinationService;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCompletion;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.util.Tristate;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * The {@code /navigate} (alias {@code /nav}) command: parse flags, resolve the destination through
 * the registered providers, run the search, and — on success — create the chosen navigator and
 * start a guided trip. All the non-Sponge logic lives in platform-neutral plugin-core helpers (flag
 * parsing, destination resolution). {@code <x> <y> <z>} routes to raw coordinates in the current
 * world.
 */
final class NavigateCommand {

  static final String PERMISSION_NAVIGATE = "odyssey.navigate";
  private static final String PERMISSION_NAVIGATOR_PREFIX = "odyssey.navigator.";
  // A tiny, greedy search for the off-trail "guide" path: bounded and heavily weighted so it's
  // cheap.
  private static final SearchSettings GUIDE_SETTINGS =
      SearchSettings.builder()
          .maxCellsVisited(4000)
          .maxWallClockMillis(1500L)
          .heuristicWeight(2.0)
          .build();

  private NavigateCommand() {}

  static Command.Parameterized build(
      SpongeNavigationServiceImpl navigationService,
      SpongeTripServiceImpl tripService,
      SpongeIntegrationRegistry integrations,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      OdysseyLogger log,
      Messages messages) {
    Parameter.Value<String> args =
        Parameter.remainingJoinedStrings()
            .key("args")
            .optional()
            .completer((context, input) -> complete(context, input, integrations))
            .build();
    return Command.builder()
        .shortDescription(Component.text("Navigate to a location or destination"))
        .permission(PERMISSION_NAVIGATE)
        .addParameter(args)
        .executor(
            context -> {
              Optional<ServerPlayer> player = context.cause().first(ServerPlayer.class);
              if (player.isEmpty()) {
                context.sendMessage(
                    messages.render(messages.defaultLocale(), OdysseyMessages.PLAYERS_ONLY));
                return CommandResult.success();
              }
              String raw = context.one(args).orElse("");
              run(
                  player.get(),
                  raw,
                  navigationService,
                  tripService,
                  integrations,
                  searches,
                  gate,
                  searchSettings,
                  log,
                  messages);
              return CommandResult.success();
            })
        .build();
  }

  private static void run(
      ServerPlayer player,
      String raw,
      SpongeNavigationServiceImpl navigationService,
      SpongeTripServiceImpl tripService,
      SpongeIntegrationRegistry integrations,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      OdysseyLogger log,
      Messages messages) {
    Locale locale = player.locale();
    List<String> tokens = tokenize(raw);
    if (tokens.isEmpty()) {
      navHelp(player, locale, messages, integrations);
      return;
    }

    FlagParser.Result parseResult = FlagParser.parse(tokens);
    if (parseResult instanceof FlagParser.Invalid invalid) {
      sendFlagError(player, locale, messages, invalid);
      return;
    }
    FlagParser.Parsed parsed = (FlagParser.Parsed) parseResult;
    NavigationFlags flags = parsed.flags();

    if (!flags.navigator().equals(FlagParser.DEFAULT_NAVIGATOR)
        && !player.hasPermission(PERMISSION_NAVIGATOR_PREFIX + flags.navigator())) {
      messages.send(player, locale, OdysseyMessages.NO_PERMISSION);
      return;
    }
    if (integrations.navigator(flags.navigator()) == null) {
      messages.send(player, locale, OdysseyMessages.NAVIGATE_UNKNOWN_NAVIGATOR, flags.navigator());
      return;
    }

    // A bare "x y z" (no flags) routes to coordinates in the current world.
    double[] coordinates =
        parsed.destination().isEmpty() ? null : asCoordinates(parsed.destination());
    if (coordinates != null) {
      startSearch(
          player,
          locale,
          coordinateLabel(coordinates),
          coordinateDestination(player, coordinates),
          flags,
          false,
          navigationService,
          tripService,
          integrations,
          searches,
          gate,
          searchSettings,
          log,
          messages);
      return;
    }

    DestinationResolver.Resolution<ServerWorld, Vector3i> resolution =
        DestinationResolver.resolve(
            destinationRoots(integrations, player),
            parsed.destination(),
            player::hasPermission,
            canNavigate(player));
    if (resolution
        instanceof DestinationResolver.Ambiguous<ServerWorld, Vector3i>(List<List<String>> addrs)) {
      messages.send(
          player, locale, OdysseyMessages.NAVIGATE_DESTINATION_AMBIGUOUS, formatAddresses(addrs));
      return;
    }
    if (!(resolution
        instanceof
        DestinationResolver.Resolved<ServerWorld, Vector3i>(
            MinecraftDestination<ServerWorld, Vector3i> destination,
            List<String> address))) {
      messages.send(
          player,
          locale,
          OdysseyMessages.NAVIGATE_DESTINATION_NOT_FOUND,
          String.join(" ", parsed.destination()));
      return;
    }

    boolean live =
        switch (flags.liveness()) {
          case LIVE -> true;
          case NO_LIVE -> false;
          case DEFAULT -> destination.isMobile();
        };
    startSearch(
        player,
        locale,
        String.join(" ", address),
        destination.destination(),
        flags,
        live,
        navigationService,
        tripService,
        integrations,
        searches,
        gate,
        searchSettings,
        log,
        messages);
  }

  private static void startSearch(
      ServerPlayer player,
      Locale locale,
      String label,
      Destination<WorldRegion<ServerWorld, Vector3i>> destination,
      NavigationFlags flags,
      boolean live,
      SpongeNavigationServiceImpl navigationService,
      SpongeTripServiceImpl tripService,
      SpongeIntegrationRegistry integrations,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      OdysseyLogger log,
      Messages messages) {
    UUID uuid = player.uniqueId();
    long startNanos = System.nanoTime();
    gate.beginForced(uuid);
    SearchHandle<ServerLocation, MinecraftStepPayload> handle =
        navigationService.navigatePlayerToDestination(
            player, destination, searchSettingsFor(searchSettings, flags));
    searches.track(uuid, handle);
    messages.send(player, locale, OdysseyMessages.NAVIGATE_SEARCHING);

    handle
        .future()
        .whenComplete(
            (result, error) -> {
              searches.untrack(uuid, handle);
              gate.end(uuid);
              long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
              if (error != null || result instanceof NavigationResult.Error) {
                log.debug(
                    "navigate {} -> {}: errored in {}ms", player.name(), label, elapsedMillis);
                messages.send(player, locale, OdysseyMessages.NAVIGATE_ERROR);
                return;
              }
              if (result
                  instanceof NavigationResult.Failure<ServerLocation, MinecraftStepPayload> f) {
                sendFailure(player, locale, messages, f.reason());
                return;
              }
              if (result
                  instanceof
                  NavigationResult.Success<ServerLocation, MinecraftStepPayload>(
                      Path<ServerLocation, MinecraftStepPayload> path)) {
                NavigatorSettings settings = NavigatorSettings.builder(flags.navigator()).build();
                tripService
                    .start(
                        player,
                        path,
                        label,
                        settings,
                        liveSearch(
                            player,
                            destination,
                            flags,
                            navigationService,
                            searches,
                            gate,
                            searchSettings),
                        guideSearch(player, flags, navigationService),
                        live)
                    .whenComplete(
                        (outcome, tripError) ->
                            reportTrip(
                                player, locale, messages, path, elapsedMillis, outcome, tripError));
              }
            });
  }

  private static void reportTrip(
      ServerPlayer player,
      Locale locale,
      Messages messages,
      Path<ServerLocation, MinecraftStepPayload> path,
      long elapsedMillis,
      TripOutcome outcome,
      Throwable error) {
    if (error != null || outcome instanceof TripOutcome.Failed) {
      messages.send(player, locale, OdysseyMessages.NAVIGATE_ERROR);
    } else if (outcome instanceof TripOutcome.TripLimitReached) {
      messages.send(player, locale, OdysseyMessages.NAVIGATE_TRIP_LIMIT);
    } else {
      Component started = messages.render(locale, OdysseyMessages.NAVIGATE_STARTED);
      Component stats =
          messages.render(
              locale,
              OdysseyMessages.NAVIGATE_STATS,
              elapsedMillis,
              messages.formatDuration(locale, path.duration()));
      player.sendMessage(started.hoverEvent(HoverEvent.showText(stats)));
    }
  }

  private static LiveSearch<ServerLocation> liveSearch(
      ServerPlayer player,
      Destination<WorldRegion<ServerWorld, Vector3i>> destination,
      NavigationFlags flags,
      SpongeNavigationServiceImpl navigationService,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings) {
    UUID uuid = player.uniqueId();
    return () -> {
      if (!player.isOnline() || !gate.tryBegin(uuid)) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      SearchHandle<ServerLocation, MinecraftStepPayload> handle =
          navigationService.navigatePlayerToDestination(
              player, destination, searchSettingsFor(searchSettings, flags));
      searches.track(uuid, handle);
      return handle
          .future()
          .handle(
              (result, error) -> {
                searches.untrack(uuid, handle);
                gate.end(uuid);
                return convertUpdateResult(result, error);
              });
    };
  }

  private static GuideSearch<ServerLocation> guideSearch(
      ServerPlayer player, NavigationFlags flags, SpongeNavigationServiceImpl navigationService) {
    return target -> {
      if (!player.isOnline()) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      return navigationService
          .navigatePlayer(
              player,
              target,
              new MinecraftSearchSettings(
                  GUIDE_SETTINGS,
                  flags.excludedModes(),
                  flags.excludedWorlds(),
                  flags.excludedDimensions()))
          .future()
          .handle(NavigateCommand::convertUpdateResult);
    };
  }

  private static MinecraftSearchSettings searchSettingsFor(
      Supplier<SearchSettings> searchSettings, NavigationFlags flags) {
    return new MinecraftSearchSettings(
        searchSettings.get(),
        flags.excludedModes(),
        flags.excludedWorlds(),
        flags.excludedDimensions());
  }

  private static void navHelp(
      ServerPlayer player,
      Locale locale,
      Messages messages,
      SpongeIntegrationRegistry integrations) {
    messages.send(player, locale, OdysseyMessages.NAVIGATE_HELP_HEADER);
    SpongeCommandHelp.line(
        player, messages, locale, "/navigate <destination…>", "command.navigate.help.destination");
    SpongeCommandHelp.line(
        player, messages, locale, "/navigate <x> <y> <z>", "command.navigate.help.destination");
    SpongeCommandHelp.line(
        player, messages, locale, "-navigator <id>", "command.navigate.help.navigator");
    SpongeCommandHelp.line(
        player, messages, locale, "-no-mode <mode>", "command.navigate.help.no_mode");
    SpongeCommandHelp.line(
        player,
        messages,
        locale,
        "-no-world <world> / -no-dimension <dim>",
        "command.navigate.help.no_world");
    SpongeCommandHelp.line(player, messages, locale, "-live", "command.navigate.help.live");
  }

  private static Predicate<List<String>> canNavigate(ServerPlayer player) {
    return address ->
        NavigationPermissions.allowed(
            address,
            node -> player.permissionValue(node) != Tristate.UNDEFINED,
            player::hasPermission);
  }

  private static List<PlatformDestinationTree<ServerWorld, Vector3i>> destinationRoots(
      SpongeIntegrationRegistry integrations, ServerPlayer player) {
    List<PlatformDestinationTree<ServerWorld, Vector3i>> roots = new ArrayList<>();
    for (DestinationService provider : integrations.destinationProviders()) {
      roots.addAll(provider.provide(player));
    }
    return roots;
  }

  private static List<CommandCompletion> complete(
      org.spongepowered.api.command.parameter.CommandContext context,
      String input,
      SpongeIntegrationRegistry integrations) {
    Optional<ServerPlayer> player = context.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      return List.of();
    }
    List<String> tokens = tokenize(input);
    String last = input.endsWith(" ") || tokens.isEmpty() ? "" : tokens.getLast();
    List<String> priorDestination =
        tokens.isEmpty() ? tokens : tokens.subList(0, tokens.size() - 1);
    List<String> suggestions =
        DestinationResolver.suggest(
            destinationRoots(integrations, player.get()),
            input.endsWith(" ") ? tokens : priorDestination,
            player.get()::hasPermission,
            canNavigate(player.get()));
    List<CommandCompletion> completions = new ArrayList<>();
    for (String suggestion : suggestions) {
      if (suggestion.toLowerCase(Locale.ROOT).startsWith(last.toLowerCase(Locale.ROOT))) {
        completions.add(CommandCompletion.of(suggestion));
      }
    }
    return completions;
  }

  private static ServerLocation coordinateDestination(ServerPlayer player, double[] xyz) {
    return ServerLocation.of(player.world(), xyz[0], xyz[1], xyz[2]);
  }

  private static MinecraftDestination<ServerWorld, Vector3i> destinationOf(
      ServerLocation location) {
    return net.whimxiqal.odyssey.sponge12.plugin.api.Destination.at(location, Component.empty());
  }

  private static void startSearch(
      ServerPlayer player,
      Locale locale,
      String label,
      ServerLocation location,
      NavigationFlags flags,
      boolean live,
      SpongeNavigationServiceImpl navigationService,
      SpongeTripServiceImpl tripService,
      SpongeIntegrationRegistry integrations,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      OdysseyLogger log,
      Messages messages) {
    startSearch(
        player,
        locale,
        label,
        () -> List.of(SingleCellWorldRegion.of(location)),
        flags,
        live,
        navigationService,
        tripService,
        integrations,
        searches,
        gate,
        searchSettings,
        log,
        messages);
  }

  private static void sendFailure(
      ServerPlayer player, Locale locale, Messages messages, FailureReason reason) {
    switch (reason) {
      case NO_ROUTE, DESTINATION_UNREACHABLE ->
          messages.send(player, locale, OdysseyMessages.NAVIGATE_NO_ROUTE);
      case LIMIT_EXCEEDED -> messages.send(player, locale, OdysseyMessages.NAVIGATE_LIMIT_EXCEEDED);
      case TIMED_OUT -> messages.send(player, locale, OdysseyMessages.NAVIGATE_TIMED_OUT);
      case CANCELLED -> {
        /* silent: the player asked for it */
      }
      default -> messages.send(player, locale, OdysseyMessages.NAVIGATE_ERROR);
    }
  }

  private static void sendFlagError(
      ServerPlayer player, Locale locale, Messages messages, FlagParser.Invalid invalid) {
    switch (invalid.error()) {
      case UNKNOWN_FLAG ->
          messages.send(player, locale, OdysseyMessages.NAVIGATE_FLAG_UNKNOWN, invalid.token());
      case MISSING_VALUE ->
          messages.send(
              player, locale, OdysseyMessages.NAVIGATE_FLAG_MISSING_VALUE, invalid.token());
      case UNKNOWN_MODE ->
          messages.send(
              player, locale, OdysseyMessages.NAVIGATE_FLAG_UNKNOWN_MODE, invalid.token());
      default -> messages.send(player, locale, OdysseyMessages.NAVIGATE_ERROR);
    }
  }

  private static double[] asCoordinates(List<String> tokens) {
    if (tokens.size() != 3) {
      return null;
    }
    try {
      return new double[] {
        Double.parseDouble(tokens.get(0)),
        Double.parseDouble(tokens.get(1)),
        Double.parseDouble(tokens.get(2))
      };
    } catch (NumberFormatException notCoordinates) {
      return null;
    }
  }

  private static String coordinateLabel(double[] xyz) {
    return (int) xyz[0] + ", " + (int) xyz[1] + ", " + (int) xyz[2];
  }

  private static Optional<Path<ServerLocation, MinecraftStepPayload>> convertUpdateResult(
      NavigationResult<ServerLocation, MinecraftStepPayload> result, Throwable error) {
    if (error == null
        && result
            instanceof
            NavigationResult.Success<ServerLocation, MinecraftStepPayload>(
                Path<ServerLocation, MinecraftStepPayload> path)
        && !path.steps().isEmpty()) {
      return Optional.of(path);
    }
    return Optional.empty();
  }

  private static String formatAddresses(List<List<String>> addresses) {
    List<String> joined = new ArrayList<>();
    for (List<String> address : addresses) {
      joined.add(String.join(" ", address));
    }
    return String.join(", ", joined);
  }

  private static List<String> tokenize(String raw) {
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? List.of() : Arrays.asList(trimmed.split("\\s+"));
  }
}
