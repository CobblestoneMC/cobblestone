/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
import org.cobblestonemc.minecraft.RegionFactory;
import org.cobblestonemc.minecraft.api.MinecraftSearchSettings;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.Permissions;
import org.cobblestonemc.plugin.api.DestinationTree;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.cobblestonemc.plugin.api.TripOutcome;
import org.cobblestonemc.plugin.api.Validity;
import org.cobblestonemc.plugin.command.FlagParser;
import org.cobblestonemc.plugin.command.NavigationFlags;
import org.cobblestonemc.plugin.destination.DestinationDetails;
import org.cobblestonemc.plugin.destination.DestinationResolver;
import org.cobblestonemc.plugin.destination.DestinationRoots;
import org.cobblestonemc.plugin.destination.NavigationPermissions;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.menu.RecentDestinations;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Message0;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.search.SearchGate;
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.trip.GuideSearch;
import org.cobblestonemc.plugin.trip.LiveSearch;
import org.cobblestonemc.plugin.trip.TripLabel;
import org.cobblestonemc.sponge.SpongeNavigationServiceImpl;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCompletion;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.CommandContext;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.util.Tristate;
import org.spongepowered.api.world.server.ServerLocation;

/**
 * The {@code /navigate} (alias {@code /nav}) command: parse flags, resolve the destination through
 * the registered providers, run the search, and — on success — create the chosen navigator and
 * start a guided trip. All the non-Sponge logic lives in platform-neutral plugin-core helpers (flag
 * parsing, destination resolution). Typed on its own, it opens the navigation menu.
 */
final class NavigateCommand {

  // How many words /navigate accepts. Each word is its own parameter (see #command), so this is a
  // hard cap: a destination address with its options, plus a flag or two, fits comfortably.
  private static final int MAX_WORDS = 12;
  // One key per word position. Distinct keys, all read back in order, so every word lands in the
  // same parse context — which is what lets tab-completion see the words typed before the cursor.
  private static final List<Parameter.Key<String>> WORD_KEYS = wordKeys();
  // Typed alone, prints the command's help rather than being read as a destination.
  private static final String HELP_FLAG = "-help";
  // A tiny, greedy search for the off-trail "guide" path: bounded and heavily weighted so it's
  // cheap.
  private static final SearchSettings GUIDE_SETTINGS =
      SearchSettings.builder()
          .maxCellsVisited(4000)
          .maxWallClockMillis(1500L)
          .heuristicWeight(2.0)
          .build();

  private final SpongeNavigationServiceImpl navigationService;
  private final SpongeTripServiceImpl tripService;
  private final SpongeIntegrationRegistry integrations;
  private final SearchRegistry<ServerLocation> searches;
  private final SearchGate gate;
  private final Supplier<SearchSettings> searchSettings;
  private final CobblestoneLogger log;
  private final Messages messages;
  private final RecentDestinations recents;

  NavigateCommand(
      SpongeNavigationServiceImpl navigationService,
      SpongeTripServiceImpl tripService,
      SpongeIntegrationRegistry integrations,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      CobblestoneLogger log,
      Messages messages,
      RecentDestinations recents) {
    this.navigationService = navigationService;
    this.tripService = tripService;
    this.integrations = integrations;
    this.searches = searches;
    this.gate = gate;
    this.searchSettings = searchSettings;
    this.log = log;
    this.messages = messages;
    this.recents = recents;
  }

  /**
   * Builds the command.
   *
   * @param openMenu opens the navigation menu, for a player who types the command on its own
   * @return the command
   */
  Command.Parameterized command(Consumer<ServerPlayer> openMenu) {
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
          Parameter.string().key(key).optional().completer(this::complete).build());
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
              List<String> words = typedWords(context);
              if (words.isEmpty()) {
                openMenu.accept(player.get());
              } else {
                run(player.get(), words);
              }
              return CommandResult.success();
            })
        .build();
  }

  /** The words the player has typed, in order, gathered from the per-word parameters. */
  private static List<String> typedWords(CommandContext context) {
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

  private void run(ServerPlayer player, List<String> tokens) {
    Locale locale = player.locale();
    if (tokens.equals(List.of(HELP_FLAG))) {
      navHelp(player, locale);
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

    DestinationResolver.Resolution<NavigationTarget> resolution =
        DestinationResolver.resolve(
            destinationRoots(player),
            parsed.destination(),
            player::hasPermission,
            canNavigate(player));
    if (resolution
        instanceof DestinationResolver.Ambiguous<NavigationTarget>(List<List<String>> addrs)) {
      messages.send(
          player,
          locale,
          CobblestoneMessages.NAVIGATE_DESTINATION_AMBIGUOUS,
          formatAddresses(addrs));
      return;
    }
    if (resolution instanceof DestinationResolver.InvalidOptions<NavigationTarget> invalid) {
      sendUsage(player, invalid);
      return;
    }
    if (!(resolution instanceof DestinationResolver.Resolved<NavigationTarget> resolved)) {
      messages.send(
          player,
          locale,
          CobblestoneMessages.NAVIGATE_DESTINATION_NOT_FOUND,
          String.join(" ", parsed.destination()));
      return;
    }
    navigate(player, locale, flags, resolved);
  }

  /**
   * Navigates a player to a destination picked without typing, as though they had typed it with no
   * flags. Call on the main thread: the destination is built here, and may read the world around
   * them.
   *
   * @param player the player
   * @param destination the destination, with its options parsed
   */
  void navigate(ServerPlayer player, DestinationResolver.Resolved<NavigationTarget> destination) {
    navigate(player, player.locale(), NavigationFlags.defaults(), destination);
  }

  private void navigate(
      ServerPlayer player,
      Locale locale,
      NavigationFlags flags,
      DestinationResolver.Resolved<NavigationTarget> resolved) {
    List<String> address = resolved.address();
    // Built only now, on the player's own thread, which may read the world around them.
    Validity<NavigationTarget> built;
    try {
      built = resolved.leaf().build(resolved.values());
    } catch (RuntimeException e) {
      // Most likely an integration's leaf; the address's first key names the plugin.
      log.error(
          "navigate {} -> {}: building the destination failed",
          e,
          player.name(),
          String.join(" ", address));
      messages.send(player, locale, CobblestoneMessages.NAVIGATE_ERROR);
      return;
    }
    NavigationTarget destination;
    switch (built) {
      case Validity.Invalid<NavigationTarget>(Component reason) -> {
        player.sendMessage(reason);
        return;
      }
      case Validity.Valid<NavigationTarget>(NavigationTarget valid) -> destination = valid;
    }

    boolean live =
        switch (flags.liveness()) {
          case LIVE -> true;
          case NO_LIVE -> false;
          case DEFAULT -> destination.mobile();
        };
    startSearch(
        player,
        locale,
        TripLabel.address(address),
        DestinationDetails.hover(address, destination),
        destination.destination(),
        destination::exists,
        flags,
        live,
        new RecentDestinations.Recent(address, resolved.optionTokens()));
  }

  private void startSearch(
      ServerPlayer player,
      Locale locale,
      TripLabel tripLabel,
      Component hover,
      Destination<RegionFactory> destination,
      BooleanSupplier targetExists,
      NavigationFlags flags,
      boolean live,
      RecentDestinations.Recent recent) {
    String label = tripLabel.text();
    UUID uuid = player.uniqueId();
    long startNanos = System.nanoTime();
    gate.beginForced(uuid);
    SearchHandle<ServerLocation, MinecraftStepPayload> handle =
        navigationService.navigatePlayerToDestination(
            player, destination, searchSettingsFor(searchSettings, flags));
    searches.track(uuid, handle);
    // Hovering shows where the search is headed — for a fixed place, its coordinates, so the player
    // can still get there by hand if no route turns up.
    player.sendMessage(
        messages
            .render(locale, CobblestoneMessages.NAVIGATE_SEARCHING)
            .hoverEvent(HoverEvent.showText(hover)));

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
                sendFailure(player, locale, messages, f.reason(), hover);
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
                        tripLabel,
                        settings,
                        liveSearch(player, destination, flags),
                        guideSearch(player, flags),
                        live,
                        targetExists)
                    .whenComplete(
                        (outcome, tripError) -> {
                          if (tripError == null && outcome instanceof TripOutcome.Started) {
                            recents.record(uuid, recent);
                          }
                          reportTrip(
                              player,
                              locale,
                              messages,
                              log,
                              label,
                              path,
                              elapsedMillis,
                              tripError != null ? new TripOutcome.Error(tripError) : outcome);
                        });
              }
            });
  }

  private static void reportTrip(
      ServerPlayer player,
      Locale locale,
      Messages messages,
      CobblestoneLogger log,
      String label,
      Path<ServerLocation, MinecraftStepPayload> path,
      long elapsedMillis,
      TripOutcome outcome) {
    switch (outcome) {
      case TripOutcome.Started started -> {
        Component found = messages.render(locale, CobblestoneMessages.NAVIGATE_STARTED);
        Component stats =
            messages.render(
                locale,
                CobblestoneMessages.NAVIGATE_STATS,
                elapsedMillis,
                messages.formatDuration(locale, path.duration()));
        player.sendMessage(found.hoverEvent(HoverEvent.showText(stats)));
      }
      case TripOutcome.TripLimitReached limit ->
          messages.send(player, locale, CobblestoneMessages.NAVIGATE_TRIP_LIMIT);
      case TripOutcome.Failed failed ->
          messages.send(player, locale, CobblestoneMessages.NAVIGATE_ERROR);
      case TripOutcome.Error error -> {
        log.error(
            "navigate {} -> {}: starting the trip failed", error.throwable(), player.name(), label);
        messages.send(player, locale, CobblestoneMessages.NAVIGATE_ERROR);
      }
    }
  }

  private LiveSearch<ServerLocation> liveSearch(
      ServerPlayer player, Destination<RegionFactory> destination, NavigationFlags flags) {
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

  private GuideSearch<ServerLocation> guideSearch(ServerPlayer player, NavigationFlags flags) {
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

  private void navHelp(ServerPlayer player, Locale locale) {
    messages.send(player, locale, CobblestoneMessages.NAVIGATE_HELP_HEADER);
    SpongeCommandHelp.line(player, messages, locale, "/navigate", "command.navigate.help.menu");
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
    SpongeCommandHelp.line(player, messages, locale, "-help", "command.navigate.help.help");
  }

  /**
   * The Cobblestone navigation-gate check for a player: default-allow, so a destination is offered
   * unless its {@code cobblestone.navigate.<address>} node is explicitly denied.
   *
   * @param player the player
   * @return tests a canonical address
   */
  static Predicate<List<String>> canNavigate(ServerPlayer player) {
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

  private Map<String, DestinationTree<NavigationTarget>> destinationRoots(ServerPlayer player) {
    return DestinationRoots.of(integrations.destinationProviders(), player);
  }

  /**
   * Tab-completion for the destination arguments. Each word is its own parameter, so {@code input}
   * is just the word under the cursor — a completion replaces that word alone — and the words
   * before it are readable from the context.
   */
  private List<CommandCompletion> complete(CommandContext context, String input) {
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
    List<String> destinationTokens;
    if (input.equals("-")) {
      // Not yet a flag or a negative number: offer an option's negative values.
      destinationTokens = new ArrayList<>(FlagParser.destinationTokens(tokens));
      destinationTokens.add(input);
    } else if (FlagParser.isFlag(input)) {
      return List.of(); // a flag is being typed, not a destination word
    } else {
      tokens.add(input); // the (possibly empty) word being typed
      destinationTokens = FlagParser.destinationTokens(tokens);
    }
    List<String> suggestions =
        DestinationResolver.suggest(
            destinationRoots(player.get()),
            destinationTokens,
            player.get()::hasPermission,
            canNavigate(player.get()));
    List<CommandCompletion> completions = new ArrayList<>();
    for (String suggestion : suggestions) {
      completions.add(CommandCompletion.of(suggestion));
    }
    return completions;
  }

  /** Reports a failed search, with the same hover as the "Searching…" line that preceded it. */
  private static void sendFailure(
      ServerPlayer player,
      Locale locale,
      Messages messages,
      FailureReason reason,
      Component hover) {
    Message0 message =
        switch (reason) {
          case NO_ROUTE, DESTINATION_UNREACHABLE -> CobblestoneMessages.NAVIGATE_NO_ROUTE;
          case LIMIT_EXCEEDED -> CobblestoneMessages.NAVIGATE_LIMIT_EXCEEDED;
          case TIMED_OUT -> CobblestoneMessages.NAVIGATE_TIMED_OUT;
          case CANCELLED -> null; // silent: the player asked for it
          default -> CobblestoneMessages.NAVIGATE_ERROR;
        };
    if (message != null) {
      player.sendMessage(messages.render(locale, message).hoverEvent(HoverEvent.showText(hover)));
    }
  }

  /**
   * Tells a player how a destination's options are typed, after they gave it options that do not
   * fit.
   *
   * @param player the player
   * @param invalid what did not fit
   */
  void sendUsage(
      ServerPlayer player, DestinationResolver.InvalidOptions<NavigationTarget> invalid) {
    Locale locale = player.locale();
    String usage = DestinationResolver.usage(invalid.address(), invalid.options());
    if (invalid.invalidToken() == null) {
      messages.send(player, locale, CobblestoneMessages.NAVIGATE_DESTINATION_USAGE, usage);
    } else {
      messages.send(
          player,
          locale,
          CobblestoneMessages.NAVIGATE_OPTION_INVALID,
          invalid.invalidToken(),
          usage);
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
