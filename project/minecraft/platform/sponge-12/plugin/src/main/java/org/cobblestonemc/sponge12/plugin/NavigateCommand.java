/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.api.FailureReason;
import org.cobblestonemc.api.NavigationResult;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.api.SearchHandle;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.api.MinecraftSearchSettings;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.plugin.Permissions;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.cobblestonemc.plugin.api.TripOutcome;
import org.cobblestonemc.plugin.command.FlagParser;
import org.cobblestonemc.plugin.command.NavigationFlags;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.NavigationPermissions;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.search.SearchGate;
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.trip.GuideSearch;
import org.cobblestonemc.plugin.trip.LiveSearch;
import org.cobblestonemc.sponge12.SpongeNavigationServiceImpl;
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
 * parsing, destination resolution).
 */
final class NavigateCommand {

  // How many words /navigate accepts. Each word is its own parameter (see #build), so this is a
  // hard cap: a destination address plus a flag or two fits comfortably.
  private static final int MAX_WORDS = 8;
  // One key per word position. Distinct keys, all read back in order, so every word lands in the
  // same parse context — which is what lets tab-completion see the words typed before the cursor.
  private static final List<Parameter.Key<String>> WORD_KEYS = wordKeys();
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

  static Command.Parameterized build(
      SpongeNavigationServiceImpl navigationService,
      SpongeTripServiceImpl tripService,
      SpongeIntegrationRegistry integrations,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      CobblestoneLogger log,
      Messages messages) {
    // A chain of single-word parameters, NOT one greedy parameter. Sponge translates a greedy or
    // repeating parameter into nodes that redirect to themselves, and each redirect starts a fresh
    // parse context — so a completer only ever sees the first word, and cannot tell where in the
    // destination tree the player is. One node per word keeps the whole line in one context.
    Command.Builder command =
        Command.builder()
            .shortDescription(Component.text("Navigate to a location or destination"))
            .permission(Permissions.NAVIGATE.value());
    for (Parameter.Key<String> key : WORD_KEYS) {
      command.addParameter(
          Parameter.string()
              .key(key)
              .optional()
              .completer((context, input) -> complete(context, input, integrations))
              .build());
    }
    return command
        .executor(
            context -> {
              Optional<ServerPlayer> player = context.cause().first(ServerPlayer.class);
              if (player.isEmpty()) {
                context.sendMessage(
                    messages.render(messages.defaultLocale(), CobblestoneMessages.PLAYERS_ONLY));
                return CommandResult.success();
              }
              run(
                  player.get(),
                  typedWords(context),
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

  /** The words the player has typed, in order, gathered from the per-word parameters. */
  private static List<String> typedWords(
      org.spongepowered.api.command.parameter.CommandContext context) {
    List<String> words = new ArrayList<>();
    for (Parameter.Key<String> key : WORD_KEYS) {
      context.one(key).ifPresent(words::add);
    }
    return words;
  }

  private static List<Parameter.Key<String>> wordKeys() {
    List<Parameter.Key<String>> keys = new ArrayList<>();
    for (int i = 1; i <= MAX_WORDS; i++) {
      keys.add(Parameter.key("arg" + i, String.class));
    }
    return List.copyOf(keys);
  }

  private static void run(
      ServerPlayer player,
      List<String> tokens,
      SpongeNavigationServiceImpl navigationService,
      SpongeTripServiceImpl tripService,
      SpongeIntegrationRegistry integrations,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      CobblestoneLogger log,
      Messages messages) {
    Locale locale = player.locale();
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
        && !mayUseNavigator(player, flags.navigator())) {
      messages.send(player, locale, CobblestoneMessages.NO_PERMISSION);
      return;
    }
    if (integrations.navigator(flags.navigator()) == null) {
      messages.send(
          player, locale, CobblestoneMessages.NAVIGATE_UNKNOWN_NAVIGATOR, flags.navigator());
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
          player,
          locale,
          CobblestoneMessages.NAVIGATE_DESTINATION_AMBIGUOUS,
          formatAddresses(addrs));
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
          CobblestoneMessages.NAVIGATE_DESTINATION_NOT_FOUND,
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
      CobblestoneLogger log,
      Messages messages) {
    UUID uuid = player.uniqueId();
    long startNanos = System.nanoTime();
    gate.beginForced(uuid);
    SearchHandle<ServerLocation, MinecraftStepPayload> handle =
        navigationService.navigatePlayerToDestination(
            player, destination, searchSettingsFor(searchSettings, flags));
    searches.track(uuid, handle);
    messages.send(player, locale, CobblestoneMessages.NAVIGATE_SEARCHING);

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
                messages.send(player, locale, CobblestoneMessages.NAVIGATE_ERROR);
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
      messages.send(player, locale, CobblestoneMessages.NAVIGATE_ERROR);
    } else if (outcome instanceof TripOutcome.TripLimitReached) {
      messages.send(player, locale, CobblestoneMessages.NAVIGATE_TRIP_LIMIT);
    } else {
      Component started = messages.render(locale, CobblestoneMessages.NAVIGATE_STARTED);
      Component stats =
          messages.render(
              locale,
              CobblestoneMessages.NAVIGATE_STATS,
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
    messages.send(player, locale, CobblestoneMessages.NAVIGATE_HELP_HEADER);
    SpongeCommandHelp.line(
        player, messages, locale, "/navigate <destination…>", "command.navigate.help.destination");
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

  /**
   * Whether the player may use a non-default navigator. Default-allow, matching the per-destination
   * gate: {@code cobblestone.navigator.<id>} cannot be declared up front (a navigator's id comes
   * from whichever plugin registered it), so an undefined node must not read as a denial.
   */
  private static boolean mayUseNavigator(ServerPlayer player, String navigator) {
    return player.permissionValue(Permissions.NAVIGATOR.value() + "." + navigator)
        != Tristate.FALSE;
  }

  /**
   * The destination forest for this player: one root per registered provider, keyed by the plugin
   * that registered it. The owner is the root key rather than something the provider picks, so two
   * plugins that both offer a {@code warp} level stay tellable apart.
   */
  private static Map<String, PlatformDestinationTree<ServerWorld, Vector3i>> destinationRoots(
      SpongeIntegrationRegistry integrations, ServerPlayer player) {
    Map<String, PlatformDestinationTree<ServerWorld, Vector3i>> roots = new TreeMap<>();
    for (var provider : integrations.destinationProviders().entrySet()) {
      PlatformDestinationTree<ServerWorld, Vector3i> root = provider.getValue().provide(player);
      // An empty root would be offered as a token that leads nowhere — drop it instead.
      if (root == null || (root.subTrees().isEmpty() && root.destinations().isEmpty())) {
        continue;
      }
      roots.put(provider.getKey(), root);
    }
    return roots;
  }

  /**
   * Tab-completion for the destination arguments. Each word is its own parameter, so {@code input}
   * is just the word under the cursor — a completion replaces that word alone — and the words
   * before it are readable from the context.
   */
  private static List<CommandCompletion> complete(
      org.spongepowered.api.command.parameter.CommandContext context,
      String input,
      SpongeIntegrationRegistry integrations) {
    Optional<ServerPlayer> player = context.cause().first(ServerPlayer.class);
    if (player.isEmpty()) {
      return List.of();
    }
    List<String> tokens = typedWords(context);
    // The word under the cursor has usually been parsed into the context already; it is the same
    // string, in the last position. Counting it twice would place the player a level too deep.
    if (!input.isEmpty() && !tokens.isEmpty() && tokens.getLast().equals(input)) {
      tokens.removeLast();
    }
    tokens.add(input); // the (possibly empty) word being typed
    List<String> suggestions =
        DestinationResolver.suggest(
            destinationRoots(integrations, player.get()),
            FlagParser.destinationTokens(tokens),
            player.get()::hasPermission,
            canNavigate(player.get()));
    // Only offer completions once the candidate set is small; otherwise the player narrows first.
    if (suggestions.size() > MAX_DESTINATION_SUGGESTIONS) {
      return List.of();
    }
    List<CommandCompletion> completions = new ArrayList<>();
    for (String suggestion : suggestions) {
      completions.add(CommandCompletion.of(suggestion));
    }
    return completions;
  }

  private static void sendFailure(
      ServerPlayer player, Locale locale, Messages messages, FailureReason reason) {
    switch (reason) {
      case NO_ROUTE, DESTINATION_UNREACHABLE ->
          messages.send(player, locale, CobblestoneMessages.NAVIGATE_NO_ROUTE);
      case LIMIT_EXCEEDED ->
          messages.send(player, locale, CobblestoneMessages.NAVIGATE_LIMIT_EXCEEDED);
      case TIMED_OUT -> messages.send(player, locale, CobblestoneMessages.NAVIGATE_TIMED_OUT);
      case CANCELLED -> {
        /* silent: the player asked for it */
      }
      default -> messages.send(player, locale, CobblestoneMessages.NAVIGATE_ERROR);
    }
  }

  private static void sendFlagError(
      ServerPlayer player, Locale locale, Messages messages, FlagParser.Invalid invalid) {
    switch (invalid.error()) {
      case UNKNOWN_FLAG ->
          messages.send(player, locale, CobblestoneMessages.NAVIGATE_FLAG_UNKNOWN, invalid.token());
      case MISSING_VALUE ->
          messages.send(
              player, locale, CobblestoneMessages.NAVIGATE_FLAG_MISSING_VALUE, invalid.token());
      case UNKNOWN_MODE ->
          messages.send(
              player, locale, CobblestoneMessages.NAVIGATE_FLAG_UNKNOWN_MODE, invalid.token());
      default -> messages.send(player, locale, CobblestoneMessages.NAVIGATE_ERROR);
    }
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
}
