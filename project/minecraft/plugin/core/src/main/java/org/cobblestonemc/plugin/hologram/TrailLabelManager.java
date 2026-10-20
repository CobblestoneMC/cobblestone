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
import java.util.OptionalDouble;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.ScheduledTaskHandle;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.message.CobblestoneColors;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Messages;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.cobblestonemc.plugin.navigator.Vec3;
import org.cobblestonemc.plugin.trip.Trip;
import org.cobblestonemc.plugin.trip.TripAgent;
import org.cobblestonemc.plugin.trip.TripLabels;
import org.cobblestonemc.plugin.trip.TripManager;
import org.cobblestonemc.plugin.trip.TripTarget;

/**
 * The {@link TrailLabels} shared by every trail navigator. Labels are shortened against each other
 * like the HUD's, so a lone trip to a player reads {@code Steve}, and beside a trip to a town of
 * the same name, {@code player Steve} and {@code town Steve}; labels over the same spot are
 * stacked.
 *
 * <p>Trail labels show only while the player stands still: once they have stayed in one block for
 * {@link #STILL_TICKS}, each trail's label appears over its trail head, in an open block they can
 * see (see {@link HologramPlacement}); a trail with no such spot yet gets its label as soon as it
 * has one. A label follows its trail head as the route changes, gliding there, or jumping if it is
 * more than {@link #GLIDE_LIMIT} away; all go away as soon as the player leaves the block. Someone
 * following a trail knows where it goes; one who stops may not.
 *
 * <p>Each trail label reads {@code Cancel} in red above the trip's name, and hitting that cancels
 * the trip. The client can't hit a label (see {@link LabelHit}), so the platform reports every left
 * click to {@link #swing}, which works out what it landed on.
 *
 * <p>Once the player comes within {@link #GOAL_SHOW_DISTANCE} of a trip's destination, a label
 * naming it floats over it, moving or not, and follows it if it moves. It goes only if they head
 * back past {@link #GOAL_HIDE_DISTANCE}. A destination that shows its own name, as a player does,
 * gets none.
 *
 * @param <E> the native entity type
 * @param <A> the trip-agent type wrapping a player
 * @param <L> the native location type
 */
public final class TrailLabelManager<E, A extends TripAgent<E>, L> implements TrailLabels<L> {

  /** How long the player must stay in one block before their labels appear: one second. */
  static final int STILL_TICKS = 20;

  /** How often a player's labels are updated while any of their trails is attached. */
  static final long PERIOD_TICKS = 2L;

  /** A trail label that would move further than this at once jumps there instead of gliding. */
  static final double GLIDE_LIMIT = 1.0;

  /** A destination label that would move further than this at once jumps there. */
  static final double GOAL_GLIDE_LIMIT = 8.0;

  /** How close the player must come to a destination, in blocks, for its label to appear. */
  static final double GOAL_SHOW_DISTANCE = 24.0;

  /** How far the player must head back from a destination, in blocks, for its label to go. */
  static final double GOAL_HIDE_DISTANCE = 32.0;

  /** How far above the end of the route a destination's label floats, in blocks. */
  static final double GOAL_HEIGHT = 1.0;

  /** A label that would move less than this stays put. */
  private static final double MIN_MOVE = 0.01;

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
    Shown<E, A, L> shown = session.shown.remove(navigator);
    if (shown != null) {
      shown.remove();
    }
    Goal goal = session.goals.remove(navigator);
    if (goal != null) {
      goal.hologram().remove();
    }
    if (session.sources.isEmpty()) {
      session.clear();
      session.ticker.cancel();
      sessions.remove(player);
    }
  }

  /**
   * Handles the player swinging their arm, which is any left click: if their line of sight meets
   * the {@code Cancel} line of one of their labels, its trip is cancelled. Call on the player's
   * thread.
   *
   * @param player the player
   * @return whether the swing hit a label, in which case the click should do nothing else
   */
  public boolean swing(UUID player) {
    A agent;
    Trip<E, A, L> hit = null;
    synchronized (this) {
      Session<E, A, L> session = sessions.get(player);
      if (session == null || session.shown.isEmpty()) {
        return false;
      }
      agent = session.agent;
      Optional<PlayerPosition> position = platform.position(agent);
      if (position.isEmpty()) {
        return false;
      }
      TrailSmoother.BlockProbe probe = platform.probe(agent);
      double nearest = Double.POSITIVE_INFINITY;
      for (Shown<E, A, L> shown : session.shown.values()) {
        OptionalDouble distance =
            LabelHit.distance(
                position.get().eye(),
                position.get().look(),
                HologramPlacement.cancelAt(shown.at()),
                shown.cancelHalfWidth(),
                probe);
        if (distance.isPresent() && distance.getAsDouble() < nearest) {
          nearest = distance.getAsDouble();
          hit = shown.trip();
        }
      }
    }
    if (hit == null) {
      return false;
    }
    cancel(agent, hit); // outside our lock: stopping the trip detaches its navigator from us
    return true;
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
    if (position.isEmpty()) {
      session.position = null;
      session.stillTicks = 0;
      session.clear();
      return;
    }
    Vec3 eye = position.get().eye();
    renderGoals(session, active, labels, eye);
    if (session.position == null || !position.get().sameBlock(session.position)) {
      // Moving: no trail labels, and the wait starts over.
      session.position = position.get();
      session.stillTicks = 0;
      session.clearTrailLabels();
      return;
    }
    if (session.stillTicks < STILL_TICKS) {
      session.stillTicks += (int) PERIOD_TICKS;
      if (session.stillTicks < STILL_TICKS) {
        return;
      }
    }
    TrailSmoother.BlockProbe probe = platform.probe(session.agent);
    Map<Navigator<L>, Shown<E, A, L>> shown = session.shown;
    List<Vec3> taken = new ArrayList<>();
    for (int i = 0; i < active.size(); i++) {
      Trip<E, A, L> trip = active.get(i);
      Navigator<L> navigator = trip.navigator();
      Source<L> source = session.sources.get(navigator);
      if (source == null) {
        continue; // not a labelled trail
      }
      Component text =
          Component.text("✦ ")
              .color(CobblestoneColors.PRIMARY)
              .append(Component.text(labels.get(i)).color(NamedTextColor.WHITE))
              .append(Component.text(" ✦").color(CobblestoneColors.PRIMARY));
      Shown<E, A, L> current = shown.get(navigator);
      Optional<Vec3> spot =
          source
              .headPoint()
              .map(platform::point)
              .flatMap(point -> HologramPlacement.place(eye, point, taken, probe));
      if (spot.isEmpty()) {
        if (current != null) {
          taken.add(current.at()); // keep it where it is until the trail has a spot again
        }
        continue;
      }
      if (current != null && current.at().minus(spot.get()).length() > GLIDE_LIMIT) {
        current.remove(); // the route changed a lot: jump, rather than swing across
        shown.remove(navigator);
        current = null;
      }
      if (current == null) {
        spawn(session.agent, trip, spot.get(), text)
            .ifPresent(label -> shown.put(navigator, label));
      } else {
        if (!text.equals(current.text())) {
          current.name().text(text); // another trip started or ended
          current = current.withText(text);
        }
        if (current.at().minus(spot.get()).length() > MIN_MOVE) {
          current.name().move(spot.get());
          current.cancel().move(HologramPlacement.cancelAt(spot.get()));
          current = current.withAt(spot.get());
        }
        shown.put(navigator, current);
      }
      taken.add(spot.get());
    }
  }

  /**
   * Shows, moves and removes the labels over the destinations the player is close to, whether or
   * not they are moving.
   */
  private void renderGoals(
      Session<E, A, L> session, List<Trip<E, A, L>> active, List<String> labels, Vec3 eye) {
    for (int i = 0; i < active.size(); i++) {
      Trip<E, A, L> trip = active.get(i);
      Navigator<L> navigator = trip.navigator();
      Source<L> source = session.sources.get(navigator);
      if (source == null) {
        continue; // not a labelled trail
      }
      Goal current = session.goals.get(navigator);
      Optional<Vec3> spot = goalSpot(session.agent, trip, source);
      double distance = spot.map(at -> at.minus(eye).length()).orElse(Double.POSITIVE_INFINITY);
      if (current == null ? distance > GOAL_SHOW_DISTANCE : distance > GOAL_HIDE_DISTANCE) {
        if (current != null) {
          current.hologram().remove(); // headed back, or labels are off
          session.goals.remove(navigator);
        }
        continue;
      }
      // Arrows down at the place itself, styled like the trail labels.
      Component text =
          Component.text("↓ ")
              .color(CobblestoneColors.PRIMARY)
              .append(Component.text(labels.get(i)).color(NamedTextColor.WHITE))
              .append(Component.text(" ↓").color(CobblestoneColors.PRIMARY));
      Vec3 at = spot.get();
      if (current != null && current.at().minus(at).length() > GOAL_GLIDE_LIMIT) {
        current.hologram().remove(); // it was teleported: jump there
        current = null;
      }
      if (current == null) {
        platform
            .spawn(session.agent, at, text)
            .ifPresent(hologram -> session.goals.put(navigator, new Goal(hologram, at, text)));
        continue;
      }
      if (!text.equals(current.text())) {
        current.hologram().text(text);
      }
      Vec3 now = current.at();
      if (now.minus(at).length() > MIN_MOVE) {
        current.hologram().move(at);
        now = at;
      }
      session.goals.put(navigator, new Goal(current.hologram(), now, text));
    }
  }

  /**
   * Where the label naming a trip's destination floats: where the destination says, if it moves, or
   * else over the end of the route. Empty for a destination that shows its own name, one in another
   * world, or while labels are off.
   */
  private Optional<Vec3> goalSpot(A agent, Trip<E, A, L> trip, Source<L> source) {
    Optional<L> goal = source.goalPoint(); // empty while labels are off, too
    if (trip.target().labelled() || goal.isEmpty()) {
      return Optional.empty();
    }
    Optional<TripTarget.Point> anchor = trip.target().anchor().get();
    if (anchor.isPresent()) {
      return anchor.get().world().equals(platform.worldKey(agent))
          ? Optional.of(anchor.get().at())
          : Optional.empty();
    }
    return goal.map(platform::point).map(point -> point.plus(new Vec3(0, GOAL_HEIGHT, 0)));
  }

  /** Places a trip's label, its name at {@code at} and {@code Cancel} above. */
  private Optional<Shown<E, A, L>> spawn(A agent, Trip<E, A, L> trip, Vec3 at, Component text) {
    String cancelText = messages.raw(agent.locale(), CobblestoneMessages.HOLOGRAM_CANCEL.key());
    Optional<Hologram> name = platform.spawn(agent, at, text);
    if (name.isEmpty()) {
      return Optional.empty();
    }
    Optional<Hologram> cancel =
        platform.spawn(
            agent, HologramPlacement.cancelAt(at), Component.text(cancelText, NamedTextColor.RED));
    if (cancel.isEmpty()) {
      name.get().remove();
      return Optional.empty();
    }
    return Optional.of(
        new Shown<>(
            name.get(), cancel.get(), at, text, trip, LabelHit.halfWidth(cancelText.length())));
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
    final Map<Navigator<L>, Shown<E, A, L>> shown = new HashMap<>();
    final Map<Navigator<L>, Goal> goals = new HashMap<>();
    PlayerPosition position; // where the player was at the last update, or null
    int stillTicks; // how long they have been in that block

    Session(A agent, ScheduledTaskHandle ticker) {
      this.agent = agent;
      this.ticker = ticker;
    }

    void clearTrailLabels() {
      shown.values().forEach(Shown::remove);
      shown.clear();
    }

    void clear() {
      clearTrailLabels();
      goals.values().forEach(goal -> goal.hologram().remove());
      goals.clear();
    }
  }

  /**
   * A trip's label as shown.
   *
   * @param name the line with the trip's name
   * @param cancel the {@code Cancel} line above it
   * @param at the center of the name
   * @param text the name
   * @param trip the trip it cancels
   * @param cancelHalfWidth half the width of the {@code Cancel} line, for hitting it
   */
  private record Shown<E, A extends TripAgent<E>, L>(
      Hologram name,
      Hologram cancel,
      Vec3 at,
      Component text,
      Trip<E, A, L> trip,
      double cancelHalfWidth) {

    Shown<E, A, L> withText(Component text) {
      return new Shown<>(name, cancel, at, text, trip, cancelHalfWidth);
    }

    Shown<E, A, L> withAt(Vec3 at) {
      return new Shown<>(name, cancel, at, text, trip, cancelHalfWidth);
    }

    void remove() {
      name.remove();
      cancel.remove();
    }
  }

  /** A label over a trip's destination, as shown. */
  private record Goal(Hologram hologram, Vec3 at, Component text) {}
}
