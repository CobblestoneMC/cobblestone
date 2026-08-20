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
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
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
import net.whimxiqal.odyssey.api.FailureReason;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftSearchSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.PaperNavigationServiceImpl;
import net.whimxiqal.odyssey.paper.plugin.api.DestinationService;
import net.whimxiqal.odyssey.paper.plugin.api.NavigatorFactory;
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
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * The {@code /navigate} (alias {@code /nav}) user command: parse flags, resolve the destination
 * through the registered providers, run the search, and — on success — create the chosen navigator
 * and start a {@link net.whimxiqal.odyssey.plugin.trip.Trip}. All the non-Brigadier logic lives in
 * platform-neutral plugin-core helpers (flag parsing, destination resolution).
 */
final class NavigateCommand {

  static final String PERMISSION_NAVIGATE = "odyssey.navigate";
  private static final String PERMISSION_NAVIGATOR_PREFIX = "odyssey.navigator.";
  // Above this many destination matches, tab-completion offers nothing — the player must type more
  // to narrow down. Keeps a huge level the player asked for by name from flooding completion.
  private static final int MAX_DESTINATION_SUGGESTIONS = DestinationResolver.PROMOTION_LIMIT;
  // A tiny, greedy search for the off-trail "guide" path: bounded and heavily weighted so it's
  // cheap.
  private static final SearchSettings GUIDE_SETTINGS =
      SearchSettings.builder()
          .maxCellsVisited(4000)
          .maxWallClockMillis(1500L)
          .heuristicWeight(2.0)
          .build();

  private NavigateCommand() {}

  static LiteralCommandNode<CommandSourceStack> build(
      PaperNavigationServiceImpl platformApi,
      PaperTripServiceImpl tripService,
      PaperIntegrationRegistry integrations,
      SearchRegistry<Location> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      OdysseyLogger log,
      Messages messages) {
    return Commands.literal("navigate")
        .requires(source -> source.getSender().hasPermission(PERMISSION_NAVIGATE))
        .executes(ctx -> navHelp(ctx.getSource().getSender(), messages))
        .then(
            Commands.literal("help")
                .executes(ctx -> navHelp(ctx.getSource().getSender(), messages)))
        .then(Commands.literal("?").executes(ctx -> navHelp(ctx.getSource().getSender(), messages)))
        .then(
            Commands.argument("args", StringArgumentType.greedyString())
                .suggests(
                    (suggestCtx, suggestBuilder) ->
                        suggest(suggestCtx, suggestBuilder, integrations))
                .executes(
                    ctx ->
                        run(
                            ctx,
                            platformApi,
                            tripService,
                            integrations,
                            searches,
                            gate,
                            searchSettings,
                            log,
                            messages)))
        .build();
  }

  private static int navHelp(CommandSender sender, Messages messages) {
    Locale locale = localeOf(sender, messages);
    messages.send(sender, locale, OdysseyMessages.NAVIGATE_HELP_HEADER);
    CommandHelp.line(
        sender,
        messages,
        locale,
        "/navigate <destination...>",
        "command.navigate.help.destination");
    CommandHelp.line(
        sender, messages, locale, "-navigator <id>", "command.navigate.help.navigator");
    CommandHelp.line(sender, messages, locale, "-no-mode <mode>", "command.navigate.help.no_mode");
    CommandHelp.line(
        sender,
        messages,
        locale,
        "-no-world <world> / -no-dimension <dim>",
        "command.navigate.help.no_world");
    CommandHelp.line(sender, messages, locale, "-live", "command.navigate.help.live");
    return Command.SINGLE_SUCCESS;
  }

  private static int run(
      CommandContext<CommandSourceStack> ctx,
      PaperNavigationServiceImpl platformApi,
      PaperTripServiceImpl tripService,
      PaperIntegrationRegistry integrations,
      SearchRegistry<Location> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      OdysseyLogger log,
      Messages messages) {
    CommandSender sender = ctx.getSource().getSender();
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, OdysseyMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }

    FlagParser.Result parseResult =
        FlagParser.parse(tokenize(StringArgumentType.getString(ctx, "args")));
    if (parseResult instanceof FlagParser.Invalid invalid) {
      sendFlagError(player, locale, messages, invalid);
      return Command.SINGLE_SUCCESS;
    }
    FlagParser.Parsed parsed = (FlagParser.Parsed) parseResult;
    NavigationFlags flags = parsed.flags();

    if (!flags.navigator().equals(FlagParser.DEFAULT_NAVIGATOR)
        && !player.hasPermission(PERMISSION_NAVIGATOR_PREFIX + flags.navigator())) {
      messages.send(player, locale, OdysseyMessages.NO_PERMISSION);
      return Command.SINGLE_SUCCESS;
    }
    NavigatorFactory factory = navigatorFactory(integrations, flags.navigator());
    if (factory == null) {
      messages.send(player, locale, OdysseyMessages.NAVIGATE_UNKNOWN_NAVIGATOR, flags.navigator());
      return Command.SINGLE_SUCCESS;
    }

    DestinationResolver.Resolution<World, Vector3i> resolution =
        DestinationResolver.resolve(
            destinationRoots(integrations, player),
            parsed.destination(),
            player::hasPermission,
            canNavigate(player),
            log);
    if (resolution
        instanceof DestinationResolver.Ambiguous<World, Vector3i>(List<List<String>> addresses)) {
      messages.send(
          player,
          locale,
          OdysseyMessages.NAVIGATE_DESTINATION_AMBIGUOUS,
          formatAddresses(addresses));
      return Command.SINGLE_SUCCESS;
    }
    if (!(resolution
        instanceof
        DestinationResolver.Resolved<World, Vector3i>(
            MinecraftDestination<World, Vector3i> destination,
            List<String> address))) {
      messages.send(
          player,
          locale,
          OdysseyMessages.NAVIGATE_DESTINATION_NOT_FOUND,
          String.join(" ", parsed.destination()));
      return Command.SINGLE_SUCCESS;
    }

    String destinationLabel = String.join(" ", address);
    boolean live =
        switch (flags.liveness()) {
          case LIVE -> true;
          case NO_LIVE -> false;
          case DEFAULT -> destination.isMobile();
        };
    startSearch(
        player,
        locale,
        destinationLabel,
        destination,
        flags,
        live,
        platformApi,
        tripService,
        searches,
        gate,
        searchSettings,
        log,
        messages);
    return Command.SINGLE_SUCCESS;
  }

  private static void startSearch(
      Player player,
      Locale locale,
      String destinationLabel,
      MinecraftDestination<World, Vector3i> destination,
      NavigationFlags flags,
      boolean live,
      PaperNavigationServiceImpl platformApi,
      PaperTripServiceImpl tripService,
      SearchRegistry<Location> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      OdysseyLogger log,
      Messages messages) {
    UUID uuid = player.getUniqueId();
    final long startNanos = System.nanoTime();
    gate.beginForced(uuid); // a manual search always runs and counts toward the budget
    SearchHandle<Location, MinecraftStepPayload> handle =
        platformApi.navigatePlayerToDestination(
            player,
            destination.destination(),
            new MinecraftSearchSettings(
                searchSettings.get(),
                flags.excludedModes(),
                flags.excludedWorlds(),
                flags.excludedDimensions()));
    searches.track(uuid, handle);
    messages.send(player, locale, OdysseyMessages.NAVIGATE_SEARCHING);

    handle
        .future()
        .whenComplete(
            (result, error) -> {
              searches.untrack(uuid, handle);
              gate.end(uuid);
              long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
              if (error != null) {
                log.debug(
                    "navigate {} -> {}: errored in {}ms",
                    player.getName(),
                    destinationLabel,
                    elapsedMillis);
                messages.send(player, locale, OdysseyMessages.NAVIGATE_ERROR);
                return;
              }
              switch (result) {
                case NavigationResult.Error<Location, MinecraftStepPayload> v -> {
                  log.error(
                      "navigate {} -> {}: ERROR in {}ms",
                      v.throwable(),
                      player.getName(),
                      destinationLabel,
                      elapsedMillis);
                  messages.send(player, locale, OdysseyMessages.NAVIGATE_ERROR);
                }
                case NavigationResult.Failure<Location, MinecraftStepPayload> v -> {
                  log.debug(
                      "navigate {} -> {}: {} in {}ms",
                      player.getName(),
                      destinationLabel,
                      v.reason(),
                      elapsedMillis);
                  sendFailure(player, locale, messages, v.reason());
                }
                case NavigationResult.Success<Location, MinecraftStepPayload> v -> {
                  Path<Location, MinecraftStepPayload> path =
                      ((NavigationResult.Success<Location, MinecraftStepPayload>) result).path();
                  log.debug(
                      "navigate {} -> {}: {} steps, {}s duration, found in {}ms",
                      player.getName(),
                      destinationLabel,
                      path.steps().size(),
                      path.duration(),
                      elapsedMillis);
                  // Hand the found route to the shared trip service — the same code path
                  // integrations
                  // use. The command only carries the chosen navigator id (appearance comes from
                  // config);
                  // the flag-scoped re-search/guide closures ride along for stray recalculation.
                  NavigatorSettings settings = NavigatorSettings.builder(flags.navigator()).build();
                  tripService
                      .start(
                          player,
                          path,
                          destinationLabel,
                          settings,
                          liveSearch(
                              player,
                              destination,
                              flags,
                              platformApi,
                              searches,
                              gate,
                              searchSettings),
                          guideSearch(player, flags, platformApi),
                          live)
                      .whenComplete(
                          (outcome, tripError) -> {
                            if (tripError != null || outcome instanceof TripOutcome.Failed) {
                              messages.send(player, locale, OdysseyMessages.NAVIGATE_ERROR);
                            } else if (outcome instanceof TripOutcome.TripLimitReached) {
                              messages.send(player, locale, OdysseyMessages.NAVIGATE_TRIP_LIMIT);
                            } else {
                              // "Route found" carries a hover with the search time and the trip
                              // length.
                              Component started =
                                  messages.render(locale, OdysseyMessages.NAVIGATE_STARTED);
                              Component stats =
                                  messages.render(
                                      locale,
                                      OdysseyMessages.NAVIGATE_STATS,
                                      elapsedMillis,
                                      messages.formatDuration(locale, path.duration()));
                              player.sendMessage(started.hoverEvent(HoverEvent.showText(stats)));
                            }
                          });
                }
              }
            });
  }

  /** Builds the short-range guide search (player -> current step) for off-trail drift. */
  private static GuideSearch<Location> guideSearch(
      Player player, NavigationFlags flags, PaperNavigationServiceImpl platformApi) {
    return target -> {
      if (!player.isOnline()) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      return platformApi
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

  /**
   * Builds the re-search behavior for a {@code -live} trip; yields to the per-player search budget.
   */
  private static LiveSearch<Location> liveSearch(
      Player player,
      MinecraftDestination<World, Vector3i> destination,
      NavigationFlags flags,
      PaperNavigationServiceImpl platformApi,
      SearchRegistry<Location> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings) {
    UUID uuid = player.getUniqueId();
    return () -> {
      if (!player.isOnline() || !gate.tryBegin(uuid)) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      SearchHandle<Location, MinecraftStepPayload> handle =
          platformApi.navigatePlayerToDestination(
              player,
              destination.destination(),
              new MinecraftSearchSettings(
                  searchSettings.get(),
                  flags.excludedModes(),
                  flags.excludedWorlds(),
                  flags.excludedDimensions()));
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

  private static CompletableFuture<Suggestions> suggest(
      CommandContext<CommandSourceStack> ctx,
      SuggestionsBuilder builder,
      PaperIntegrationRegistry integrations) {
    if (!(ctx.getSource().getSender() instanceof Player player)) {
      return builder.buildFuture();
    }
    String remaining = builder.getRemaining();
    List<String> tokens = FlagParser.tokenizeKeepingTrailing(remaining);
    String last = tokens.isEmpty() ? "" : tokens.getLast();
    String previous =
        tokens.size() >= 2 ? tokens.get(tokens.size() - 2).toLowerCase(Locale.ROOT) : "";
    SuggestionsBuilder offset =
        builder.createOffset(builder.getStart() + remaining.length() - last.length());

    if (previous.equals("-navigator")) {
      navigatorIds(integrations).stream()
          .filter(id -> id.startsWith(last))
          .forEach(offset::suggest);
    } else if (previous.equals("-no-mode")) {
      FlagParser.modeWords().stream()
          .filter(word -> word.startsWith(last))
          .sorted()
          .forEach(offset::suggest);
    } else if (last.startsWith("-")) {
      flagNames().stream().filter(flag -> flag.startsWith(last)).forEach(offset::suggest);
    } else {
      List<String> suggestions =
          DestinationResolver.suggest(
              destinationRoots(integrations, player),
              FlagParser.destinationTokens(tokens),
              player::hasPermission,
              canNavigate(player));
      // Only offer completions once the candidate set is small; otherwise the player narrows first.
      if (suggestions.size() <= MAX_DESTINATION_SUGGESTIONS) {
        suggestions.forEach(offset::suggest);
      }
    }
    return offset.buildFuture();
  }

  /**
   * The Odyssey navigation-gate check for a player: default-allow, so a destination is offered
   * unless its {@code odyssey.navigate.<address>} node is explicitly denied.
   */
  private static Predicate<List<String>> canNavigate(Player player) {
    return address ->
        NavigationPermissions.allowed(address, player::isPermissionSet, player::hasPermission);
  }

  private static List<PlatformDestinationTree<World, Vector3i>> destinationRoots(
      PaperIntegrationRegistry integrations, Player player) {
    List<PlatformDestinationTree<World, Vector3i>> roots = new ArrayList<>();
    for (DestinationService provider : integrations.destinationProviders()) {
      roots.addAll(provider.provide(player));
    }
    return roots;
  }

  private static NavigatorFactory navigatorFactory(
      PaperIntegrationRegistry integrations, String id) {
    return integrations.navigator(id);
  }

  private static List<String> navigatorIds(PaperIntegrationRegistry integrations) {
    return integrations.navigatorIds();
  }

  private static List<String> flagNames() {
    List<String> flags =
        new ArrayList<>(List.of("-navigator", "-no-world", "-no-dimension", "-no-mode", "-live"));
    FlagParser.modeWords().stream().sorted().forEach(word -> flags.add("-no-" + word));
    return flags;
  }

  private static void sendFailure(
      Player player, Locale locale, Messages messages, FailureReason reason) {
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
      Player player, Locale locale, Messages messages, FlagParser.Invalid invalid) {
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

  private static Locale localeOf(CommandSender sender, Messages messages) {
    return sender instanceof Player player ? player.locale() : messages.defaultLocale();
  }

  private static Optional<Path<Location, MinecraftStepPayload>> convertUpdateResult(
      NavigationResult<Location, MinecraftStepPayload> result, Throwable error) {
    if (error == null
        && result
            instanceof
            NavigationResult.Success<Location, MinecraftStepPayload>(
                Path<Location, MinecraftStepPayload> path)
        && !path.steps().isEmpty()) {
      return Optional.of(path);
    }
    return Optional.<Path<Location, MinecraftStepPayload>>empty();
  }
}
