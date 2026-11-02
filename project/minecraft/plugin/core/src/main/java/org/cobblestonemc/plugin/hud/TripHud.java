/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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
import org.cobblestonemc.plugin.trip.TripLabels;
import org.cobblestonemc.plugin.trip.TripManager;

/**
 * Shows each player's active trips in a HUD with a live countdown. The HUD is open while the player
 * has trips, unless the server or the player has turned it off.
 *
 * @param <E> the native entity type
 * @param <A> the trip-agent type wrapping a player
 * @param <L> the native location type
 */
public final class TripHud<E, A extends TripAgent<E>, L> {

  private static final long PERIOD_TICKS = 20L;
  private static final int MAX_LINES = 15;

  private final TripManager<E, A, L> trips;
  private final MinecraftScheduler<E> scheduler;
  private final Executor data;
  private final Messages messages;
  private final PlayerPreferencesDao preferences;
  private final BooleanSupplier enabled;
  private final Function<A, HudDisplay> displays;
  private final CobblestoneLogger logger;

  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, Boolean> shown = new ConcurrentHashMap<>();
  private final Set<UUID> loading = ConcurrentHashMap.newKeySet();

  /**
   * Creates a trip HUD.
   *
   * @param trips the trips to show
   * @param scheduler the entity scheduler
   * @param data where preferences are read and written, in order
   * @param messages the message renderer
   * @param preferences each player's stored choice
   * @param enabled whether the server has the HUD turned on
   * @param displays opens a player's HUD
   * @param logger the logger
   */
  public TripHud(
      TripManager<E, A, L> trips,
      MinecraftScheduler<E> scheduler,
      Executor data,
      Messages messages,
      PlayerPreferencesDao preferences,
      BooleanSupplier enabled,
      Function<A, HudDisplay> displays,
      CobblestoneLogger logger) {
    this.trips = trips;
    this.scheduler = scheduler;
    this.data = data;
    this.messages = messages;
    this.preferences = preferences;
    this.enabled = enabled;
    this.displays = displays;
    this.logger = logger;
  }

  /**
   * Returns whether the server has the HUD turned on.
   *
   * @return whether HUDs may be shown
   */
  public boolean available() {
    return enabled.getAsBoolean();
  }

  /**
   * Loads the player's stored choice in the background. Until it arrives, the default applies.
   *
   * @param player the player
   */
  public void load(UUID player) {
    loading.add(player);
    data.execute(
        () -> {
          boolean show = read(player);
          // Dropped if the player has since left or made a new choice.
          if (loading.remove(player)) {
            shown.put(player, show);
          }
        });
  }

  /**
   * Returns whether the player wants their HUD shown.
   *
   * @param player the player
   * @return {@code true} unless they have hidden it
   */
  public boolean isShown(UUID player) {
    return shown.getOrDefault(player, PlayerPreferences.defaults(player).hud());
  }

  /**
   * Records whether the player wants their HUD shown, and redraws it.
   *
   * @param agent the player
   * @param show whether to show it
   */
  public void setShown(A agent, boolean show) {
    UUID uuid = agent.uuid();
    loading.remove(uuid);
    shown.put(uuid, show);
    data.execute(
        () -> {
          try {
            preferences.upsert(new PlayerPreferences(uuid, show));
          } catch (DataStoreException e) {
            logger.warn("Could not store the HUD preference of {}: {}", uuid, e.getMessage());
          }
        });
    refresh(agent);
  }

  /**
   * Schedules a redraw of the player's HUD, opening or closing it as needed.
   *
   * @param agent the player
   */
  public void refresh(A agent) {
    scheduler.runAtEntity(agent.entity(), () -> render(agent));
  }

  /**
   * Closes the player's HUD and forgets their cached preference.
   *
   * @param player the departing player
   */
  public void forget(UUID player) {
    close(player);
    loading.remove(player);
    shown.remove(player);
  }

  /** Closes every open HUD. */
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
    Locale locale = agent.locale();
    Component title =
        Component.text(messages.raw(locale, "hud.title"), null, TextDecoration.UNDERLINED);
    List<String> labels = TripLabels.shorten(active.stream().map(Trip::label).toList());
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

  private Session open(A agent) {
    HudDisplay display = displays.apply(agent);
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

  private boolean read(UUID player) {
    try {
      return preferences.get(player).orElseGet(() -> PlayerPreferences.defaults(player)).hud();
    } catch (DataStoreException e) {
      logger.warn("Could not read the HUD preference of {}: {}", player, e.getMessage());
      return PlayerPreferences.defaults(player).hud();
    }
  }

  private record Session(HudDisplay display, ScheduledTaskHandle ticker) {}
}
