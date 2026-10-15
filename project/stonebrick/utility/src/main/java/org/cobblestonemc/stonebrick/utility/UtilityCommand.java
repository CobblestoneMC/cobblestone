/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.utility;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.Cell;
import org.cobblestonemc.stonebrick.bench.NeededChunks;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.cobblestonemc.stonebrick.format.CorpusLayout;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code /stonebrick} command tree, also {@code /sb}.
 *
 * <p>Named for what it does rather than for Cobblestone: capturing world data into a benchmark
 * format has nothing to do with navigation, and a developer running this on a test server should
 * not have to guess which plugin owns it.
 */
final class UtilityCommand {

  /** The permission every subcommand requires. */
  static final String PERMISSION = "stonebrick.utility";

  /**
   * The chunk count a capture may reach before {@code --force} is needed.
   *
   * <p>Not a performance guard — the job is rate-limited either way — but a typo guard. A mistaken
   * order of magnitude in one coordinate is the difference between a 2 000-file capture and a 200
   * 000-file one, and the second is discovered when the working tree is already ruined.
   */
  private static final int MAX_CHUNKS_WITHOUT_FORCE = 100_000;

  private final Plugin plugin;
  private final UtilityState state;
  private final ScenarioChecker checker;

  UtilityCommand(Plugin plugin, UtilityState state, ScenarioChecker checker) {
    this.plugin = plugin;
    this.state = state;
    this.checker = checker;
  }

  /**
   * Builds the command tree.
   *
   * @return the root node
   */
  LiteralCommandNode<CommandSourceStack> build() {
    return Commands.literal("stonebrick")
        .requires(source -> source.getSender().hasPermission(PERMISSION))
        .executes(ctx -> help(ctx.getSource().getSender()))
        .then(Commands.literal("help").executes(ctx -> help(ctx.getSource().getSender())))
        .then(rectangleCommand("copy", false))
        .then(rectangleCommand("estimate", true))
        .then(
            Commands.literal("mark")
                .then(
                    Commands.argument("scenario", StringArgumentType.word())
                        .then(markEnd("origin"))
                        .then(markEnd("dest"))))
        .then(
            Commands.literal("tag")
                .then(
                    Commands.argument("scenario", StringArgumentType.word())
                        .then(
                            Commands.argument("tags", StringArgumentType.greedyString())
                                .executes(
                                    ctx ->
                                        tag(
                                            ctx.getSource().getSender(),
                                            scenarioArg(ctx),
                                            StringArgumentType.getString(ctx, "tags"))))))
        .then(
            Commands.literal("check")
                .then(
                    Commands.argument("scenario", StringArgumentType.word())
                        .executes(
                            ctx -> check(ctx.getSource().getSender(), scenarioArg(ctx), false))
                        .then(
                            Commands.literal("--generate")
                                .executes(
                                    ctx ->
                                        check(
                                            ctx.getSource().getSender(), scenarioArg(ctx), true)))))
        .then(
            Commands.literal("probe")
                .then(
                    Commands.argument("x", IntegerArgumentType.integer())
                        .then(
                            Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> probe(ctx, null))
                                .then(
                                    Commands.argument("world", StringArgumentType.greedyString())
                                        .executes(
                                            ctx ->
                                                probe(
                                                    ctx,
                                                    StringArgumentType.getString(ctx, "world")))))))
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
                                .suggests(UtilityCommand::suggestOptions)
                                .executes(
                                    ctx ->
                                        here(
                                            ctx.getSource().getSender(),
                                            IntegerArgumentType.getInteger(ctx, "radius"),
                                            StringArgumentType.getString(ctx, "options"),
                                            estimateOnly)))))
        .then(
            Commands.literal("capsule")
                .then(
                    Commands.argument("x1", IntegerArgumentType.integer())
                        .then(
                            Commands.argument("z1", IntegerArgumentType.integer())
                                .then(
                                    Commands.argument("x2", IntegerArgumentType.integer())
                                        .then(
                                            Commands.argument("z2", IntegerArgumentType.integer())
                                                .then(
                                                    Commands.argument(
                                                            "radius",
                                                            IntegerArgumentType.integer(0, 4096))
                                                        .executes(
                                                            ctx -> capsule(ctx, "", estimateOnly))
                                                        .then(
                                                            Commands.argument(
                                                                    "options",
                                                                    StringArgumentType
                                                                        .greedyString())
                                                                .suggests(
                                                                    UtilityCommand::suggestOptions)
                                                                .executes(
                                                                    ctx ->
                                                                        capsule(
                                                                            ctx,
                                                                            StringArgumentType
                                                                                .getString(
                                                                                    ctx, "options"),
                                                                            estimateOnly)))))))))
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
                                                .suggests(UtilityCommand::suggestOptions)
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
          "--force", "waive the chunk-count guard",
          "--generate", "generate chunks that do not exist yet (seeded corpus worlds only)");

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

  /**
   * Captures every chunk within {@code radius} of the segment from one point to the other.
   *
   * <p>The shape {@code needed-chunks.yml} records, taken directly rather than squared off: the
   * bounding box of a long diagonal capsule holds several times as many chunks as the capsule, and
   * a capture tool asked for a corridor should not quietly take a field.
   */
  private int capsule(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
      String rawOptions,
      boolean estimateOnly) {
    int x1 = IntegerArgumentType.getInteger(ctx, "x1");
    int z1 = IntegerArgumentType.getInteger(ctx, "z1");
    int x2 = IntegerArgumentType.getInteger(ctx, "x2");
    int z2 = IntegerArgumentType.getInteger(ctx, "z2");
    int radius = IntegerArgumentType.getInteger(ctx, "radius");
    return run(
        ctx.getSource().getSender(),
        rawOptions,
        estimateOnly,
        Math.min(x1, x2) - radius,
        Math.min(z1, z2) - radius,
        Math.max(x1, x2) + radius,
        Math.max(z1, z2) + radius,
        (x, z) -> Capsule.contains(x1, z1, x2, z2, radius, x, z));
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
    return run(sender, rawOptions, estimateOnly, x1, z1, x2, z2, (x, z) -> true);
  }

  private int run(
      CommandSender sender,
      String rawOptions,
      boolean estimateOnly,
      int x1,
      int z1,
      int x2,
      int z2,
      CaptureJob.ChunkFilter filter) {
    CaptureOptions options;
    try {
      options = CaptureOptions.parse(rawOptions, state.defaultCaptureName());
    } catch (CaptureOptions.ParseException e) {
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
            options.generate(),
            minChunkX,
            minChunkZ,
            maxChunkX,
            maxChunkZ,
            filter,
            report);
    state.start(job);
    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
  }

  /**
   * The {@code origin} or {@code dest} branch of {@code mark}: where the player stands, or given
   * coordinates, which is how the console marks. A Y of {@code surface} stands on the ground there.
   */
  private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> markEnd(
      String which) {
    return Commands.literal(which)
        .executes(ctx -> markHere(ctx.getSource().getSender(), scenarioArg(ctx), which))
        .then(
            Commands.argument("x", IntegerArgumentType.integer())
                .then(
                    Commands.argument("y", StringArgumentType.word())
                        .suggests(
                            (ctx, builder) -> {
                              if ("surface".startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest("surface");
                              }
                              return builder.buildFuture();
                            })
                        .then(
                            Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> markAt(ctx, which, null))
                                .then(
                                    Commands.argument("world", StringArgumentType.greedyString())
                                        .executes(
                                            ctx ->
                                                markAt(
                                                    ctx,
                                                    which,
                                                    StringArgumentType.getString(
                                                        ctx, "world")))))));
  }

  private static String scenarioArg(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
    return StringArgumentType.getString(ctx, "scenario");
  }

  private int markHere(CommandSender sender, String scenario, String which) {
    if (!(sender instanceof Player player)) {
      return fail(
          sender,
          "From the console, give coordinates: mark <scenario> "
              + which
              + " <x> <y|surface> <z> [world]");
    }
    Location at = player.getLocation();
    return mark(
        sender, scenario, which, at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
  }

  private int markAt(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
      String which,
      @Nullable String worldKey) {
    CommandSender sender = ctx.getSource().getSender();
    World world = resolveWorld(sender, worldKey);
    if (world == null) {
      return fail(
          sender, "Name a world: mark <scenario> " + which + " <x> <y|surface> <z> <world>");
    }
    int x = IntegerArgumentType.getInteger(ctx, "x");
    int z = IntegerArgumentType.getInteger(ctx, "z");
    String rawY = StringArgumentType.getString(ctx, "y");
    int y;
    if (rawY.equalsIgnoreCase("surface")) {
      y = Endpoint.standingY(world, x, z);
    } else {
      try {
        y = Integer.parseInt(rawY);
      } catch (NumberFormatException e) {
        return fail(sender, "Y is a number or 'surface', not '" + rawY + "'.");
      }
    }
    return mark(sender, scenarioArg(ctx), which, world, x, y, z);
  }

  /**
   * Records one end of a scenario and says what is there.
   *
   * <p>Once both ends are marked it also says how far apart they are and how much terrain that
   * means capturing, and tags a scenario that has no tags yet by where its ends are, since the tags
   * decide which loadouts the bench runs on it.
   */
  private int mark(
      CommandSender sender, String scenario, String which, World world, int x, int y, int z) {
    Path file = scenariosFile();
    String worldKey = world.getKey().asString();
    Consumer<String> report = reporter(sender);
    String otherEnd = which.equals("origin") ? "dest" : "origin";
    try {
      boolean created = ScenarioFile.mark(file, scenario, which, worldKey, x, y, z);
      report.accept(
          "%s %s of '%s' in %s"
              .formatted(created ? "Created" : "Marked", which, scenario, worldKey));
      Endpoint end = Endpoint.at(world, x, y, z);
      end.describe(which).forEach(report);

      int[] other = ScenarioFile.position(file, worldKey, scenario, otherEnd);
      if (other == null) {
        report.accept("  Now mark the " + otherEnd + ".");
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
      }
      Endpoint far = Endpoint.at(world, other[0], other[1], other[2]);
      double dx = far.x() - x;
      double dy = far.y() - y;
      double dz = far.z() - z;
      int chunks =
          new NeededChunks(
                  worldKey,
                  new Cell(x, y, z),
                  new Cell(far.x(), far.y(), far.z()),
                  NeededChunks.INITIAL_RADIUS)
              .chunkCount();
      report.accept(
          "  %.0f blocks apart (%.0f across, %+.0f up); about %,d chunks to capture to start with"
              .formatted(
                  Math.sqrt(dx * dx + dy * dy + dz * dz),
                  Math.sqrt(dx * dx + dz * dz),
                  which.equals("origin") ? dy : -dy,
                  chunks));
      if (!ScenarioFile.hasTags(file, worldKey, scenario)) {
        String tag =
            end.suggestedTag().equals("underground") || far.suggestedTag().equals("underground")
                ? "underground"
                : end.suggestedTag();
        ScenarioFile.tag(file, worldKey, scenario, List.of(tag));
        report.accept(
            "  Tagged [%s]; change it with /stonebrick tag %s <tags>, e.g. to add no-pickaxe."
                .formatted(tag, scenario));
      }
      report.accept("  Next: /stonebrick check " + scenario);
    } catch (IOException e) {
      return fail(sender, "Could not write the mark: " + e.getMessage());
    }
    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
  }

  private int tag(CommandSender sender, String scenario, String raw) {
    Path file = scenariosFile();
    List<String> tags = Arrays.stream(raw.split("[,\\s]+")).filter(t -> !t.isBlank()).toList();
    try {
      String world = ScenarioFile.worldOf(file, scenario);
      if (world == null) {
        return fail(sender, "No scenario '" + scenario + "'; mark it first.");
      }
      ScenarioFile.tag(file, world, scenario, tags);
      reporter(sender).accept("Tagged '%s' %s".formatted(scenario, tags));
    } catch (IOException e) {
      return fail(sender, "Could not tag it: " + e.getMessage());
    }
    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
  }

  private int check(CommandSender sender, String scenario, boolean generate) {
    return checker.start(scenario, generate, reporter(sender))
        ? com.mojang.brigadier.Command.SINGLE_SUCCESS
        : 0;
  }

  /** Says where a body would stand in a column, and what is there: for picking ends from afar. */
  private int probe(
      com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, @Nullable String key) {
    CommandSender sender = ctx.getSource().getSender();
    World world = resolveWorld(sender, key);
    if (world == null) {
      return fail(sender, "Name a world: probe <x> <z> <world>");
    }
    int x = IntegerArgumentType.getInteger(ctx, "x");
    int z = IntegerArgumentType.getInteger(ctx, "z");
    int y = Endpoint.standingY(world, x, z);
    Endpoint.at(world, x, y, z).describe("standing at").forEach(reporter(sender));
    return com.mojang.brigadier.Command.SINGLE_SUCCESS;
  }

  private Path scenariosFile() {
    return CorpusLayout.at(state.outputRoot()).root().resolve(ScenarioFile.FILE_NAME);
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
    sender.sendMessage(Component.text("/stonebrick copy <x1> <z1> <x2> <z2> [options]"));
    sender.sendMessage(Component.text("/stonebrick copy here <radiusChunks> [options]"));
    sender.sendMessage(
        Component.text("/stonebrick estimate ...: same arguments, captures nothing"));
    sender.sendMessage(
        Component.text("/stonebrick mark <scenario> origin|dest [<x> <y|surface> <z> [world]]"));
    sender.sendMessage(Component.text("/stonebrick tag <scenario> <tags...>"));
    sender.sendMessage(
        Component.text("/stonebrick check <scenario> [--generate]: can the bench use it?"));
    sender.sendMessage(Component.text("/stonebrick probe <x> <z> [world]: where would you stand?"));
    sender.sendMessage(Component.text("/stonebrick traits [capture]"));
    sender.sendMessage(Component.text("/stonebrick status | /stonebrick cancel"));
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
