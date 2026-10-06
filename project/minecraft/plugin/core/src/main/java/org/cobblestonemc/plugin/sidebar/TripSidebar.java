/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.sidebar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.ScheduledTaskHandle;
import org.cobblestonemc.plugin.data.DataStoreException;
import org.cobblestonemc.plugin.data.PlayerPreferences;
import org.cobblestonemc.plugin.data.PlayerPreferencesDao;
import org.cobblestonemc.plugin.message.CobblestoneColors;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.trip.Trip;
import org.cobblestonemc.plugin.trip.TripAgent;
import org.cobblestonemc.plugin.trip.TripManager;

/**
 * Lists each player's active trips in their sidebar with a live countdown: a {@code Trips} heading,
 * then one row per trip, {@code home - 3m 24s}. Platform-neutral — the platform supplies only a
 * {@link SidebarDisplay} to draw into.
 *
 * <p>A player's sidebar opens with their first trip, redraws once a second and whenever their trips
 * change, and closes with their last trip. It is skipped entirely while the server has it turned
 * off or the player has hidden it for themselves; that choice is stored, so it survives a
 * reconnect.
 *
 * <p>All drawing runs on the player's own entity thread. Register {@link #refresh} with {@link
 * TripManager#onChange}, call {@link #forget} on logout and {@link #closeAll} on shutdown.
 *
 * @param <E> the native entity type
 * @param <A> the trip-agent type wrapping a player
 * @param <L> the native location type
 */
public final class TripSidebar<E, A extends TripAgent<E>, L> {

  /** A countdown in whole seconds needs a redraw once a second. */
  private static final long PERIOD_TICKS = 20L;

  /** The most rows a sidebar can show. */
  private static final int MAX_LINES = 15;

  private final TripManager<E, A, L> trips;
  private final MinecraftScheduler<E> scheduler;
  private final Messages messages;
  private final PlayerPreferencesDao preferences;
  private final BooleanSupplier enabled;
  private final Function<A, SidebarDisplay> displays;
  private final Function<A, Locale> locales;
  private final CobblestoneLogger logger;

  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, Boolean> shown = new ConcurrentHashMap<>();

  /**
   * Creates a sidebar controller.
   *
   * @param trips the trips to list
   * @param scheduler the scheduler that runs work on a player's entity thread
   * @param messages the message renderer (heading and durations)
   * @param preferences where each player's show/hide choice is stored
   * @param enabled whether the server has the sidebar turned on (re-read on every draw)
   * @param displays opens a player's sidebar on the platform
   * @param locales a player's locale
   * @param logger for preference-store failures
   */
  public TripSidebar(
      TripManager<E, A, L> trips,
      MinecraftScheduler<E> scheduler,
      Messages messages,
      PlayerPreferencesDao preferences,
      BooleanSupplier enabled,
      Function<A, SidebarDisplay> displays,
      Function<A, Locale> locales,
      CobblestoneLogger logger) {
    this.trips = trips;
    this.scheduler = scheduler;
    this.messages = messages;
    this.preferences = preferences;
    this.enabled = enabled;
    this.displays = displays;
    this.locales = locales;
    this.logger = logger;
  }

  /**
   * Returns whether the server has the sidebar turned on at all.
   *
   * @return {@code true} if sidebars may be shown
   */
  public boolean available() {
    return enabled.getAsBoolean();
  }

  /**
   * Returns whether the player wants their trips in the sidebar.
   *
   * @param player the player
   * @return {@code true} unless they have hidden it
   */
  public boolean isShown(UUID player) {
    return shown.computeIfAbsent(player, this::load);
  }

  /**
   * Records whether the player wants their trips in the sidebar, and redraws it to match.
   *
   * @param agent the player
   * @param show {@code true} to show it, {@code false} to hide it
   */
  public void setShown(A agent, boolean show) {
    UUID uuid = agent.uuid();
    shown.put(uuid, show);
    try {
      preferences.upsert(new PlayerPreferences(uuid, show));
    } catch (DataStoreException e) {
      // Still honoured for this session; it just will not survive a reconnect.
      logger.warn("Could not store the sidebar preference of {}: {}", uuid, e.getMessage());
    }
    refresh(agent);
  }

  /**
   * Schedules a redraw of the player's sidebar on their entity thread — opening it, updating it, or
   * closing it, whichever their trips now call for.
   *
   * @param agent the player
   */
  public void refresh(A agent) {
    scheduler.runAtEntity(agent.entity(), () -> render(agent));
  }

  /**
   * Closes the player's sidebar and forgets their cached preference (call on logout).
   *
   * @param player the departing player
   */
  public void forget(UUID player) {
    close(player);
    shown.remove(player);
  }

  /** Closes every open sidebar (call on shutdown). */
  public void closeAll() {
    List.copyOf(sessions.keySet()).forEach(this::close);
  }

  private void render(A agent) {
    UUID uuid = agent.uuid();
    List<Trip<E, A, L>> active = new ArrayList<>(trips.trips(uuid));
    if (active.isEmpty() || !enabled.getAsBoolean() || !isShown(uuid)) {
      close(uuid);
      return;
    }
    active.sort(Comparator.comparingInt(Trip::id));
    if (active.size() > MAX_LINES) {
      active = active.subList(0, MAX_LINES);
    }
    Locale locale = locales.apply(agent);
    Component title =
        Component.text(messages.raw(locale, "sidebar.title"), null, TextDecoration.UNDERLINED);
    List<String> labels = SidebarLabels.shorten(active.stream().map(Trip::label).toList());
    List<Component> lines = new ArrayList<>(active.size());
    for (int i = 0; i < active.size(); i++) {
      lines.add(
          Component.text()
              .append(Component.text(labels.get(i), CobblestoneColors.SECONDARY))
              .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
              .append(
                  Component.text(
                      messages.formatDurationShort(locale, active.get(i).remainingSeconds()),
                      NamedTextColor.GRAY))
              .build());
    }
    Session session = sessions.get(uuid);
    if (session == null) {
      session = open(agent);
      sessions.put(uuid, session);
    }
    session.display().render(title, lines);
  }

  /** Opens the player's display and starts its once-a-second redraw. */
  private Session open(A agent) {
    SidebarDisplay display = displays.apply(agent);
    ScheduledTaskHandle ticker =
        scheduler.runAtEntityRepeating(agent.entity(), () -> render(agent), PERIOD_TICKS);
    return new Session(display, ticker);
  }

  private void close(UUID player) {
    Session session = sessions.remove(player);
    if (session != null) {
      session.ticker().cancel();
      session.display().close();
    }
  }

  private boolean load(UUID player) {
    try {
      return preferences.get(player).orElseGet(() -> PlayerPreferences.defaults(player)).sidebar();
    } catch (DataStoreException e) {
      logger.warn("Could not read the sidebar preference of {}: {}", player, e.getMessage());
      return PlayerPreferences.defaults(player).sidebar();
    }
  }

  /** One player's open sidebar and the task that keeps it current. */
  private record Session(SidebarDisplay display, ScheduledTaskHandle ticker) {}
}
