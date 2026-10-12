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
import org.cobblestonemc.plugin.navigator.TrailSmoother;
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
 * <p>A label goes over the point its navigator gives, {@link #DISTANCE} blocks from the player, in
 * an open block they can see (see {@link HologramPlacement}); if there is none, it isn't shown. It
 * appears when the player looks at it, within a window wider than it is tall, and disappears once
 * they look a little further away, so it isn't in front of them while they watch the trail itself.
 * A player's labels are updated every {@link #PERIOD_TICKS} ticks while any of their trails is
 * attached.
 *
 * @param <E> the native entity type
 * @param <A> the trip-agent type wrapping a player
 * @param <L> the native location type
 */
public final class TrailLabelManager<E, A extends TripAgent<E>, L> implements TrailLabels<L> {

  /** How far from the player, in a straight line, a label floats over their trail. */
  static final double DISTANCE = 3.25;

  /** A label appears once the player looks within this many degrees of it, to either side... */
  static final double SHOW_YAW_DEGREES = 20;

  /** ...and this many above or below. */
  static final double SHOW_PITCH_DEGREES = 12;

  /** It disappears once they look further than this to either side... */
  static final double HIDE_YAW_DEGREES = 32;

  /** ...or above or below. */
  static final double HIDE_PITCH_DEGREES = 19;

  /** Labels follow the player every other tick. */
  static final long PERIOD_TICKS = 2L;

  /**
   * A label whose spot is briefly lost (e.g. for the tick between a new guide arriving and being
   * drawn) stays where it is for this many updates before it is removed, so it doesn't blink.
   */
  static final int GRACE_UPDATES = 5;

  /** A label moved less than this stays where it is. */
  private static final double MOVE_EPSILON_SQUARED = 0.02 * 0.02;

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
   * @param platform places labels and reads the player's view
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
      session.sources().put(navigator, source);
    }
  }

  @Override
  public synchronized void detach(UUID player, Navigator<L> navigator) {
    Session<E, A, L> session = sessions.get(player);
    if (session == null) {
      return;
    }
    session.sources().remove(navigator);
    Shown shown = session.shown().remove(navigator);
    if (shown != null) {
      shown.hologram().remove();
    }
    if (session.sources().isEmpty()) {
      session.ticker().cancel();
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
    Map<Navigator<L>, Shown> shown = session.shown();
    Optional<Gaze> gaze = platform.gaze(session.agent());
    if (gaze.isEmpty()) {
      shown.values().forEach(label -> label.hologram().remove());
      shown.clear();
      return;
    }
    TrailSmoother.BlockProbe probe = platform.probe(session.agent());
    List<Vec3> taken = new ArrayList<>();
    for (int i = 0; i < active.size(); i++) {
      Trip<E, A, L> trip = active.get(i);
      Navigator<L> navigator = trip.navigator();
      Source<L> source = session.sources().get(navigator);
      if (source == null) {
        continue; // not a labelled trail
      }
      Optional<Vec3> spot =
          source
              .labelPoint(DISTANCE)
              .map(platform::point)
              .flatMap(point -> HologramPlacement.place(gaze.get().eye(), point, taken, probe));
      Shown current = shown.get(navigator);
      if (spot.isEmpty() && current != null && current.missed() < GRACE_UPDATES) {
        spot = Optional.of(current.at()); // keep it there for now
        current = current.missing();
      } else if (current != null) {
        current = current.found();
      }
      spot.ifPresent(taken::add);
      if (spot.isEmpty() || !inView(gaze.get(), spot.get(), current != null)) {
        if (current != null) {
          current.hologram().remove();
          shown.remove(navigator);
        }
        continue;
      }
      // White, like the name over a player.
      Component text = Component.text(labels.get(i), NamedTextColor.WHITE);
      Shown updated = show(session.agent(), trip, current, spot.get(), text);
      if (updated == null) {
        shown.remove(navigator);
      } else {
        shown.put(navigator, updated);
      }
    }
  }

  /** Whether the player looks at {@code spot}, with more slack once the label is {@code shown}. */
  private static boolean inView(Gaze gaze, Vec3 spot, boolean shown) {
    return shown
        ? HologramPlacement.inView(gaze, spot, HIDE_YAW_DEGREES, HIDE_PITCH_DEGREES)
        : HologramPlacement.inView(gaze, spot, SHOW_YAW_DEGREES, SHOW_PITCH_DEGREES);
  }

  /** Shows, moves, or relabels the trip's label; {@code null} if it can't be shown here. */
  private Shown show(A agent, Trip<E, A, L> trip, Shown current, Vec3 at, Component text) {
    if (current != null) {
      boolean moved = at.minus(current.at()).lengthSquared() > MOVE_EPSILON_SQUARED;
      if (!moved || current.hologram().move(at)) {
        if (!text.equals(current.text())) {
          current.hologram().text(text);
        }
        return new Shown(current.hologram(), moved ? at : current.at(), text, current.missed());
      }
      current.hologram().remove(); // it can't follow from here; place a fresh one
    }
    return platform
        .spawn(agent, at, text, () -> cancel(agent, trip))
        .map(hologram -> new Shown(hologram, at, text, 0))
        .orElse(null);
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

  /** One player's attached trails and their labels, in attach order. */
  private record Session<E, A extends TripAgent<E>, L>(
      A agent,
      ScheduledTaskHandle ticker,
      Map<Navigator<L>, Source<L>> sources,
      Map<Navigator<L>, Shown> shown) {

    Session(A agent, ScheduledTaskHandle ticker) {
      this(agent, ticker, new LinkedHashMap<>(), new HashMap<>());
    }
  }

  /** A label on screen, and for how many updates in a row its spot has been lost. */
  private record Shown(Hologram hologram, Vec3 at, Component text, int missed) {

    Shown missing() {
      return new Shown(hologram, at, text, missed + 1);
    }

    Shown found() {
      return missed == 0 ? this : new Shown(hologram, at, text, 0);
    }
  }
}
