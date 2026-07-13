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

  /** Shown when a command is used with an unrecognized subcommand ({@code {1}} = the input). */
  public static final Message1 UNKNOWN_SUBCOMMAND = Message1.error("command.unknown_subcommand");

  /** The {@code /odyssey} usage hint. */
  public static final Message1 ODYSSEY_USAGE = Message1.info("command.odyssey.usage");

  /** Confirms a successful {@code /odyssey reload}. */
  public static final Message0 RELOAD_SUCCESS = Message0.success("command.odyssey.reload.success");

  /**
   * Notes that some changed settings need a restart ({@code {1}} = the comma-joined key list).
   */
  public static final Message1 RELOAD_RESTART_REQUIRED =
      Message1.info("command.odyssey.reload.restart_required");

  private OdysseyMessages() {
  }
}
