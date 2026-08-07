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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.api.FailureReason;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.PaperOdysseyApiImpl;
import net.whimxiqal.odyssey.paper.plugin.api.PaperDestinationProvider;
import net.whimxiqal.odyssey.paper.plugin.api.PaperNavigatorFactory;
import net.whimxiqal.odyssey.plugin.api.DestinationTree;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import net.whimxiqal.odyssey.plugin.api.Navigator;
import net.whimxiqal.odyssey.plugin.command.FlagParser;
import net.whimxiqal.odyssey.plugin.command.NavigationFlags;
import net.whimxiqal.odyssey.plugin.destination.DestinationResolver;
import net.whimxiqal.odyssey.plugin.message.Messages;
import net.whimxiqal.odyssey.plugin.message.OdysseyMessages;
import net.whimxiqal.odyssey.plugin.trip.LiveSearch;
import net.whimxiqal.odyssey.plugin.trip.Trip;
import net.whimxiqal.odyssey.plugin.trip.TripManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
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
  private static final Set<String> VALUE_FLAGS =
      Set.of("-navigator", "-no-world", "-no-dimension", "-no-mode");

  private NavigateCommand() {
  }

  static LiteralCommandNode<CommandSourceStack> build(
      PaperOdysseyApiImpl platformApi, TripManager<Location> trips, SearchRegistry searches,
      SearchGate gate, long liveIntervalMillis, Messages messages) {
    return Commands.literal("navigate")
        .requires(source -> source.getSender().hasPermission(PERMISSION_NAVIGATE))
        .then(Commands.argument("args", StringArgumentType.greedyString())
            .suggests((ctx, builder) -> suggest(ctx, builder))
            .executes(ctx -> run(ctx, platformApi, trips, searches, gate, liveIntervalMillis, messages)))
        .build();
  }

  private static int run(
      CommandContext<CommandSourceStack> ctx,
      PaperOdysseyApiImpl platformApi,
      TripManager<Location> trips,
      SearchRegistry searches,
      SearchGate gate,
      long liveIntervalMillis,
      Messages messages) {
    CommandSender sender = ctx.getSource().getSender();
    Locale locale = localeOf(sender, messages);
    if (!(sender instanceof Player player)) {
      messages.send(sender, locale, OdysseyMessages.PLAYERS_ONLY);
      return Command.SINGLE_SUCCESS;
    }

    FlagParser.Result parseResult = FlagParser.parse(tokenize(StringArgumentType.getString(ctx, "args")));
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
    PaperNavigatorFactory factory = navigatorFactory(flags.navigator());
    if (factory == null) {
      messages.send(player, locale, OdysseyMessages.NAVIGATE_UNKNOWN_NAVIGATOR, flags.navigator());
      return Command.SINGLE_SUCCESS;
    }

    DestinationResolver.Resolution<World, Vector3i> resolution =
        DestinationResolver.resolve(destinationRoots(player), parsed.destination(), player::hasPermission);
    if (resolution instanceof DestinationResolver.Ambiguous<World, Vector3i> ambiguous) {
      messages.send(player, locale, OdysseyMessages.NAVIGATE_DESTINATION_AMBIGUOUS, formatAddresses(ambiguous.addresses()));
      return Command.SINGLE_SUCCESS;
    }
    if (!(resolution instanceof DestinationResolver.Resolved<World, Vector3i> resolved)) {
      messages.send(player, locale, OdysseyMessages.NAVIGATE_DESTINATION_NOT_FOUND, String.join(" ", parsed.destination()));
      return Command.SINGLE_SUCCESS;
    }

    startSearch(player, locale, resolved.destination(), flags, factory, platformApi, trips, searches,
        gate, liveIntervalMillis, messages);
    return Command.SINGLE_SUCCESS;
  }

  private static void startSearch(
      Player player,
      Locale locale,
      MinecraftDestination<World, Vector3i> destination,
      NavigationFlags flags,
      PaperNavigatorFactory factory,
      PaperOdysseyApiImpl platformApi,
      TripManager<Location> trips,
      SearchRegistry searches,
      SearchGate gate,
      long liveIntervalMillis,
      Messages messages) {
    UUID uuid = player.getUniqueId();
    gate.beginForced(uuid); // a manual search always runs and counts toward the budget
    SearchHandle<Step<Location, MinecraftStepPayload>> handle = platformApi.navigatePlayerToDestination(
        player, destination.destination(), flags.excludedModes(),
        flags.excludedWorlds(), flags.excludedDimensions(), SearchSettings.defaults());
    searches.track(uuid, handle);
    messages.send(player, locale, OdysseyMessages.NAVIGATE_SEARCHING);

    handle.future().whenComplete((result, error) -> {
      searches.untrack(uuid, handle);
      gate.end(uuid);
      if (error != null) {
        messages.send(player, locale, OdysseyMessages.NAVIGATE_ERROR);
        return;
      }
      if (result instanceof NavigationResult.Failure<Step<Location, MinecraftStepPayload>> failure) {
        if (failure.reason() != FailureReason.CANCELLED) {
          messages.send(player, locale,
              failure.reason() == FailureReason.ERROR
                  ? OdysseyMessages.NAVIGATE_ERROR : OdysseyMessages.NAVIGATE_NO_ROUTE);
        }
        return;
      }
      Path<Step<Location, MinecraftStepPayload>> path =
          ((NavigationResult.Success<Step<Location, MinecraftStepPayload>>) result).path();
      Location origin = path.steps().isEmpty() ? null : path.steps().get(0).position();
      if (origin == null || origin.getWorld() == null) {
        messages.send(player, locale, OdysseyMessages.NAVIGATE_ERROR);
        return;
      }
      // Trip creation and rendering must run on the location's owning thread (Folia-safe).
      platformApi.scheduler().runAtPosition(platformApi.position(origin), () -> {
        if (!player.isOnline()) {
          return;
        }
        Navigator<Location> navigator = factory.create(player, path, new PaperNavigatorContext(player));
        Optional<Trip<Location>> trip = flags.live()
            ? trips.startLive(uuid, platformApi.position(origin), flags.navigator(), navigator,
                liveSearch(player, destination, flags, platformApi, searches, gate), liveIntervalMillis)
            : trips.start(uuid, platformApi.position(origin), flags.navigator(), navigator);
        messages.send(player, locale,
            trip.isEmpty() ? OdysseyMessages.NAVIGATE_TRIP_LIMIT : OdysseyMessages.NAVIGATE_STARTED);
      });
    });
  }

  /** Builds the re-search behavior for a {@code -live} trip; yields to the per-player search budget. */
  private static LiveSearch<Location> liveSearch(
      Player player,
      MinecraftDestination<World, Vector3i> destination,
      NavigationFlags flags,
      PaperOdysseyApiImpl platformApi,
      SearchRegistry searches,
      SearchGate gate) {
    UUID uuid = player.getUniqueId();
    return () -> {
      if (!player.isOnline() || !gate.tryBegin(uuid)) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      SearchHandle<Step<Location, MinecraftStepPayload>> handle = platformApi.navigatePlayerToDestination(
          player, destination.destination(), flags.excludedModes(),
          flags.excludedWorlds(), flags.excludedDimensions(), SearchSettings.defaults());
      searches.track(uuid, handle);
      return handle.future().handle((result, error) -> {
        searches.untrack(uuid, handle);
        gate.end(uuid);
        if (error == null
            && result instanceof NavigationResult.Success<Step<Location, MinecraftStepPayload>> success
            && !success.path().steps().isEmpty()) {
          return Optional.of(success.path());
        }
        return Optional.<Path<Step<Location, MinecraftStepPayload>>>empty();
      });
    };
  }

  private static CompletableFuture<Suggestions> suggest(
      CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    if (!(ctx.getSource().getSender() instanceof Player player)) {
      return builder.buildFuture();
    }
    String remaining = builder.getRemaining();
    List<String> tokens = tokenizeKeepingTrailing(remaining);
    String last = tokens.isEmpty() ? "" : tokens.get(tokens.size() - 1);
    String previous = tokens.size() >= 2 ? tokens.get(tokens.size() - 2).toLowerCase(Locale.ROOT) : "";
    SuggestionsBuilder offset = builder.createOffset(builder.getStart() + remaining.length() - last.length());

    if (previous.equals("-navigator")) {
      navigatorIds().stream().filter(id -> id.startsWith(last)).forEach(offset::suggest);
    } else if (previous.equals("-no-mode")) {
      FlagParser.modeWords().stream().filter(word -> word.startsWith(last)).sorted().forEach(offset::suggest);
    } else if (last.startsWith("-")) {
      flagNames().stream().filter(flag -> flag.startsWith(last)).forEach(offset::suggest);
    } else {
      DestinationResolver.suggest(destinationRoots(player), destinationTokens(tokens), player::hasPermission)
          .forEach(offset::suggest);
    }
    return offset.buildFuture();
  }

  private static List<DestinationTree<World, Vector3i>> destinationRoots(Player player) {
    List<DestinationTree<World, Vector3i>> roots = new ArrayList<>();
    for (RegisteredServiceProvider<PaperDestinationProvider> registration
        : Bukkit.getServicesManager().getRegistrations(PaperDestinationProvider.class)) {
      roots.add(registration.getProvider().provide(player));
    }
    return roots;
  }

  private static PaperNavigatorFactory navigatorFactory(String id) {
    for (RegisteredServiceProvider<PaperNavigatorFactory> registration
        : Bukkit.getServicesManager().getRegistrations(PaperNavigatorFactory.class)) {
      if (registration.getProvider().key().equals(id)) {
        return registration.getProvider();
      }
    }
    return null;
  }

  private static List<String> navigatorIds() {
    List<String> ids = new ArrayList<>();
    for (RegisteredServiceProvider<PaperNavigatorFactory> registration
        : Bukkit.getServicesManager().getRegistrations(PaperNavigatorFactory.class)) {
      ids.add(registration.getProvider().key());
    }
    return ids;
  }

  private static List<String> flagNames() {
    List<String> flags = new ArrayList<>(List.of(
        "-navigator", "-no-world", "-no-dimension", "-no-mode", "-live"));
    FlagParser.modeWords().stream().sorted().forEach(word -> flags.add("-no-" + word));
    return flags;
  }

  /** Positional destination tokens only: flags and the values they consume are dropped. */
  private static List<String> destinationTokens(List<String> tokens) {
    List<String> out = new ArrayList<>();
    boolean skipValue = false;
    for (String token : tokens) {
      if (skipValue) {
        skipValue = false;
        continue;
      }
      String lower = token.toLowerCase(Locale.ROOT);
      if (VALUE_FLAGS.contains(lower)) {
        skipValue = true;
      } else if (!token.startsWith("-")) {
        out.add(token);
      }
    }
    return out;
  }

  private static void sendFlagError(
      Player player, Locale locale, Messages messages, FlagParser.Invalid invalid) {
    switch (invalid.error()) {
      case UNKNOWN_FLAG ->
          messages.send(player, locale, OdysseyMessages.NAVIGATE_FLAG_UNKNOWN, invalid.token());
      case MISSING_VALUE ->
          messages.send(player, locale, OdysseyMessages.NAVIGATE_FLAG_MISSING_VALUE, invalid.token());
      case UNKNOWN_MODE ->
          messages.send(player, locale, OdysseyMessages.NAVIGATE_FLAG_UNKNOWN_MODE, invalid.token());
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

  /** Like {@link #tokenize} but preserves a trailing empty token when the input ends in a space. */
  private static List<String> tokenizeKeepingTrailing(String raw) {
    if (raw.isEmpty()) {
      return List.of("");
    }
    List<String> tokens = new ArrayList<>(Arrays.asList(raw.split("\\s+", -1)));
    if (tokens.isEmpty()) {
      tokens.add("");
    }
    return tokens;
  }

  private static Locale localeOf(CommandSender sender, Messages messages) {
    return sender instanceof Player player ? player.locale() : messages.defaultLocale();
  }
}
