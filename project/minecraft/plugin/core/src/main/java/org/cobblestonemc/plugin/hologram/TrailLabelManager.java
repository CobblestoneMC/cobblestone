/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.ScheduledTaskHandle;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.navigator.Vec3;
import org.cobblestonemc.plugin.trip.Trip;
import org.cobblestonemc.plugin.trip.TripAgent;
import org.cobblestonemc.plugin.trip.TripLabels;
import org.cobblestonemc.plugin.trip.TripManager;

/**
 * The {@link TrailLabels} shared by every trail navigator. Labels are shortened against each other
 * like the HUD's, so a lone trip to a player reads {@code Steve}, and beside a trip to a town of
 * the same name, {@code player Steve} and {@code town Steve}; labels over the same spot are
 * stacked.
 *
 * <p>Labels show only while the player stands still: once they have stayed in one block for {@link
 * #STILL_TICKS}, each trail's label appears over the point its navigator gives, {@link #DISTANCE}
 * blocks from them, in an open block they can see (see {@link HologramPlacement}); a trail with no
 * such spot yet gets its label as soon as it has one. Labels stay where they appeared, and all go
 * away as soon as the player leaves the block. Someone following a trail knows where it goes; one
 * who stops may not.
 *
 * @param <E> the native entity type
 * @param <A> the trip-agent type wrapping a player
 * @param <L> the native location type
 */
public final class TrailLabelManager<E, A extends TripAgent<E>, L> implements TrailLabels<L> {

  /** How far from the player, in a straight line, a label floats over their trail. */
  static final double DISTANCE = 4.5;

  /** How long the player must stay in one block before their labels appear: one second. */
  static final int STILL_TICKS = 20;

  /** How often a player's labels are updated while any of their trails is attached. */
  static final long PERIOD_TICKS = 2L;

  private final TripManager<E, A, L> trips;
  private final MinecraftScheduler<E> scheduler;
  private final Messages messages;
  private final HologramPlatform<A, L> platform;

  // Guarded by this. Never held while calling into the trip manager, which calls in here under its
  // own lock (a trip stopping detaches its navigator).
  private final Map<UUID, Session<E, A, L>> sessions = new HashMap<>();

  /**
   * Creates the label manager.
   *
   * @param trips the trips whose trails are labelled, for each label's name and for cancelling
   * @param scheduler the entity scheduler
   * @param messages the message renderer
   * @param platform places labels and reads where the player is
   */
  public TrailLabelManager(
      TripManager<E, A, L> trips,
      MinecraftScheduler<E> scheduler,
      Messages messages,
      HologramPlatform<A, L> platform) {
    this.trips = trips;
    this.scheduler = scheduler;
    this.messages = messages;
    this.platform = platform;
  }

  @Override
  public void attach(UUID player, Navigator<L> navigator, Source<L> source) {
    Optional<Trip<E, A, L>> trip = tripOf(trips.trips(player), navigator);
    if (trip.isEmpty()) {
      return; // not driving one of the player's trips
    }
    synchronized (this) {
      Session<E, A, L> session = sessions.get(player);
      if (session == null) {
        A agent = trip.get().player();
        ScheduledTaskHandle ticker =
            scheduler.runAtEntityRepeating(agent.entity(), () -> render(player), PERIOD_TICKS);
        session = new Session<>(agent, ticker);
        sessions.put(player, session);
      }
      session.sources.put(navigator, source);
    }
  }

  @Override
  public synchronized void detach(UUID player, Navigator<L> navigator) {
    Session<E, A, L> session = sessions.get(player);
    if (session == null) {
      return;
    }
    session.sources.remove(navigator);
    Shown shown = session.shown.remove(navigator);
    if (shown != null) {
      shown.hologram().remove();
    }
    if (session.sources.isEmpty()) {
      session.ticker.cancel();
      sessions.remove(player);
    }
  }

  private void render(UUID player) {
    List<Trip<E, A, L>> active = sorted(trips.trips(player));
    List<String> labels = labels(active);
    synchronized (this) {
      Session<E, A, L> session = sessions.get(player);
      if (session != null) {
        render(session, active, labels);
      }
    }
  }

  private void render(Session<E, A, L> session, List<Trip<E, A, L>> active, List<String> labels) {
    Optional<PlayerPosition> position = platform.position(session.agent);
    if (position.isEmpty()
        || session.position == null
        || !position.get().sameBlock(session.position)) {
      // Moving (or gone): no labels, and the wait starts over.
      session.position = position.orElse(null);
      session.stillTicks = 0;
      session.clear();
      return;
    }
    if (session.stillTicks < STILL_TICKS) {
      session.stillTicks += (int) PERIOD_TICKS;
      if (session.stillTicks < STILL_TICKS) {
        return;
      }
    }
    Vec3 eye = position.get().eye();
    Map<Navigator<L>, Shown> shown = session.shown;
    List<Vec3> taken = new ArrayList<>(shown.values().stream().map(Shown::at).toList());
    for (int i = 0; i < active.size(); i++) {
      Trip<E, A, L> trip = active.get(i);
      Navigator<L> navigator = trip.navigator();
      Source<L> source = session.sources.get(navigator);
      if (source == null) {
        continue; // not a labelled trail
      }
      // White, like the name over a player.
      Component text = Component.text(labels.get(i), NamedTextColor.WHITE);
      Shown current = shown.get(navigator);
      if (current != null) {
        if (!text.equals(current.text())) {
          current.hologram().text(text); // another trip started or ended
          shown.put(navigator, new Shown(current.hologram(), current.at(), text));
        }
        continue;
      }
      Optional<Vec3> spot =
          source
              .labelPoint(DISTANCE)
              .map(platform::point)
              .flatMap(
                  point ->
                      HologramPlacement.place(eye, point, taken, platform.probe(session.agent)));
      if (spot.isEmpty()) {
        continue; // nowhere to put it yet; try again next time
      }
      A agent = session.agent;
      platform
          .spawn(agent, spot.get(), text, () -> cancel(agent, trip))
          .ifPresent(
              hologram -> {
                shown.put(navigator, new Shown(hologram, spot.get(), text));
                taken.add(spot.get());
              });
    }
  }

  private void cancel(A agent, Trip<E, A, L> trip) {
    List<Trip<E, A, L>> active = sorted(trips.trips(agent.uuid()));
    int index = active.indexOf(trip);
    if (index < 0) {
      return; // already over
    }
    String label = labels(active).get(index); // as the label read
    trips.stop(trip); // detaches its navigator, which removes the label
    messages.send(agent.audience(), agent.locale(), CobblestoneMessages.HOLOGRAM_CANCELED, label);
  }

  /** The player's trips in the order they started. */
  private static <E, A extends TripAgent<E>, L> List<Trip<E, A, L>> sorted(
      List<Trip<E, A, L>> active) {
    List<Trip<E, A, L>> sorted = new ArrayList<>(active);
    sorted.sort(Comparator.comparingInt(Trip::id));
    return sorted;
  }

  /** Each trip's label, as short as it can be while telling the trips apart. */
  private static List<String> labels(List<? extends Trip<?, ?, ?>> active) {
    return TripLabels.shorten(active.stream().map(Trip::label).toList());
  }

  private static <E, A extends TripAgent<E>, L> Optional<Trip<E, A, L>> tripOf(
      List<Trip<E, A, L>> active, Navigator<L> navigator) {
    return active.stream().filter(trip -> trip.navigator() == navigator).findFirst();
  }

  /** One player's attached trails, their labels, and how long the player has stood still. */
  private static final class Session<E, A extends TripAgent<E>, L> {

    final A agent;
    final ScheduledTaskHandle ticker;
    final Map<Navigator<L>, Source<L>> sources = new LinkedHashMap<>(); // in attach order
    final Map<Navigator<L>, Shown> shown = new HashMap<>();
    PlayerPosition position; // where the player was at the last update, or null
    int stillTicks; // how long they have been in that block

    Session(A agent, ScheduledTaskHandle ticker) {
      this.agent = agent;
      this.ticker = ticker;
    }

    void clear() {
      shown.values().forEach(label -> label.hologram().remove());
      shown.clear();
    }
  }

  private record Shown(Hologram hologram, Vec3 at, Component text) {}
}
