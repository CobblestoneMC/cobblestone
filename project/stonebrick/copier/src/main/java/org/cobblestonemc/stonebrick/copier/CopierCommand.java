/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code /copier} command tree.
 *
 * <p>Named for what it does rather than for Cobblestone: capturing world data into a benchmark
 * format has nothing to do with navigation, and a developer running this on a test server should
 * not have to guess which plugin owns it.
 */
final class CopierCommand {

  /** The permission every subcommand requires. */
  static final String PERMISSION = "stonebrick.copier";

  /**
   * The chunk count a capture may reach before {@code --force} is needed.
   *
   * <p>Not a performance guard — the job is rate-limited either way — but a typo guard. A mistaken
   * order of magnitude in one coordinate is the difference between a 2 000-file capture and a 200
   * 000-file one, and the second is discovered when the working tree is already ruined.
   */
  private static final int MAX_CHUNKS_WITHOUT_FORCE = 100_000;

  private final Plugin plugin;
  private final CopierState state;

  CopierCommand(Plugin plugin, CopierState state) {
    this.plugin = plugin;
    this.state = state;
  }

  /**
   * Builds the command tree.
   *
   * @return the root node
   */
  LiteralCommandNode<CommandSourceStack> build() {
    return Commands.literal("copier")
        .requires(source -> source.getSender().hasPermission(PERMISSION))
        .executes(ctx -> help(ctx.getSource().getSender()))
        .then(Commands.literal("help").executes(ctx -> help(ctx.getSource().getSender())))
        .then(rectangleCommand("copy", false))
        .then(rectangleCommand("estimate", true))
        .then(
            Commands.literal("mark")
                .then(
                    Commands.argument("scenario", StringArgumentType.word())
                        .then(
                            Commands.literal("origin")
                                .executes(
                                    ctx ->
                                        mark(
                                            ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "scenario"),
                                            "origin")))
                        .then(
                            Commands.literal("dest")
                                .executes(
                                    ctx ->
                                        mark(
                                            ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "scenario"),
                                            "dest")))))
        .then(
            Commands.literal("traits")
                .executes(ctx -> traits(ctx.getSource().getSender(), null))
                .then(
                    Commands.argument("capture", StringArgumentType.word())
                        .executes(
                            ctx ->
                                traits(
                                    ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, "capture")))))
        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource().getSender())))
        .then(Commands.literal("cancel").executes(ctx -> cancel(ctx.getSource().getSender())))
        .build();
  }

  private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> rectangleCommand(
      String literal, boolean estimateOnly) {
    return Commands.literal(literal)
        .then(
            Commands.literal("here")
                .then(
                    Commands.argument("radius", IntegerArgumentType.integer(0, 2048))
                        .executes(
                            ctx ->
                                here(
                                    ctx.getSource().getSender(),
                                    IntegerArgumentType.getInteger(ctx, "radius"),
                                    "",
                                    estimateOnly))
                        .then(
                            Commands.argument("options", StringArgumentType.greedyString())
                                .suggests(CopierCommand::suggestOptions)
                                .executes(
                                    ctx ->
                                        here(
                                            ctx.getSource().getSender(),
                                            IntegerArgumentType.getInteger(ctx, "radius"),
                                            StringArgumentType.getString(ctx, "options"),
                                            estimateOnly)))))
        .then(
            Commands.argument("x1", IntegerArgumentType.integer())
                .then(
                    Commands.argument("z1", IntegerArgumentType.integer())
                        .then(
                            Commands.argument("x2", IntegerArgumentType.integer())
                                .then(
                                    Commands.argument("z2", IntegerArgumentType.integer())
                                        .executes(ctx -> rectangle(ctx, "", estimateOnly))
                                        .then(
                                            Commands.argument(
                                                    "options", StringArgumentType.greedyString())
                                                .suggests(CopierCommand::suggestOptions)
                                                .executes(
                                                    ctx ->
                                                        rectangle(
                                                            ctx,
                                                            StringArgumentType.getString(
                                                                ctx, "options"),
                                                            estimateOnly)))))));
  }

  /**
   * The flags {@code copy} and {@code estimate} accept, with a one-line hint each.
   *
   * <p>Suggested rather than modelled as literal nodes: the vertical modes are mutually exclusive
   * and the rest are free to combine, so a literal tree would need a branch per legal combination
   * for a tool two people use. A suggestion list gets the discoverability without the thicket —
   * which is the part that was actually missing, since a greedy string offers nothing at all.
   */
  private static final java.util.Map<String, String> OPTIONS =
      java.util.Map.of(
          "--name", "capture directory to write into",
          "--world", "world key to capture, instead of yours",
          "--blocks", "coordinates are block, not chunk",
          "--surface", "<below> <above> band following the terrain",
          "--y", "<minY> <maxY> fixed vertical band",
          "--full", "the whole column",
          "--overwrite", "write into a capture that already has this world",
          "--force", "waive the chunk-count guard");

  private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
      suggestOptions(
          com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
          com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
    // The greedy string hands us everything typed so far, so suggest against the last word only.
    String remaining = builder.getRemaining();
    int lastSpace = remaining.lastIndexOf(' ');
    String word = lastSpace < 0 ? remaining : remaining.substring(lastSpace + 1);
    com.mojang.brigadier.suggestion.SuggestionsBuilder offset =
        builder.createOffset(builder.getStart() + lastSpace + 1);
    OPTIONS.entrySet().stream()
        .filter(entry -> entry.getKey().startsWith(word))
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(
            entry ->
                offset.suggest(
                    entry.getKey(),
                    io.papermc.paper.command.brigadier.MessageComponentSerializer.message()
                        .serialize(Component.text(entry.getValue()))));
    return offset.buildFuture();
  }

  private int rectangle(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
      String rawOptions,
      boolean estimateOnly) {
    return run(
        ctx.getSource().getSender(),
        rawOptions,
        estimateOnly,
        IntegerArgumentType.getInteger(ctx, "x1"),
        IntegerArgumentType.getInteger(ctx, "z1"),
        IntegerArgumentType.getInteger(ctx, "x2"),
        IntegerArgumentType.getInteger(ctx, "z2"));
  }

  private int here(CommandSender sender, int radius, String rawOptions, boolean estimateOnly) {
    if (!(sender instanceof Player player)) {
      return fail(sender, "`here` needs a player; give explicit coordinates from the console.");
    }
    int chunkX = player.getLocation().getBlockX() >> 4;
    int chunkZ = player.getLocation().getBlockZ() >> 4;
    return run(
        sender,
        rawOptions,
        estimateOnly,
        chunkX - radius,
        chunkZ - radius,
        chunkX + radius,
        chunkZ + radius);
  }

  private int run(
      CommandSender sender,
      String rawOptions,
      boolean estimateOnly,
      int x1,
      int z1,
      int x2,
      int z2) {
    CopierOptions options;
    try {
      options = CopierOptions.parse(rawOptions, state.defaultCaptureName());
    } catch (CopierOptions.ParseException e) {
      return fail(sender, e.getMessage());
    }

    World world = resolveWorld(sender, options.worldKey());
    if (world == null) {
      return fail(
          sender,
          options.worldKey() == null
              ? "Run this as a player, or name a world with --world."
              : "No world with key '" + options.worldKey() + "'.");
    }

    // Chunk coordinates by default; --blocks accepts block coordinates and rounds outward so the
    // named blocks are always inside the capture rather than a rounding away from its edge.
    int minChunkX = options.blockCoordinates() ? Math.min(x1, x2) >> 4 : Math.min(x1, x2);
    int minChunkZ = options.blockCoordinates() ? Math.min(z1, z2) >> 4 : Math.min(z1, z2);
    int maxChunkX = options.blockCoordinates() ? Math.max(x1, x2) >> 4 : Math.max(x1, x2);
    int maxChunkZ = options.blockCoordinates() ? Math.max(z1, z2) >> 4 : Math.max(z1, z2);

    long chunks = (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
    if (chunks > MAX_CHUNKS_WITHOUT_FORCE && !options.force()) {
      return fail(
          sender,
          "That region is %,d chunks. If you meant it, add --force; if you gave block coordinates, add --blocks."
              .formatted(chunks));
    }

    CaptureDirectory capture = state.capture(options.captureName());
    Consumer<String> report = reporter(sender);

    if (estimateOnly) {
      CaptureEstimate.run(
          plugin,
          world,
          capture,
          options.vertical(),
          minChunkX,
          minChunkZ,
          maxChunkX,
          maxChunkZ,
          report);
      return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    if (state.hasActiveJob()) {
      return fail(sender, "A capture is already running. " + state.activeStatus());
    }
    if (!options.overwrite() && Files.isDirectory(capture.world(world.getKey().asString()))) {
      return fail(
          sender,
          "Capture '%s' already holds columns for %s. Add --overwrite to capture into it again."
              .formatted(options.captureName(), world.getKey()));
    }

    CaptureJob job =
        new CaptureJob(
            plugin,
            world,
            capture,
            options.vertical(),
            minChunkX,
            minChunkZ,
            maxChunkX,
            maxChunkZ,
            report);
    state.start(job);
    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
  }

  private int mark(CommandSender sender, String scenario, String which) {
    if (!(sender instanceof Player player)) {
      return fail(sender, "`mark` records where you are standing, so it needs a player.");
    }
    Location at = player.getLocation();
    try {
      Path path =
          org.cobblestonemc.stonebrick.format.CorpusLayout.at(state.outputRoot()).scenarios();
      Files.createDirectories(path);
      Path file = path.resolve(scenario + "." + which + ".txt");
      Files.writeString(
          file,
          "world\t%s\nx\t%d\ny\t%d\nz\t%d\ncanFly\t%s\n"
              .formatted(
                  at.getWorld().getKey().asString(),
                  at.getBlockX(),
                  at.getBlockY(),
                  at.getBlockZ(),
                  player.getAllowFlight()));
      sender.sendMessage(
          Component.text(
              "Marked %s of '%s' at %d, %d, %d in %s"
                  .formatted(
                      which,
                      scenario,
                      at.getBlockX(),
                      at.getBlockY(),
                      at.getBlockZ(),
                      at.getWorld().getKey())));
      sender.sendMessage(Component.text("  " + file));
    } catch (IOException e) {
      return fail(sender, "Could not write the mark: " + e.getMessage());
    }
    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
  }

  private int traits(CommandSender sender, @Nullable String captureName) {
    CaptureDirectory capture =
        state.capture(captureName == null ? state.defaultCaptureName() : captureName);
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              try {
                int written = TraitDump.writeAll(capture);
                reporter(sender)
                    .accept(
                        "Wrote traits for %,d block states to %s"
                            .formatted(written, capture.traitTable()));
              } catch (IOException e) {
                reporter(sender).accept("Could not write the trait table: " + e);
              }
            });
    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
  }

  private int status(CommandSender sender) {
    sender.sendMessage(Component.text(state.activeStatus()));
    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
  }

  private int cancel(CommandSender sender) {
    if (!state.hasActiveJob()) {
      return fail(sender, "No capture is running.");
    }
    state.cancel();
    sender.sendMessage(Component.text("Cancelling; chunks already loaded will still be written."));
    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
  }

  /**
   * Sends a line to whoever asked, and to the console.
   *
   * <p>Both, always. A capture report is the only record of what a long job did, and chat scrolls
   * away, cannot be copied out of the client, and is gone entirely if the player logs off midway.
   * The console log is the one that can be pasted into a bug report.
   */
  private Consumer<String> reporter(CommandSender sender) {
    return line -> {
      plugin.getLogger().info(line);
      if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
        sender.sendMessage(Component.text(line));
      }
    };
  }

  private static @Nullable World resolveWorld(CommandSender sender, @Nullable String key) {
    if (key != null) {
      NamespacedKey parsed = NamespacedKey.fromString(key);
      return parsed == null ? null : Bukkit.getWorld(parsed);
    }
    return sender instanceof Player player ? player.getWorld() : null;
  }

  private int help(CommandSender sender) {
    sender.sendMessage(Component.text("/copier copy <x1> <z1> <x2> <z2> [options]"));
    sender.sendMessage(Component.text("/copier copy here <radiusChunks> [options]"));
    sender.sendMessage(Component.text("/copier estimate … — same arguments, captures nothing"));
    sender.sendMessage(Component.text("/copier mark <scenario> origin|dest"));
    sender.sendMessage(Component.text("/copier traits [capture]"));
    sender.sendMessage(Component.text("/copier status | /copier cancel"));
    sender.sendMessage(
        Component.text("options: --name <capture> --world <key> --blocks --overwrite --force"));
    sender.sendMessage(
        Component.text("         --surface <below> <above> | --y <minY> <maxY> | --full"));
    sender.sendMessage(
        Component.text("Coordinates are chunk coordinates unless you pass --blocks."));
    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
  }

  private static int fail(CommandSender sender, String message) {
    sender.sendMessage(Component.text(message));
    return 0;
  }
}
