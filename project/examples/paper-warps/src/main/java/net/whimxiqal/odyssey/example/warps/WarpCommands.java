/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.example.warps;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The commands: {@code /warp <name>} (the teleport players run — and the one Odyssey prompts on a
 * routed command warp) and {@code /odywarp …} to manage destinations, warps, and portals. Built with
 * Paper's Brigadier {@link Commands} API.
 */
final class WarpCommands {

  static final String PERMISSION_USE = "odysseywarps.use";
  static final String PERMISSION_ADMIN = "odysseywarps.admin";
  private static final double DEFAULT_WARP_COST = 3.0;
  private static final double DEFAULT_PORTAL_COST = 0.0;

  private WarpCommands() {
  }

  /** Builds {@code /warp <name>}: teleport the running player to the named warp. */
  static LiteralCommandNode<CommandSourceStack> warp(WarpStore store) {
    return Commands.literal("warp")
        .requires(source -> source.getSender().hasPermission(PERMISSION_USE))
        .then(Commands.argument("name", StringArgumentType.word())
            .suggests((ctx, builder) -> suggest(builder, store.warps().stream().map(Warp::name)))
            .executes(ctx -> teleport(ctx, store)))
        .build();
  }

  /** Builds the {@code /odywarp} management tree. */
  static LiteralCommandNode<CommandSourceStack> admin(WarpStore store, Selections selections) {
    return Commands.literal("odywarp")
        .requires(source -> source.getSender().hasPermission(PERMISSION_ADMIN))
        .then(Commands.literal("create")
            .then(named("destination", ctx -> createDestination(ctx, store)))
            .then(named("warp", ctx -> createWarp(ctx, store)))
            .then(Commands.literal("portal")
                .then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("destination", StringArgumentType.word())
                        .suggests((ctx, b) -> suggest(b, store.destinations().stream().map(Destination::name)))
                        .executes(ctx -> createPortal(ctx, store, selections))))))
        .then(Commands.literal("remove")
            .then(namedSuggested("destination", () -> store.destinations().stream().map(Destination::name),
                ctx -> remove(ctx, "destination", store::removeDestination)))
            .then(namedSuggested("warp", () -> store.warps().stream().map(Warp::name),
                ctx -> remove(ctx, "warp", store::removeWarp)))
            .then(namedSuggested("portal", () -> store.portals().stream().map(Portal::name),
                ctx -> remove(ctx, "portal", store::removePortal))))
        .then(Commands.literal("cost")
            .then(costBranch("warp", ctx -> setWarpCost(ctx, store),
                (ctx, b) -> suggest(b, store.warps().stream().map(Warp::name))))
            .then(costBranch("portal", ctx -> setPortalCost(ctx, store),
                (ctx, b) -> suggest(b, store.portals().stream().map(Portal::name)))))
        .then(Commands.literal("list").executes(ctx -> list(ctx, store)))
        .then(Commands.literal("selection")
            .then(Commands.literal("clear").executes(ctx -> clearSelection(ctx, selections))))
        .build();
  }

  private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> named(
      String literal, com.mojang.brigadier.Command<CommandSourceStack> action) {
    return Commands.literal(literal)
        .then(Commands.argument("name", StringArgumentType.word()).executes(action));
  }

  private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> namedSuggested(
      String literal, java.util.function.Supplier<java.util.stream.Stream<String>> names,
      com.mojang.brigadier.Command<CommandSourceStack> action) {
    return Commands.literal(literal)
        .then(Commands.argument("name", StringArgumentType.word())
            .suggests((ctx, b) -> suggest(b, names.get()))
            .executes(action));
  }

  private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> costBranch(
      String literal, com.mojang.brigadier.Command<CommandSourceStack> action,
      com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> suggestions) {
    return Commands.literal(literal)
        .then(Commands.argument("name", StringArgumentType.word())
            .suggests(suggestions)
            .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0)).executes(action)));
  }

  // ---- warp teleport ----

  private static int teleport(CommandContext<CommandSourceStack> ctx, WarpStore store) {
    Player player = requirePlayer(ctx);
    if (player == null) {
      return 0;
    }
    String name = StringArgumentType.getString(ctx, "name");
    Optional<Warp> warp = store.getWarp(name);
    if (warp.isEmpty()) {
      error(player, "No warp named '" + name + "'.");
      return 0;
    }
    World world = Worlds.byKey(warp.get().world());
    if (world == null) {
      error(player, "Warp '" + name + "' points to an unloaded world.");
      return 0;
    }
    player.teleportAsync(warp.get().toLocation(world));
    player.sendMessage(Component.text("Warped to " + name + ".", NamedTextColor.AQUA));
    return Command.SINGLE_SUCCESS;
  }

  // ---- create ----

  private static int createDestination(CommandContext<CommandSourceStack> ctx, WarpStore store) {
    Player player = requirePlayer(ctx);
    if (player == null) {
      return 0;
    }
    String name = WarpStore.key(StringArgumentType.getString(ctx, "name"));
    boolean existed = store.getDestination(name).isPresent();
    Location loc = player.getLocation();
    store.putDestination(new Destination(name, Worlds.keyOf(loc),
        loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
    player.sendMessage(Component.text(existed
        ? "Moved destination '" + name + "' here; all portals to it now lead here."
        : "Created destination '" + name + "'. Link a portal with /odywarp create portal <name> " + name + ".",
        NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  private static int createWarp(CommandContext<CommandSourceStack> ctx, WarpStore store) {
    Player player = requirePlayer(ctx);
    if (player == null) {
      return 0;
    }
    String name = WarpStore.key(StringArgumentType.getString(ctx, "name"));
    double cost = store.getWarp(name).map(Warp::cost).orElse(DEFAULT_WARP_COST);
    Location loc = player.getLocation();
    store.putWarp(new Warp(name, Worlds.keyOf(loc),
        loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), cost));
    player.sendMessage(Component.text(
        "Created warp '" + name + "'. /warp " + name + " leads here from anywhere.", NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  private static int createPortal(
      CommandContext<CommandSourceStack> ctx, WarpStore store, Selections selections) {
    Player player = requirePlayer(ctx);
    if (player == null) {
      return 0;
    }
    String name = WarpStore.key(StringArgumentType.getString(ctx, "name"));
    String destination = WarpStore.key(StringArgumentType.getString(ctx, "destination"));
    if (store.getDestination(destination).isEmpty()) {
      error(player, "No destination named '" + destination
          + "'. Create it first with /odywarp create destination " + destination + ".");
      return 0;
    }
    Selections.Selection selection = selections.get(player.getUniqueId());
    Portal portal;
    if (selection == null || (!selection.hasCorner1() && !selection.hasCorner2())) {
      Location loc = player.getLocation();
      portal = new Portal(name, Worlds.keyOf(loc),
          loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
          loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), destination, DEFAULT_PORTAL_COST);
    } else if (!selection.complete()) {
      error(player, "Only one corner is selected. Set the other with the wooden shovel, "
          + "or clear it with /odywarp selection clear.");
      return 0;
    } else {
      portal = new Portal(name, selection.world,
          Math.min(selection.corner1.x(), selection.corner2.x()),
          Math.min(selection.corner1.y(), selection.corner2.y()),
          Math.min(selection.corner1.z(), selection.corner2.z()),
          Math.max(selection.corner1.x(), selection.corner2.x()),
          Math.max(selection.corner1.y(), selection.corner2.y()),
          Math.max(selection.corner1.z(), selection.corner2.z()), destination, DEFAULT_PORTAL_COST);
    }
    store.putPortal(portal);
    selections.clear(player.getUniqueId());
    long cells = (long) (portal.maxX() - portal.minX() + 1)
        * (portal.maxY() - portal.minY() + 1) * (portal.maxZ() - portal.minZ() + 1);
    player.sendMessage(Component.text(
        "Created portal '" + name + "' (" + cells + " block(s)) → " + destination + ".", NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  // ---- remove / cost / list / selection ----

  private static int remove(
      CommandContext<CommandSourceStack> ctx, String kind, Function<String, Boolean> remover) {
    CommandSender sender = ctx.getSource().getSender();
    String name = StringArgumentType.getString(ctx, "name");
    if (remover.apply(name)) {
      sender.sendMessage(Component.text("Removed " + kind + " '" + name + "'.", NamedTextColor.GREEN));
    } else {
      error(sender, "No " + kind + " named '" + name + "'.");
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int setWarpCost(CommandContext<CommandSourceStack> ctx, WarpStore store) {
    CommandSender sender = ctx.getSource().getSender();
    String name = StringArgumentType.getString(ctx, "name");
    Optional<Warp> warp = store.getWarp(name);
    if (warp.isEmpty()) {
      error(sender, "No warp named '" + name + "'.");
      return 0;
    }
    double seconds = DoubleArgumentType.getDouble(ctx, "seconds");
    store.putWarp(warp.get().withCost(seconds));
    sender.sendMessage(Component.text(
        "Warp '" + name + "' now costs " + seconds + "s.", NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  private static int setPortalCost(CommandContext<CommandSourceStack> ctx, WarpStore store) {
    CommandSender sender = ctx.getSource().getSender();
    String name = StringArgumentType.getString(ctx, "name");
    Optional<Portal> portal = store.getPortal(name);
    if (portal.isEmpty()) {
      error(sender, "No portal named '" + name + "'.");
      return 0;
    }
    double seconds = DoubleArgumentType.getDouble(ctx, "seconds");
    store.putPortal(portal.get().withCost(seconds));
    sender.sendMessage(Component.text(
        "Portal '" + name + "' now costs " + seconds + "s.", NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  private static int list(CommandContext<CommandSourceStack> ctx, WarpStore store) {
    CommandSender sender = ctx.getSource().getSender();
    sender.sendMessage(Component.text("Destinations:", NamedTextColor.AQUA));
    for (Destination d : store.destinations()) {
      sender.sendMessage(Component.text("  " + d.name() + " → " + d.world(), NamedTextColor.GRAY));
    }
    sender.sendMessage(Component.text("Warps (/warp):", NamedTextColor.AQUA));
    for (Warp w : store.warps()) {
      sender.sendMessage(Component.text(
          "  " + w.name() + " → " + w.world() + " (" + w.cost() + "s)", NamedTextColor.GRAY));
    }
    sender.sendMessage(Component.text("Portals (pads):", NamedTextColor.AQUA));
    for (Portal p : store.portals()) {
      sender.sendMessage(Component.text(
          "  " + p.name() + " → destination '" + p.destination() + "' (" + p.cost() + "s)", NamedTextColor.GRAY));
    }
    return Command.SINGLE_SUCCESS;
  }

  private static int clearSelection(CommandContext<CommandSourceStack> ctx, Selections selections) {
    Player player = requirePlayer(ctx);
    if (player == null) {
      return 0;
    }
    selections.clear(player.getUniqueId());
    player.sendMessage(Component.text("Selection cleared.", NamedTextColor.GREEN));
    return Command.SINGLE_SUCCESS;
  }

  // ---- helpers ----

  private static CompletableFuture<Suggestions> suggest(
      SuggestionsBuilder builder, java.util.stream.Stream<String> names) {
    String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
    names.filter(name -> name.startsWith(prefix)).forEach(builder::suggest);
    return builder.buildFuture();
  }

  private static Player requirePlayer(CommandContext<CommandSourceStack> ctx) {
    if (ctx.getSource().getSender() instanceof Player player) {
      return player;
    }
    ctx.getSource().getSender().sendMessage(
        Component.text("Only players can run this command.", NamedTextColor.RED));
    return null;
  }

  private static void error(CommandSender sender, String message) {
    sender.sendMessage(Component.text(message, NamedTextColor.RED));
  }
}
