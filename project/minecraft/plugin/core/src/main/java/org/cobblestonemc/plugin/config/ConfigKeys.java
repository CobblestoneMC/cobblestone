/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.config;

import java.util.List;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.plugin.data.DataBackend;

/**
 * Cobblestone's registered configuration parameters, grouped to mirror the generated {@code
 * config.yml}.
 *
 * <p>This class is the single source of truth for the config: every key's default value, its
 * documentation, and the values it accepts are declared here, and the file an admin edits is
 * rendered from these registrations on first run. Adding a setting means adding it here and nowhere
 * else.
 *
 * <p>Settings that differ between platforms take their default, accepted values, and prose from the
 * {@link ConfigPlatform} passed in. Settings that exist on only one platform are registered by that
 * platform's own plugin against the same {@link ConfigManager}.
 *
 * <p>Hold an instance for the lifetime of the plugin: construct it once with the plugin's {@link
 * ConfigManager} (which registers every key), then read keys via {@code manager.get(...)}.
 */
public final class ConfigKeys {

  /** The locale used for console/system messages (BCP-47 language tag). Immutable. */
  public final ConfigKey<String> localeDefault;

  /** Whether the {@code [✦]} prefix badge precedes every player message. Mutable. */
  public final ConfigKey<Boolean> messagesShowPrefix;

  /** The persistence backend. Changing it requires a restart. */
  public final ConfigKey<DataBackend> dataBackend;

  /**
   * The database file name within the plugin's data folder (embedded backends only). Requires a
   * restart.
   */
  public final ConfigKey<String> dataFile;

  /** How many trips (guided paths) one player may run at once. Mutable. */
  public final ConfigKey<Integer> tripsMaxActivePerPlayer;

  /** How often, in server ticks, a live trip re-runs its search and hot-swaps the path. Mutable. */
  public final ConfigKey<Integer> tripsLiveIntervalTicks;

  /** Blocks a player may stray from the trail before the trip quietly recalculates. Mutable. */
  public final ConfigKey<Integer> tripsRecalculateDistance;

  /** The most concurrent searches (manual + live) one player may run. Requires a restart. */
  public final ConfigKey<Integer> searchMaxConcurrentPerPlayer;

  /** Cap on cells an A* solve may visit before giving up ({@code LIMIT_EXCEEDED}). Mutable. */
  public final ConfigKey<Integer> algorithmMaxCellsVisited;

  /** Wall-clock budget for a whole search, in seconds. Mutable. */
  public final ConfigKey<Long> algorithmMaxWallClockSeconds;

  /** Pessimism factor on an unsolved Tier-1 leg's cost estimate. Mutable. */
  public final ConfigKey<Double> algorithmTier1UnsolvedPessimism;

  /** Window width for the running-average heuristic. Mutable. */
  public final ConfigKey<Integer> algorithmRunningAverageWidth;

  /**
   * A* heuristic weight (1.0 = optimal; &gt;1 = faster weighted A*, slightly sub-optimal). Mutable.
   */
  public final ConfigKey<Double> algorithmHeuristicWeight;

  /**
   * How far Cobblestone may go to obtain a chunk a search wants to walk through. The accepted
   * values and the default are platform-specific; see {@link ConfigPlatform}. Requires a restart —
   * the policy is captured in the chunk provider's settings when the platform API is built.
   */
  public final ConfigKey<ChunkLoadPolicy> chunksPolicy;

  /** How many chunks one search may keep in hand at a time. Requires a restart. */
  public final ConfigKey<Integer> chunksCacheSize;

  /** How far ahead of itself a search reads chunks, in blocks. Requires a restart. */
  public final ConfigKey<Integer> chunksPrefetchDistance;

  /**
   * How many transient failures one search tolerates for a chunk before treating it as a wall.
   * Requires a restart.
   */
  public final ConfigKey<Integer> chunksFetchAttempts;

  /** Whether Cobblestone discovers vanilla portal links by watching players teleport. Mutable. */
  public final ConfigKey<Boolean> portalsDiscovery;

  /** The traversal cost, in seconds, assigned to a discovered portal transition. Mutable. */
  public final ConfigKey<Double> portalsCostSeconds;

  /**
   * Whether to snap a nether portal ENTRY to the source portal's center, so one source portal
   * always reaches the same destination portal (vanilla otherwise picks the destination from your
   * exact entry sub-block, which Cobblestone cannot route to precisely). On by default:
   * Cobblestone's single source&nbsp;&rarr;&nbsp;destination link relies on it, and for the usual
   * one-portal-per-side setup it lands where vanilla would anyway. Mutable.
   */
  public final ConfigKey<Boolean> portalsNormalizeEntry;

  /**
   * Whether to snap a nether portal EXIT to the destination portal's center at ground level. Only
   * changes where within the (same) destination portal you land, so it is on by default. Mutable.
   */
  public final ConfigKey<Boolean> portalsNormalizeExit;

  /**
   * Whether each player's last death location is recorded and offered as the {@code cobblestone
   * death} destination. Mutable — turning it off stops recording and hides the destination, but
   * keeps what is already stored.
   */
  public final ConfigKey<Boolean> deathsTrack;

  /** Whether anonymous bStats metrics are reported. Requires a restart. */
  public final ConfigKey<Boolean> metricsEnabled;

  /** How many cells of the trail ahead the {@code trail} navigator renders. Mutable. */
  public final ConfigKey<Integer> trailBufferCells;

  /** Trail particle types (platform particle names); one is chosen at random per particle. */
  public final ConfigKey<List<String>> trailParticles;

  /** Highlight particle types, drawn at points of interest along the trail. */
  public final ConfigKey<List<String>> trailHighlightParticles;

  /** Trail dust colors as hex {@code RRGGBB}; one is chosen at random for each dust particle. */
  public final ConfigKey<List<String>> trailColors;

  /** Average particles per trail cell each tick; may be fractional (probabilistic). Mutable. */
  public final ConfigKey<Double> trailDensity;

  /**
   * Registers every shared key on the given manager.
   *
   * @param manager the config manager to populate (before {@link ConfigManager#load()})
   * @param platform the platform whose defaults, accepted values, and prose to use
   */
  public ConfigKeys(ConfigManager manager, ConfigPlatform platform) {
    manager.header(
        """
        ============================================================================================
         Cobblestone — configuration

         Cobblestone is a Minecraft navigation plugin. This file controls its behavior.

         This file was generated for %s. Settings differ slightly between server platforms, so a
         config copied from another platform may name settings this one does not support; Cobblestone
         warns about those on startup and keeps its own defaults.

         Reloading: run "/cobblestone reload" to re-read this file. Most values apply immediately.
         Values marked "(requires restart)" are only applied on a full server restart; changing one
         and reloading will warn you in the console and keep the old value until you restart.
        ============================================================================================"""
            .formatted(platform.name()));

    manager.section("locale", "Localization settings.");
    this.localeDefault =
        manager
            .key("locale.default", "en", Codec.ofString())
            .comment(
                """
                Language for console and system messages, as a BCP-47 tag (e.g. "en", "en-US",
                "fr"). Player-facing messages prefer each player's own client language when a
                translation exists, and fall back to this.""")
            .requiresRestart()
            .register();

    // Logging verbosity is deliberately not a config key: it is a debugging dial, not a
    // deployment setting. It lives in memory (defaulting to INFO) and is turned with
    // `/cobblestone loglevel`, so an admin can raise it mid-incident and have it fall back
    // on restart rather than leaving a server running at trace forever.

    manager.section("messages", "How Cobblestone's chat messages look.");
    this.messagesShowPrefix =
        manager
            .key("messages.show_prefix", true, Codec.ofBoolean())
            .comment(
                "Whether to prepend the \"[✦]\" badge to every message Cobblestone sends to a player.")
            .mutable()
            .register();

    manager.section(
        "data", "Where Cobblestone stores its data (locations, portals, segments, preferences).");
    this.dataBackend =
        manager
            .key("data.backend", DataBackend.H2, Codec.ofEnum(DataBackend.class))
            .comment("The storage backend.")
            .permitted(List.of(DataBackend.values()))
            .requiresRestart()
            .register();
    this.dataFile =
        manager
            .key("data.file", "cobblestone", Codec.ofString())
            .comment(
                """
                The database file name inside this plugin folder, without an extension (the engine
                adds its own). Only used by the embedded backends.""")
            .requiresRestart()
            .register();

    manager.section("trips", "Guided journeys (\"trips\") that follow a computed path.");
    this.tripsMaxActivePerPlayer =
        manager
            .key("trips.max_active_per_player", 3, Codec.ofInt())
            .comment(
                """
                How many trips one player may run at once, so "path home + path to the caves" both
                render. Set to 1 to allow only a single trip per player.""")
            .mutable()
            .register();
    this.tripsLiveIntervalTicks =
        manager
            .key("trips.live_interval_ticks", 100, Codec.ofInt())
            .comment(
                "How often, in server ticks, a \"-live\" trip re-runs its search and updates the"
                    + " path.")
            .mutable()
            .register();
    this.tripsRecalculateDistance =
        manager
            .key("trips.recalculate_distance", 32, Codec.ofInt())
            .comment(
                "How far (blocks) a player may stray from the trail before the trip quietly"
                    + " recalculates.")
            .mutable()
            .register();

    manager.section("search", "Search behavior.");
    this.searchMaxConcurrentPerPlayer =
        manager
            .key("search.max_concurrent_per_player", 1, Codec.ofInt())
            .comment(
                """
                The most searches (manual and live re-searches together) one player may run at
                once. Live re-searches yield to this budget; a manual /navigate always runs.""")
            .requiresRestart()
            .register();

    manager.section(
        "search.algorithm",
        """
        A* tuning. Raise the limits if searches to distant destinations give up ("limit exceeded" /
        "took too long"), at the cost of more CPU/memory per search. Lower them for weak
        hardware.""");
    this.algorithmMaxCellsVisited =
        manager
            .key(
                "search.algorithm.max_cells_visited",
                SearchSettings.DEFAULT_MAX_CELLS_VISITED,
                Codec.ofInt())
            .comment(
                """
                Most cells a single A* solve may visit before giving up. This is the memory guard —
                a solve holds a few hundred bytes per cell it reaches — so lower it on a small
                heap.""")
            .mutable()
            .register();
    this.algorithmMaxWallClockSeconds =
        manager
            .key(
                "search.algorithm.max_wall_clock_seconds",
                SearchSettings.DEFAULT_MAX_WALL_CLOCK_MILLIS / 1000,
                Codec.ofLong())
            .comment("Wall-clock budget for the whole search, in seconds.")
            .mutable()
            .register();
    this.algorithmTier1UnsolvedPessimism =
        manager
            .key(
                "search.algorithm.tier1_unsolved_pessimism",
                SearchSettings.DEFAULT_TIER1_UNSOLVED_PESSIMISM,
                Codec.ofDouble())
            .comment(
                """
                Extra margin on an unsolved route leg's assumed cost. Cobblestone already corrects
                its estimate using what the legs it has solved in that world really cost, so this
                only covers what that correction has not seen yet; 1.0 leaves it uncorrected.""")
            .mutable()
            .register();
    this.algorithmRunningAverageWidth =
        manager
            .key(
                "search.algorithm.running_average_width",
                SearchSettings.DEFAULT_RUNNING_AVERAGE_WIDTH,
                Codec.ofInt())
            .comment("Window width for the running-average heuristic.")
            .mutable()
            .register();
    this.algorithmHeuristicWeight =
        manager
            .key(
                "search.algorithm.heuristic_weight",
                SearchSettings.DEFAULT_HEURISTIC_WEIGHT,
                Codec.ofDouble())
            .comment(
                """
                A* heuristic weight. 1.0 finds optimal paths but explores a lot; higher is much
                faster and slightly suboptimal (bounded by this factor).""")
            .mutable()
            .register();

    manager.section(
        "search.chunks",
        """
        Where the terrain a search walks through comes from. These are the settings that decide how
        much work Cobblestone may ask the server to do on its behalf.""");
    this.chunksPolicy =
        manager
            .key(
                "search.chunks.policy",
                platform.chunkPolicyDefault(),
                Codec.ofEnum(ChunkLoadPolicy.class))
            .comment(platform.chunkPolicyComment())
            .permitted(platform.chunkPolicies())
            .requiresRestart()
            .register();
    this.chunksCacheSize =
        manager
            .key(
                "search.chunks.cache_size",
                ChunkProviderSettings.DEFAULT_MAX_CACHED_CHUNKS,
                Codec.ofInt())
            .comment(
                """
                How many chunks a single search keeps in hand before it starts forgetting the ones
                it touched longest ago. Each search has its own, so this is not a server-wide
                budget: several players searching at once do not evict each other.

                The point is only to avoid re-reading the same chunk from disk while a search works
                its way across a chunk border, so the useful range is small. A chunk costs roughly
                35 KB, so the default is about a megabyte per running search.""")
            .requiresRestart()
            .register();
    this.chunksPrefetchDistance =
        manager
            .key(
                "search.chunks.prefetch_distance",
                ChunkProviderSettings.DEFAULT_PREFETCH_DISTANCE,
                Codec.ofInt())
            .comment(
                """
                How far ahead of itself, in blocks towards the destination, a search reads chunks
                it has not needed yet. The default is a chunk or two, which covers the moment
                between approaching a chunk border and crossing it.

                Set to 0 to turn read-ahead off entirely, which is worth trying: it earned a long
                reach when a miss meant loading a chunk through the server, and reads now come off
                disk.""")
            .requiresRestart()
            .register();
    this.chunksFetchAttempts =
        manager
            .key(
                "search.chunks.fetch_attempts",
                ChunkProviderSettings.DEFAULT_MAX_FETCH_ATTEMPTS,
                Codec.ofInt())
            .comment(
                """
                How many times a single search tries to get a chunk whose read failed for a reason
                that might pass — a disk read that errored, a load that timed out, a chunk whose
                save had not reached disk yet — before treating it as impassable. Chunks that
                simply cannot be had, such as ones never generated, are not retried at all.""")
            .requiresRestart()
            .register();

    manager.section(
        "portals",
        """
        Vanilla portal links. No API reveals where a portal leads, so Cobblestone learns them by
        watching players travel; use "/cobblestone portals clear" to wipe what it has learned.""");
    this.portalsDiscovery =
        manager
            .key("portals.discovery", true, Codec.ofBoolean())
            .comment("Whether to discover portal links from player teleports.")
            .mutable()
            .register();
    this.portalsCostSeconds =
        manager
            .key("portals.cost_seconds", 5.0, Codec.ofDouble())
            .comment("The time cost, in seconds, of traversing a discovered portal.")
            .mutable()
            .register();
    this.portalsNormalizeEntry =
        manager
            .key("portals.normalize_entry", true, Codec.ofBoolean())
            .comment(
                """
                Snap a nether portal ENTRY to the source portal's center, so one source portal
                always reaches the same destination portal no matter which block you step through
                (vanilla picks the destination from your exact sub-block position, which Cobblestone
                cannot route to precisely). On by default: Cobblestone's single source -> destination
                link relies on it, and for the usual one-portal-per-side setup it lands where
                vanilla would anyway.""")
            .mutable()
            .register();
    this.portalsNormalizeExit =
        manager
            .key("portals.normalize_exit", true, Codec.ofBoolean())
            .comment(
                """
                Snap a nether portal EXIT to the destination portal's center at ground level. Only
                affects where within the (same) destination portal you land, so it is safe to leave
                on.""")
            .mutable()
            .register();

    manager.section(
        "deaths",
        """
        Death locations. Cobblestone can remember where each player last died, so they can walk
        back to their things with "/navigate death".""");
    this.deathsTrack =
        manager
            .key("deaths.track", true, Codec.ofBoolean())
            .comment(
                """
                Whether to record where each player last died and offer it as the "death"
                destination. Turning this off stops recording new deaths and hides the destination
                from /navigate; death locations already stored are kept, and reappear if you turn
                it back on. Players can also be denied the destination individually with the
                permission "cobblestone.navigate.cobblestone.death".""")
            .mutable()
            .register();

    manager.section(
        "metrics",
        """
        Anonymous usage metrics via bStats (https://bstats.org). Aggregate, non-identifying counts
        only. You can also opt out globally in plugins/bStats/config.yml.""");
    this.metricsEnabled =
        manager
            .key("metrics.enabled", true, Codec.ofBoolean())
            .comment("Whether to report anonymous usage metrics.")
            .requiresRestart()
            .register();

    manager.section("navigators", "Display strategies (\"navigators\") that render a trip.");
    manager.section(
        "navigators.trail",
        "The default \"trail\" navigator: a flowing ribbon of dust particles along the path.");
    this.trailBufferCells =
        manager
            .key("navigators.trail.buffer_cells", 256, Codec.ofInt())
            .comment("How many cells of the path ahead of the player to render at a time.")
            .mutable()
            .register();
    this.trailParticles =
        manager
            .key("navigators.trail.particles", List.of("SCRAPE", "WAX_OFF"), Codec.ofStringList())
            .comment(
                """
                Particle types used for the trail; one is picked at random per particle. "DUST" is
                colored by the palette below; other types (e.g. GLOW, END_ROD) are drawn as-is.
                Particles that need extra data beyond a color are not supported.""")
            .mutable()
            .register();
    this.trailHighlightParticles =
        manager
            .key("navigators.trail.highlight_particles", List.of("END_ROD"), Codec.ofStringList())
            .comment("Particle types drawn at highlighted points along the trail.")
            .mutable()
            .register();
    this.trailColors =
        manager
            .key(
                "navigators.trail.colors",
                List.of("55FFFF", "FFAA00", "FFFFFF"),
                Codec.ofStringList())
            .comment(
                """
                Dust colors as hex RRGGBB; one is picked at random for each DUST particle (no
                blending).""")
            .mutable()
            .register();
    this.trailDensity =
        manager
            .key("navigators.trail.density", 0.2, Codec.ofDouble())
            .comment(
                """
                Average particles per cell each tick, scattered with a Gaussian falloff for column
                width. May be fractional: 0.7 means each block has a 70% chance of one particle per
                tick — a way to thin the trail without lowering the tick rate.""")
            .mutable()
            .register();
  }
}
