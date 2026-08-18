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
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftSearchSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import net.whimxiqal.odyssey.plugin.api.PlatformDestinationTree;
import net.whimxiqal.odyssey.plugin.api.TripOutcome;
import net.whimxiqal.odyssey.plugin.destination.DestinationResolver;
import net.whimxiqal.odyssey.plugin.search.SearchGate;
import net.whimxiqal.odyssey.plugin.search.SearchRegistry;
import net.whimxiqal.odyssey.sponge12.SpongeNavigationServiceImpl;
import net.whimxiqal.odyssey.sponge12.plugin.api.DestinationService;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * The {@code /navigate} (alias {@code /nav}) command: {@code <x> <y> <z>} routes to coordinates in
 * the player's world, and {@code <destination…>} resolves a named destination (waypoint, player,
 * world, or an integration's target) through the registered providers, then starts a guided trip
 * with the trail navigator.
 *
 * <p>v1 scope: no flags ({@code -navigator}/{@code -live}/exclusions) or re-search-on-stray yet,
 * and the navigation-permission gate is open; those are follow-ups. (The Paper command has the full
 * set.)
 */
final class NavigateCommand {

  private NavigateCommand() {}

  static Command.Parameterized build(
      SpongeNavigationServiceImpl navigationService,
      SpongeTripServiceImpl tripService,
      SpongeIntegrationRegistry integrations,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      java.util.function.Supplier<SearchSettings> searchSettings) {
    Parameter.Value<String> destination =
        Parameter.remainingJoinedStrings().key("destination").build();
    return Command.builder()
        .shortDescription(Component.text("Navigate to a location or destination"))
        .permission("odyssey.navigate")
        .addParameter(destination)
        .executor(
            context -> {
              Optional<ServerPlayer> player = context.cause().first(ServerPlayer.class);
              if (player.isEmpty()) {
                context.sendMessage(Component.text("Only players can navigate."));
                return CommandResult.success();
              }
              List<String> tokens = tokenize(context.requireOne(destination));
              if (tokens.isEmpty()) {
                context.sendMessage(
                    Component.text("Usage: /navigate <x> <y> <z> | <destination…>"));
                return CommandResult.success();
              }
              double[] coordinates = asCoordinates(tokens);
              if (coordinates != null) {
                navigateToCoordinates(player.get(), coordinates, tripService);
              } else {
                navigateToNamed(
                    player.get(),
                    tokens,
                    navigationService,
                    tripService,
                    integrations,
                    searches,
                    gate,
                    searchSettings);
              }
              return CommandResult.success();
            })
        .build();
  }

  private static void navigateToCoordinates(
      ServerPlayer player, double[] xyz, SpongeTripServiceImpl tripService) {
    ServerLocation location = ServerLocation.of(player.world(), xyz[0], xyz[1], xyz[2]);
    tripService.navigate(
        player,
        location,
        NavigatorSettings.defaults(),
        reason -> player.sendMessage(Component.text("Could not navigate there: " + reason)));
    player.sendMessage(Component.text("Searching for a route…"));
  }

  private static void navigateToNamed(
      ServerPlayer player,
      List<String> tokens,
      SpongeNavigationServiceImpl navigationService,
      SpongeTripServiceImpl tripService,
      SpongeIntegrationRegistry integrations,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      java.util.function.Supplier<SearchSettings> searchSettings) {
    List<PlatformDestinationTree<ServerWorld, Vector3i>> roots =
        destinationRoots(integrations, player);
    // v1: an open navigation gate (canNavigate = always). The permission gate is a follow-up.
    DestinationResolver.Resolution<ServerWorld, Vector3i> resolution =
        DestinationResolver.resolve(roots, tokens, player::hasPermission, address -> true);
    if (resolution
        instanceof DestinationResolver.Ambiguous<ServerWorld, Vector3i>(List<List<String>> addrs)) {
      player.sendMessage(
          Component.text("That destination is ambiguous: " + formatAddresses(addrs)));
      return;
    }
    if (!(resolution
        instanceof
        DestinationResolver.Resolved<ServerWorld, Vector3i>(
            MinecraftDestination<ServerWorld, Vector3i> destination,
            List<String> address))) {
      player.sendMessage(
          Component.text("No destination found for \"" + String.join(" ", tokens) + "\"."));
      return;
    }

    String label = String.join(" ", address);
    UUID uuid = player.uniqueId();
    gate.beginForced(uuid);
    MinecraftSearchSettings settings =
        new MinecraftSearchSettings(
            searchSettings.get(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
    SearchHandle<ServerLocation, MinecraftStepPayload> handle =
        navigationService.navigatePlayerToDestination(player, destination.destination(), settings);
    searches.track(uuid, handle);
    player.sendMessage(Component.text("Searching for a route to " + label + "…"));
    handle
        .future()
        .whenComplete(
            (result, error) -> {
              searches.untrack(uuid, handle);
              gate.end(uuid);
              if (error != null || result instanceof NavigationResult.Error) {
                player.sendMessage(Component.text("Something went wrong finding a route."));
              } else if (result instanceof NavigationResult.Failure) {
                player.sendMessage(Component.text("No route found to " + label + "."));
              } else if (result
                  instanceof
                  NavigationResult.Success<ServerLocation, MinecraftStepPayload>(
                      Path<ServerLocation, MinecraftStepPayload> path)) {
                startTrip(player, path, label, tripService);
              }
            });
  }

  private static void startTrip(
      ServerPlayer player,
      Path<ServerLocation, MinecraftStepPayload> path,
      String label,
      SpongeTripServiceImpl tripService) {
    tripService
        .startTrip(player, path, NavigatorSettings.defaults())
        .whenComplete(
            (outcome, error) -> {
              if (error != null || outcome instanceof TripOutcome.Failed) {
                player.sendMessage(Component.text("Could not start the trip."));
              } else if (outcome instanceof TripOutcome.TripLimitReached) {
                player.sendMessage(Component.text("You have too many active trips."));
              } else {
                player.sendMessage(
                    Component.text("Route found to " + label + " — follow the trail."));
              }
            });
  }

  private static List<PlatformDestinationTree<ServerWorld, Vector3i>> destinationRoots(
      SpongeIntegrationRegistry integrations, ServerPlayer player) {
    List<PlatformDestinationTree<ServerWorld, Vector3i>> roots = new ArrayList<>();
    for (DestinationService provider : integrations.destinationProviders()) {
      roots.addAll(provider.provide(player));
    }
    return roots;
  }

  /** Parses three whitespace tokens as {@code x y z} doubles, or {@code null} if they are not. */
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

  private static List<String> tokenize(String raw) {
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? List.of() : Arrays.asList(trimmed.split("\\s+"));
  }

  private static String formatAddresses(List<List<String>> addresses) {
    List<String> joined = new ArrayList<>();
    for (List<String> address : addresses) {
      joined.add(String.join(" ", address));
    }
    return String.join(", ", joined);
  }
}
