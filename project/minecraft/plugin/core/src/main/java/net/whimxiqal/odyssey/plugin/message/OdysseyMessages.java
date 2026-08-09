/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.message;

/**
 * The catalog of Odyssey's user-facing messages. Each constant names a bundle key and its tone/arity;
 * render or send it through {@link Messages}. Keys mirror the entries in {@code messages.properties}.
 *
 * <p>Only the messages needed for the Phase 6a foundation are declared here; command/navigation
 * messages are added alongside their features in later sub-phases.
 */
public final class OdysseyMessages {

  /** Shown when a sender lacks permission for a command. */
  public static final Message0 NO_PERMISSION = Message0.error("command.no_permission");

  /** Shown when a player-only command is run from the console. */
  public static final Message0 PLAYERS_ONLY = Message0.error("command.players_only");

  /** Shown when a command is used with an unrecognized subcommand ({@code {1}} = the input). */
  public static final Message1 UNKNOWN_SUBCOMMAND = Message1.error("command.unknown_subcommand");

  /** The {@code /odyssey} usage hint. */
  public static final Message1 ODYSSEY_USAGE = Message1.info("command.odyssey.usage");

  /** Header line for the {@code /odyssey} help menu. */
  public static final Message0 HELP_HEADER = Message0.info("command.odyssey.help.header");

  /** Header line for the {@code /navigate} help menu. */
  public static final Message0 NAVIGATE_HELP_HEADER = Message0.info("command.navigate.help.header");

  /** Confirms a successful {@code /odyssey reload}. */
  public static final Message0 RELOAD_SUCCESS = Message0.success("command.odyssey.reload.success");

  /**
   * Notes that some changed settings need a restart ({@code {1}} = the comma-joined key list).
   */
  public static final Message1 RELOAD_RESTART_REQUIRED =
      Message1.info("command.odyssey.reload.restart_required");

  /** Confirms a waypoint was set ({@code {0}} = its name). */
  public static final Message1 WAYPOINT_SET =
      Message1.success("command.odyssey.waypoint.set");

  /** Confirms a waypoint was removed ({@code {0}} = its name). */
  public static final Message1 WAYPOINT_UNSET =
      Message1.success("command.odyssey.waypoint.unset");

  /** Shown when unsetting a waypoint that does not exist ({@code {0}} = the name). */
  public static final Message1 WAYPOINT_NOT_FOUND =
      Message1.error("command.odyssey.waypoint.not_found");

  /** Header for the waypoint listing ({@code {0}} = the count). */
  public static final Message1 WAYPOINT_LIST_HEADER =
      Message1.info("command.odyssey.waypoint.list.header");

  /** A personal waypoint in the listing ({@code {0}} = name, {@code {1}} = location). */
  public static final Message2 WAYPOINT_LIST_ENTRY =
      Message2.info("command.odyssey.waypoint.list.entry");

  /** A global waypoint in the listing ({@code {0}} = name, {@code {1}} = location). */
  public static final Message2 WAYPOINT_LIST_GLOBAL =
      Message2.info("command.odyssey.waypoint.list.global");

  /** Shown when the player has no waypoints. */
  public static final Message0 WAYPOINT_LIST_NONE =
      Message0.info("command.odyssey.waypoint.list.none");

  /** Shown when a waypoint could not be persisted (details are in the server log). */
  public static final Message0 WAYPOINT_STORE_ERROR =
      Message0.error("command.odyssey.waypoint.store_error");

  /** Prompts the player to run a command to traverse a transition ({@code {0}} = the command). */
  public static final Message1 NAV_TRAIL_PROMPT_COMMAND =
      Message1.info("navigator.trail.prompt.command");

  /** Prompts the player to perform the highlighted action to continue along the trail. */
  public static final Message0 NAV_TRAIL_PROMPT_ACTION =
      Message0.info("navigator.trail.prompt.action");

  /** Acknowledges a {@code /navigate} request while the search runs. */
  public static final Message0 NAVIGATE_SEARCHING = Message0.info("command.navigate.searching");

  /** Confirms a route was found and a trip started. */
  public static final Message0 NAVIGATE_STARTED = Message0.success("command.navigate.started");

  /** Hover stats on the "route found" line ({@code {0}} = calc millis, {@code {1}} = est. duration). */
  public static final Message2 NAVIGATE_STATS = Message2.info("command.navigate.stats");

  /** Shown when no route to the destination exists. */
  public static final Message0 NAVIGATE_NO_ROUTE = Message0.error("command.navigate.no_route");

  /** Shown when the search hit its cell-visit limit before finding a path. */
  public static final Message0 NAVIGATE_LIMIT_EXCEEDED =
      Message0.error("command.navigate.limit_exceeded");

  /** Shown when the search exceeded its wall-clock budget. */
  public static final Message0 NAVIGATE_TIMED_OUT = Message0.error("command.navigate.timed_out");

  /** Shown when the search failed unexpectedly (details in the server log). */
  public static final Message0 NAVIGATE_ERROR = Message0.error("command.navigate.error");

  /** Shown when no destination matches the input ({@code {0}} = what was typed). */
  public static final Message1 NAVIGATE_DESTINATION_NOT_FOUND =
      Message1.error("command.navigate.destination_not_found");

  /** Shown when the destination is ambiguous ({@code {0}} = the candidate paths). */
  public static final Message1 NAVIGATE_DESTINATION_AMBIGUOUS =
      Message1.error("command.navigate.destination_ambiguous");

  /** Shown when the chosen navigator id is unknown ({@code {0}} = the id). */
  public static final Message1 NAVIGATE_UNKNOWN_NAVIGATOR =
      Message1.error("command.navigate.unknown_navigator");

  /** Shown when the player is already at their trip limit. */
  public static final Message0 NAVIGATE_TRIP_LIMIT = Message0.error("command.navigate.trip_limit");

  /** Shown for an unrecognized flag ({@code {0}} = the flag). */
  public static final Message1 NAVIGATE_FLAG_UNKNOWN =
      Message1.error("command.navigate.flag.unknown");

  /** Shown when a value-taking flag has no value ({@code {0}} = the flag). */
  public static final Message1 NAVIGATE_FLAG_MISSING_VALUE =
      Message1.error("command.navigate.flag.missing_value");

  /** Shown for an unknown {@code -no-mode} value ({@code {0}} = the value). */
  public static final Message1 NAVIGATE_FLAG_UNKNOWN_MODE =
      Message1.error("command.navigate.flag.unknown_mode");

  /** Confirms how many trips/searches were cancelled ({@code {0}} = the count). */
  public static final Message1 CANCEL_DONE = Message1.success("command.odyssey.cancel.done");

  /** Confirms a single trip was cancelled by id ({@code {0}} = the id). */
  public static final Message1 CANCEL_TRIP = Message1.success("command.odyssey.cancel.trip");

  /** Shown when no active trip has the given id ({@code {0}} = the id). */
  public static final Message1 CANCEL_NOT_FOUND = Message1.error("command.odyssey.cancel.not_found");

  /** Shown when there is nothing to cancel. */
  public static final Message0 CANCEL_NOTHING = Message0.info("command.odyssey.cancel.nothing");

  /** Header for the active-trips list ({@code {0}} = the count). */
  public static final Message1 TRIPS_HEADER = Message1.info("command.odyssey.trips.header");

  /**
   * One entry in the active-trips list ({@code {0}} = id, {@code {1}} = destination, {@code {2}} =
   * remaining duration).
   */
  public static final Message3 TRIPS_ENTRY = Message3.info("command.odyssey.trips.entry");

  /** Shown when the player has no active trips. */
  public static final Message0 TRIPS_NONE = Message0.info("command.odyssey.trips.none");

  /** Confirms the discovered-portal cache was cleared ({@code {0}} = how many were removed). */
  public static final Message1 PORTALS_CLEARED = Message1.success("command.odyssey.portals.cleared");

  private OdysseyMessages() {
  }
}
