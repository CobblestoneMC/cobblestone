/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.api.Step;
import org.cobblestonemc.minecraft.api.MinecraftInstruction;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.hologram.TrailLabels;
import org.cobblestonemc.plugin.message.CobblestoneColors;
import org.cobblestonemc.plugin.message.CobblestoneMessages;
import org.cobblestonemc.plugin.message.Messages;

/**
 * The platform-neutral {@code trail} navigator: a column of dust particles along the path ahead of
 * the player (shown only to them). Particles are scattered around each cell with a Gaussian falloff
 * (dense on the path, sparse at the edges). It advances via {@link TrailProgress} (parallel
 * projection, so walking <i>alongside</i> counts), draws a same-width guide column back to the
 * nearest point on the path ahead when the player drifts off, prompts on discrete-action steps, and
 * asks the trip to recalculate if the player strays past {@code recalcDistance}.
 *
 * <p>The trail leads rather than trails: along the current leg (the route from the player, by way
 * of the guide back to the trail if they are off it, up to the next teleport), it starts at a
 * <i>trail head</i> some way ahead of them, so the particles are always in front and never stream
 * past their face. The head is {@link #MIN_GAP} blocks along the leg, or as far as they travel in
 * {@link #LEAD_SECONDS} at their recent speed if that is further; if they can't see it (around a
 * corner, say), it comes back to just past the furthest point of the leg they can see, leaving a
 * stub around the corner. The trail stops {@link #GOAL_CLEARANCE} blocks short of the destination
 * when they can see it, where its label takes over (see {@link TrailLabels}). Highlights before
 * action steps and marks on blocks to mine are drawn however close they are.
 *
 * <p>All the follow geometry, progress, and rendering <i>layout</i> live here; a platform subclass
 * supplies only the handful of native operations — reading the player's position/world, converting
 * a step location to a render point, spawning one particle, and testing a block — so Paper and
 * Sponge share one trail. A 1-block "personal bubble" around the player is left clear so particles
 * don't render in the player's face.
 *
 * @param <L> the native location type the path's steps are located in
 */
public abstract class AbstractTrailNavigator<L> implements Navigator<L> {

  private static final double COMPLETION_RADIUS_SQUARED = 4.0; // within 2 blocks of the goal
  private static final double SPREAD_HORIZONTAL = 0.15; // Gaussian sigma across the column
  private static final double SPREAD_VERTICAL = 0.15;
  private static final double NEAR_BUFFER = 1.0; // clear bubble around the player, in blocks
  private static final double NEAR_BUFFER_SQUARED = NEAR_BUFFER * NEAR_BUFFER;
  private static final double CALC_GUIDE_THRESHOLD = NEAR_BUFFER + 1.0;
  private static final double CALC_GUIDE_THRESHOLD_SQUARED =
      CALC_GUIDE_THRESHOLD * CALC_GUIDE_THRESHOLD;
  private static final int RECALC_COOLDOWN_TICKS = 20; // at most one stray-recalc per second
  private static final int GUIDE_COOLDOWN_TICKS = 20; // re-request the guide path ~1x/second
  private static final double MINE_MARGIN = 0.05; // cage hugs the block to mine
  private static final double PARTICLE_FLOW_SPEED = 1.0;

  /** How far ahead along the leg the trail starts while the player stands still, in blocks. */
  static final double MIN_GAP = 3.0;

  /** How many seconds of travel at the player's recent speed the trail starts ahead of them. */
  static final double LEAD_SECONDS = 1.0;

  /** The furthest ahead the trail starts, in blocks. */
  static final double MAX_GAP = 32.0;

  /** How far past the furthest point the player can see the trail head may be, in blocks. */
  static final double STUB = 1.0;

  /** How far short of a destination the player can see the trail stops, in blocks. */
  static final double GOAL_CLEARANCE = 3.0;

  /**
   * How many ticks of movement the player's speed is measured over. Movement reaches the server in
   * packets that don't line up with its ticks (a tick can see none, and the next two ticks' worth),
   * so a single tick's movement swings between nothing and double; over a few ticks it evens out.
   */
  private static final int SPEED_WINDOW_TICKS = 6;

  /** The share of the gap to the measured speed taken each tick: a time constant of two ticks. */
  private static final double SPEED_SMOOTHING = 1 - Math.exp(-1 / 2.0);

  /** A move further than this in one tick is a teleport, not travel, in blocks. */
  private static final double MAX_TRAVEL_PER_TICK = 4.0;

  private static final double TICKS_PER_SECOND = 20.0;

  /**
   * A real short path back to the trail, smoothed like the trail.
   *
   * @param steps the guide path's steps
   * @param trail node 0 = the guide path's origin, node i + 1 = step i's destination
   * @param world the key of the guide path's world
   */
  private record Guide<L>(
      List<Step<L, MinecraftStepPayload>> steps, SmoothedTrail trail, String world) {}

  /**
   * A stretch of the current leg: segment {@code segment} of {@code path}, from {@code from} (a
   * fraction of the segment) to its end.
   *
   * @param drawn whether particles are drawn along it (not for an action, such as opening a door)
   */
  private record Stretch(SmoothedTrail path, int segment, double from, boolean drawn) {

    double length() {
      return path.segmentLength(segment) * (1 - from);
    }

    /** The point {@code arc} blocks along this stretch. */
    Vec3 at(double arc) {
      double length = length();
      double fraction = length == 0.0 ? from : from + (1 - from) * Math.clamp(arc / length, 0, 1);
      return path.sample(segment, fraction).point();
    }
  }

  /**
   * The route from the player to the next teleport or the destination, as far as it is drawn.
   *
   * @param stretches the stretches in order, starting where the player is
   * @param fromPlayer whether it starts at the player: {@code false} while they are off the trail
   *     with no guide back yet, when it starts at the point of the trail nearest them
   * @param toGoal whether it runs to the destination
   * @param end the index of the first step past it, at which later legs start
   * @param teleport whether it ends at a teleport, beyond which the route goes on
   */
  private record Leg(
      List<Stretch> stretches, boolean fromPlayer, boolean toGoal, int end, boolean teleport) {

    double length() {
      double length = 0;
      for (Stretch stretch : stretches) {
        length += stretch.length();
      }
      return length;
    }

    /** The point {@code arc} blocks along the leg. */
    Vec3 at(double arc) {
      for (Stretch stretch : stretches) {
        double length = stretch.length();
        if (arc <= length) {
          return stretch.at(arc);
        }
        arc -= length;
      }
      Stretch last = stretches.getLast();
      return last.at(last.length());
    }
  }

  private final int bufferCells;
  private final double density;
  private final int recalcDistance;
  private final Messages messages;
  private final Locale locale;
  private final TrailLabels<L> labels;
  private final BooleanSupplier showLabel;

  private List<Step<L, MinecraftStepPayload>> steps;
  private String originWorld;
  // node 0 = where step 0 departs from (the player's start), node i + 1 = step i's destination
  private SmoothedTrail trail;
  private String lastPlayerWorld;
  private Map<BlockPos, Integer> stepByBlock;
  private int foremost; // the step the player still needs to complete (0 = the first step)
  private int lastPromptedIndex = -1;
  private boolean complete;
  private boolean recalcRequested;
  private int recalcCooldown;
  private int tickCounter;
  private boolean guideRequested;
  private int guideCooldown;
  private Guide<L> guide; // null when on-trail
  // record last prompted instruction so we don't repeat ourselves on a recalculation
  private MinecraftInstruction lastPromptedInstruction;
  private Vec3 lastPlayerVec;
  // how far the player moved in each of the last SPEED_WINDOW_TICKS ticks, oldest first
  private final ArrayDeque<Double> recentTravel = new ArrayDeque<>();
  private double recentTravelTotal;
  double speed; // blocks per second, smoothed; package-private for tests
  private Vec3 trailHead; // where the trail starts this tick, or null if it isn't drawn
  private boolean headFromPlayer; // whether the trail head is measured from the player

  /**
   * Creates the navigator; call {@link #setPath} once the subclass is set up.
   *
   * @param bufferCells how many steps ahead are drawn
   * @param density particles per block
   * @param recalcDistance how far the player may stray before the route is recalculated; 0 never
   * @param messages the message renderer, for action prompts
   * @param locale the player's locale
   * @param labels where the trip's label floats over the trail
   * @param showLabel whether to show that label (read whenever it is placed)
   */
  protected AbstractTrailNavigator(
      int bufferCells,
      double density,
      int recalcDistance,
      Messages messages,
      Locale locale,
      TrailLabels<L> labels,
      BooleanSupplier showLabel) {
    this.bufferCells = bufferCells;
    this.density = Math.max(0.0, density);
    this.recalcDistance = recalcDistance;
    this.messages = messages;
    this.locale = locale;
    this.labels = labels;
    this.showLabel = showLabel;
  }

  // --- platform hooks -------------------------------------------------------

  /** The guided player's id. */
  protected abstract UUID playerId();

  /** Whether the guided player is still connected. */
  protected abstract boolean playerOnline();

  /** The player's exact current position (only queried while online). */
  protected abstract Vec3 playerPoint();

  /** The position of the player's eyes (only queried while online). */
  protected abstract Vec3 playerEye();

  /** The key of the player's current world, or {@code null} if unresolved. */
  protected abstract String playerWorldKey();

  /** The block-centerd render point (raised toward body height) for a step location. */
  protected abstract Vec3 renderPoint(L location);

  /** A location in the player's world at {@code point} (only queried while online). */
  protected abstract L location(Vec3 point);

  /** The key of a step location's world, or {@code null} if unresolved. */
  protected abstract String worldKey(L location);

  /** The player as an Adventure audience, for action prompts. */
  protected abstract Audience audience();

  /** Spawns one trail particle at a world position, shown to the player, if renderable here. */
  protected abstract void spawnTrailParticle(
      double x, double y, double z, double vx, double vy, double vz);

  protected abstract void spawnHighlightParticle(double x, double y, double z);

  /**
   * A reader of what occupies each block position in the player's world, used for the current tick
   * only (so it may cache what it learns). It must not load a chunk: a block that can't be read
   * immediately (chunk not loaded, or owned by another region thread) is {@link
   * TrailBlock#UNLOADED}.
   *
   * @return a probe for this tick
   */
  protected abstract TrailSmoother.BlockProbe blockProbe();

  // --- navigator ------------------------------------------------------------

  /**
   * Loads a path: builds the render points and the block index, and resets progress. Call from the
   * subclass constructor once its own fields are set, and via {@link #update} on a live re-search.
   *
   * @param path the path to follow
   */
  protected final void setPath(Path<L, MinecraftStepPayload> path) {
    this.steps = path.steps();
    this.originWorld = worldKey(path.origin());
    this.trail = smoothedTrail(path);
    this.stepByBlock = new HashMap<>();
    for (int i = 0; i < steps.size(); i++) {
      // Highest index wins, so standing on a repeated block jumps to the furthest occurrence.
      stepByBlock.put(BlockPos.of(trail.original(i + 1)), i);
    }
    this.foremost = 0;
    this.lastPromptedIndex = -1;
    this.recalcCooldown = 0;
    this.complete = steps.isEmpty();
    guide = null;
  }

  @Override
  public void start() {
    // The first tick renders the trail.
    labels.attach(
        playerId(),
        this,
        new TrailLabels.Source<>() {
          @Override
          public Optional<L> headPoint() {
            return labelPoint();
          }

          @Override
          public Optional<L> goalPoint() {
            return AbstractTrailNavigator.this.goalPoint();
          }
        });
  }

  @Override
  public void tick() {
    if (complete || !playerOnline() || steps.isEmpty()) {
      return;
    }
    tickCounter++;
    if (recalcCooldown > 0) {
      recalcCooldown--;
    }
    if (guideCooldown > 0) {
      guideCooldown--;
    }
    Vec3 playerVec = playerPoint();
    updateSpeed(playerVec);
    String playerWorld = playerWorldKey();
    boolean onTrailWorld = sameWorld(playerWorld, worldKey(steps.get(foremost).position()));
    TrailSmoother.BlockProbe probe = blockProbe();
    if (!Objects.equals(playerWorld, lastPlayerWorld)) {
      // Nodes in the player's new world couldn't be read before, but now they can.
      trail.resetUnavailable();
      lastPlayerWorld = playerWorld;
    }
    // Smooth what's about to be followed and drawn: from the corner behind the current step
    // (node foremost - 1) to the corner after the last drawn step.
    trail.refresh(
        foremost - 1,
        foremost + bufferCells + 1,
        tickCounter,
        node -> sameWorld(playerWorld, trailNodeWorld(node)),
        probe);

    // Advance only in the trail head's world; a cross-domain hop is handled by the action prompt.
    if (onTrailWorld) {
      // advance returns steps.size() once every step is done; clamp so it stays a valid index.
      foremost =
          Math.min(TrailProgress.advance(trail.nodes(), foremost, playerVec), steps.size() - 1);
    }
    // Shortcut: if the player is standing exactly on a later step within the buffer (e.g. they cut
    // a curve the projection didn't credit), jump the trail forward to it. Standing on step i's
    // destination means step i is done, so step i + 1 is next.
    Integer atBlock = stepByBlock.get(BlockPos.of(playerVec));
    if (atBlock != null) {
      int reached = Math.min(atBlock + 1, steps.size() - 1);
      if (reached > foremost && reached <= foremost + bufferCells) {
        foremost = reached;
      }
    }

    if (reachedGoal(playerVec, playerWorld)) {
      complete = true;
      trailHead = null;
      return;
    }

    double deviationSquared =
        onTrailWorld ? playerVec.minus(projectedTarget(playerVec)).lengthSquared() : 0.0;
    // Strayed too far: quietly ask the trip to recalculate from the player's new position.
    boolean nextStepIsVanilla =
        steps.get(foremost).payload().stepType() != MinecraftStepType.TELEPORT;
    if (onTrailWorld
        && nextStepIsVanilla
        && recalcDistance > 0
        && recalcCooldown <= 0
        && deviationSquared > (double) recalcDistance * recalcDistance) {
      recalcRequested = true;
      recalcCooldown = RECALC_COOLDOWN_TICKS;
    }
    // Drifted off the path: periodically request a short guide path back to the current step.
    if (onTrailWorld && nextStepIsVanilla && deviationSquared > CALC_GUIDE_THRESHOLD_SQUARED) {
      if (guideCooldown <= 0) {
        guideRequested = true;
        guideCooldown = GUIDE_COOLDOWN_TICKS;
      }
    } else {
      // back on the trail: drop any guide
      guide = null;
    }

    promptForActionIfNeeded();
    ThreadLocalRandom random = ThreadLocalRandom.current();
    render(playerVec, playerWorld, nextStepIsVanilla, probe, random);
  }

  /**
   * Measures the player's speed as how far they moved over the last {@link #SPEED_WINDOW_TICKS},
   * lightly smoothed.
   */
  private void updateSpeed(Vec3 playerVec) {
    Vec3 last = lastPlayerVec;
    lastPlayerVec = playerVec;
    if (last == null) {
      return;
    }
    double travelled = playerVec.minus(last).length();
    if (travelled > MAX_TRAVEL_PER_TICK) {
      recentTravel.clear(); // a teleport, not travel: start measuring again from here
      recentTravelTotal = 0;
      return;
    }
    recentTravel.addLast(travelled);
    recentTravelTotal += travelled;
    if (recentTravel.size() > SPEED_WINDOW_TICKS) {
      recentTravelTotal -= recentTravel.removeFirst();
    }
    double measured = recentTravelTotal / recentTravel.size() * TICKS_PER_SECOND;
    speed += SPEED_SMOOTHING * (measured - speed);
  }

  @Override
  public void update(Path<L, MinecraftStepPayload> newPath) {
    setPath(newPath); // live re-search hot-swap; the next tick re-advances from the player
  }

  @Override
  public void stop() {
    // Particles are transient (per-tick); only the label needs clearing.
    labels.detach(playerId(), this);
  }

  @Override
  public boolean isComplete() {
    return complete;
  }

  @Override
  public boolean consumeRecalcRequest() {
    boolean requested = recalcRequested;
    recalcRequested = false;
    return requested;
  }

  @Override
  public Optional<L> consumeGuideRequest() {
    if (!guideRequested || steps.isEmpty()) {
      return Optional.empty();
    }
    guideRequested = false;
    return Optional.of(steps.get(foremost).position()); // guide the player toward the current step
  }

  @Override
  public void setGuidePath(Path<L, MinecraftStepPayload> guide) {
    if (guide.steps().isEmpty()) {
      this.guide = null;
      return;
    }
    this.guide =
        new Guide<>(
            List.copyOf(guide.steps()),
            smoothedTrail(guide),
            worldKey(guide.steps().getFirst().position()));
  }

  @Override
  public double remainingSeconds() {
    double total = 0.0;
    for (int i = foremost; i < steps.size(); i++) {
      total += steps.get(i).time();
    }
    // Add an estimate for walking the guide-line back to the path: its length times the current
    // step's per-block time (we can't know the real terrain in between).
    if (!steps.isEmpty() && playerOnline()) {
      Vec3 playerVec = playerPoint();
      if (sameWorld(playerWorldKey(), worldKey(steps.get(foremost).position()))) {
        double distance = Math.sqrt(playerVec.minus(projectedTarget(playerVec)).lengthSquared());
        total += distance * steps.get(foremost).time();
      }
    }
    return total;
  }

  /**
   * The trail head: where the trip's label goes. Empty while labels are off, the player is off the
   * trail with no guide back yet, or the trail isn't drawn in their world.
   */
  Optional<L> labelPoint() {
    if (!showLabel.getAsBoolean() || complete || !playerOnline()) {
      return Optional.empty();
    }
    Vec3 head = trailHead;
    return head == null || !headFromPlayer ? Optional.empty() : Optional.of(location(head));
  }

  /**
   * The end of the route, if it is in the player's world: where the label naming the destination
   * goes, unless the destination says where it is. Empty while labels are off.
   */
  Optional<L> goalPoint() {
    if (!showLabel.getAsBoolean() || complete || steps.isEmpty() || !playerOnline()) {
      return Optional.empty();
    }
    if (!sameWorld(playerWorldKey(), worldKey(steps.getLast().position()))) {
      return Optional.empty();
    }
    return Optional.of(location(trail.original(trail.size() - 1)));
  }

  private boolean reachedGoal(Vec3 playerVec, String playerWorld) {
    Vec3 goal = trail.original(trail.size() - 1);
    return sameWorld(playerWorld, worldKey(steps.getLast().position()))
        && playerVec.minus(goal).lengthSquared() <= COMPLETION_RADIUS_SQUARED;
  }

  private void promptForActionIfNeeded() {
    if (lastPromptedIndex == foremost) {
      return;
    }
    MinecraftStepPayload payload = steps.get(foremost).payload();
    if (payload == null) {
      return;
    }
    MinecraftInstruction instruction = payload.instruction();
    if (instruction == null) {
      return;
    }
    lastPromptedIndex = foremost;
    // Do not prompt again if the starting prompt on this path is exactly what we've already seen,
    // potentially from a different path before it updated via a live search.
    if (foremost == 0
        && lastPromptedInstruction != null
        && lastPromptedInstruction.equals(instruction)) {
      return;
    }
    lastPromptedInstruction = instruction;
    // Only command instructions prompt; other kinds (e.g. None) render silently.
    if (instruction instanceof MinecraftInstruction.CommandInstruction commandInstruction) {
      String command = commandInstruction.command();
      // The whole line is clickable, so the player can run the command without typing it.
      audience()
          .sendMessage(
              messages
                  .render(locale, CobblestoneMessages.NAV_TRAIL_PROMPT_COMMAND, command)
                  .clickEvent(ClickEvent.runCommand(command))
                  .hoverEvent(
                      HoverEvent.showText(
                          Component.text(
                              messages.raw(
                                  locale, CobblestoneMessages.NAV_TRAIL_PROMPT_COMMAND_HOVER.key()),
                              CobblestoneColors.INFO))));
    }
  }

  /**
   * Draws this tick's trail: the current leg from its trail head (see the class description), the
   * legs past a teleport whole, and highlights and mine marks however close they are.
   */
  private void render(
      Vec3 playerVec,
      String playerWorld,
      boolean nextStepIsVanilla,
      TrailSmoother.BlockProbe probe,
      ThreadLocalRandom random) {
    int bufferEnd = Math.min(steps.size(), foremost + bufferCells);
    Guide<L> guide = nextStepIsVanilla ? this.guide : null;
    if (guide != null && sameWorld(playerWorld, guide.world())) {
      guide.trail().refresh(0, guide.trail().size() - 1, tickCounter, node -> true, probe);
      guide.trail().drawFrom(playerVec); // the guide leads from wherever the player now is
      renderMarks(guide.trail(), guide.steps(), 0, guide.steps().size(), playerVec, probe, random);
    } else {
      guide = null;
    }
    renderMarks(trail, steps, foremost, bufferEnd, playerVec, probe, random);

    Leg leg = leg(playerVec, playerWorld, guide, bufferEnd);
    trailHead = null;
    if (leg == null) {
      // Not on the trail's world: draw whatever of it is in the player's world, whole.
      renderWhole(foremost, bufferEnd, playerWorld, random);
      return;
    }
    if (leg.stretches().isEmpty()) {
      if (leg.teleport()) {
        renderWhole(leg.end() + 1, bufferEnd, playerWorld, random); // standing at the portal
      }
      return;
    }
    Vec3 eye = playerEye();
    double length = leg.length();
    double head = Math.min(Math.clamp(LEAD_SECONDS * speed, MIN_GAP, MAX_GAP), length);
    // Out of sight (around a corner, say): come back to just past the furthest point in sight.
    for (double arc = head; arc >= 0; arc -= 1.0) {
      if (LineOfSight.clear(eye, leg.at(arc), probe)) {
        head = Math.min(head, arc + STUB);
        break;
      }
    }
    double end = length;
    if (leg.toGoal() && LineOfSight.clear(eye, trail.node(trail.size() - 1), probe)) {
      end -= GOAL_CLEARANCE; // the destination's label takes it from here
    }
    trailHead = leg.at(head);
    headFromPlayer = leg.fromPlayer();
    double start = 0;
    for (Stretch stretch : leg.stretches()) {
      double stretchLength = stretch.length();
      double from = Math.max(head, start);
      double to = Math.min(end, start + stretchLength);
      if (stretch.drawn() && to > from && stretchLength > 0) {
        double fraction = stretch.from() + (1 - stretch.from()) * ((from - start) / stretchLength);
        double toFraction = stretch.from() + (1 - stretch.from()) * ((to - start) / stretchLength);
        scatter(stretch.path(), stretch.segment(), fraction, toFraction, to - from, random);
      }
      start += stretchLength;
    }
    if (leg.teleport()) {
      renderWhole(leg.end() + 1, bufferEnd, playerWorld, random);
    }
  }

  /**
   * The current leg: from the player along the guide back to the trail if they are off it and one
   * is drawn, otherwise from the point of the trail nearest them; then on along the trail until a
   * teleport, another world, the end of what is drawn, or the destination. Null if the trail's next
   * step is not in the player's world.
   */
  private Leg leg(Vec3 playerVec, String playerWorld, Guide<L> guide, int bufferEnd) {
    if (!sameWorld(playerWorld, worldKey(steps.get(foremost).position()))) {
      return null;
    }
    List<Stretch> stretches = new ArrayList<>();
    boolean offTrail =
        playerVec.minus(projectedTarget(playerVec)).lengthSquared() > CALC_GUIDE_THRESHOLD_SQUARED;
    int first = foremost;
    double from = projectedFraction(playerVec);
    if (offTrail && guide != null) {
      for (int i = 0; i < guide.steps().size(); i++) {
        MinecraftStepType type = guide.steps().get(i).payload().stepType();
        if (type == MinecraftStepType.TELEPORT || !guide.trail().segmentDrawable(i)) {
          return new Leg(stretches, true, false, foremost, false);
        }
        stretches.add(new Stretch(guide.trail(), i, 0, !type.isAction()));
      }
      // The guide ends at the current step's destination; carry on along the trail from there.
      first = foremost + 1;
      from = 0;
    }
    for (int i = first; i < bufferEnd; i++) {
      Step<L, MinecraftStepPayload> step = steps.get(i);
      MinecraftStepType type = step.payload().stepType();
      if (type == MinecraftStepType.TELEPORT) {
        return new Leg(stretches, !offTrail || guide != null, false, i, true);
      }
      if (!sameWorld(playerWorld, worldKey(step.position())) || !trail.segmentDrawable(i)) {
        return new Leg(stretches, !offTrail || guide != null, false, i, false);
      }
      stretches.add(new Stretch(trail, i, i == first ? from : 0, !type.isAction()));
    }
    boolean toGoal = bufferEnd == steps.size();
    return new Leg(stretches, !offTrail || guide != null, toGoal, bufferEnd, false);
  }

  /** Draws every walked step from {@code from} to {@code to} in the player's world, whole. */
  private void renderWhole(int from, int to, String playerWorld, ThreadLocalRandom random) {
    for (int i = Math.max(0, from); i < to; i++) {
      var step = steps.get(i);
      if (sameWorld(playerWorld, worldKey(step.position()))
          && !step.payload().stepType().isAction()
          && trail.segmentDrawable(i)) {
        scatter(trail, i, 0, 1, trail.segmentLength(i), random);
      }
    }
  }

  /**
   * Highlights where each action step of {@code path} from step {@code from} to {@code to} is
   * taken, and marks each block to mine.
   */
  private void renderMarks(
      SmoothedTrail path,
      List<Step<L, MinecraftStepPayload>> pathSteps,
      int from,
      int to,
      Vec3 playerVec,
      TrailSmoother.BlockProbe probe,
      ThreadLocalRandom random) {
    String playerWorld = playerWorldKey();
    for (int i = from; i < to; i++) {
      var step = pathSteps.get(i);
      MinecraftStepPayload payload = step.payload();
      if (!sameWorld(playerWorld, worldKey(step.position()))
          || payload.stepType().isAction()
          || !path.segmentDrawable(i)) {
        continue;
      }
      Vec3 destination = path.node(i + 1);
      if (playerVec.minus(destination).lengthSquared() < NEAR_BUFFER_SQUARED) {
        continue; // keep the player's immediate view clear
      }
      if (i + 1 < to && pathSteps.get(i + 1).payload().stepType().isAction()) {
        highlight(destination, random); // the next step is an action: take it here
      }
      if (payload.stepType() == MinecraftStepType.MINE) {
        var feet = BlockPos.of(path.original(i + 1));
        for (BlockPos mined : List.of(feet, feet.above())) {
          if (probe.at(mined.x(), mined.y(), mined.z()) == TrailBlock.SOLID) {
            renderMineMarker(mined, random);
          }
        }
      }
    }
  }

  /**
   * The world of trail node {@code node}: the origin's for node 0, else step {@code node - 1}'s.
   */
  private String trailNodeWorld(int node) {
    return node == 0 ? originWorld : worldKey(steps.get(node - 1).position());
  }

  /** The smoothed trail through {@code path}: node 0 is its origin, node i + 1 step i's end. */
  private SmoothedTrail smoothedTrail(Path<L, MinecraftStepPayload> path) {
    List<Step<L, MinecraftStepPayload>> pathSteps = path.steps();
    List<Vec3> nodes = new ArrayList<>(pathSteps.size() + 1);
    nodes.add(renderPoint(path.origin()));
    for (var step : pathSteps) {
      nodes.add(renderPoint(step.position()));
    }
    // A mined node stays at the block it mines, so the trail goes straight through it.
    return new SmoothedTrail(
        nodes,
        node -> rounds(pathSteps, node - 1),
        node -> pathSteps.get(node - 1).payload().stepType() == MinecraftStepType.MINE);
  }

  /**
   * Whether the corner at the destination of {@code steps[index]} is rounded: only between two
   * walked steps in the same world, never into or out of an action (e.g. a teleport).
   */
  private boolean rounds(List<Step<L, MinecraftStepPayload>> steps, int index) {
    if (index < 0 || index + 1 >= steps.size()) {
      return false;
    }
    var arriving = steps.get(index);
    var leaving = steps.get(index + 1);
    return !arriving.payload().stepType().isAction()
        && !leaving.payload().stepType().isAction()
        && Objects.equals(worldKey(arriving.position()), worldKey(leaving.position()));
  }

  /** A cage just outside the block plus an X on each face — "mine this block". */
  private void renderMineMarker(BlockPos block, ThreadLocalRandom random) {
    double h = 0.5 + MINE_MARGIN;
    double cx = block.x() + 0.5;
    double cy = block.y() + 0.5;
    double cz = block.z() + 0.5;
    // 6 faces of the cube.
    for (double sx : new double[] {-h, h}) {
      renderMineFace(random, cx + sx, cy - h, cz - h, cx + sx, cy + h, cz + h);
    }
    for (double sy : new double[] {-h, h}) {
      renderMineFace(random, cx - h, cy + sy, cz - h, cx + h, cy + sy, cz + h);
    }
    for (double sz : new double[] {-h, h}) {
      renderMineFace(random, cx - h, cy - h, cz + sz, cx + h, cy + h, cz + sz);
    }
  }

  private void renderMineFace(
      ThreadLocalRandom random, double x1, double y1, double z1, double x2, double y2, double z2) {
    int count = (int) density;
    if (random.nextDouble() < density - count) {
      count++;
    }
    for (int p = 0; p < count; p++) {
      spawnTrailParticle(
          x1 == x2 ? x1 : random.nextDouble(x1, x2),
          y1 == y2 ? y1 : random.nextDouble(y1, y2),
          z1 == z2 ? z1 : random.nextDouble(z1, z2),
          0,
          0,
          0);
    }
  }

  /**
   * Spawns ~{@code density} particles per block of {@code length} on segment {@code i} of {@code
   * path} as drawn, each at its own random point between fractions {@code from} and {@code to} of
   * it, Gaussian-scattered around it and flowing along it, so the drawn stretch fills evenly right
   * up to the trail head. A fractional count is probabilistic (0.7 → 70%).
   */
  private void scatter(
      SmoothedTrail path, int i, double from, double to, double length, ThreadLocalRandom random) {
    double floatCount = density * length;
    int count = (int) floatCount;
    if (random.nextDouble() < floatCount - count) {
      count++;
    }
    for (int p = 0; p < count; p++) {
      TrailCurve.Sample sample = path.sample(i, from + (to - from) * random.nextDouble());
      var center = sample.point();
      var velocity = sample.direction().times(PARTICLE_FLOW_SPEED);
      spawnTrailParticle(
          center.x() + random.nextGaussian() * SPREAD_HORIZONTAL,
          center.y() + random.nextGaussian() * SPREAD_VERTICAL,
          center.z() + random.nextGaussian() * SPREAD_HORIZONTAL,
          velocity.x(),
          velocity.y(),
          velocity.z());
    }
  }

  private void highlight(Vec3 center, ThreadLocalRandom random) {
    int count = (int) density;
    if (random.nextDouble() < density - count) {
      count++;
    }
    for (int p = 0; p < count; p++) {
      spawnHighlightParticle(
          center.x() + random.nextGaussian() * SPREAD_HORIZONTAL,
          center.y() + random.nextGaussian() * SPREAD_VERTICAL,
          center.z() + random.nextGaussian() * SPREAD_HORIZONTAL);
    }
  }

  /**
   * The nearest point on the current step's smoothed segment — so a slight deviation nudges back,
   * not rewinds. The segment runs from trail node {@code foremost} (the previous destination, or
   * the origin for step 0) to node {@code foremost + 1}.
   */
  private Vec3 projectedTarget(Vec3 playerVec) {
    Vec3 start = trail.node(foremost);
    Vec3 segment = trail.node(foremost + 1).minus(start);
    double t = projectedFraction(playerVec);
    return new Vec3(
        start.x() + segment.x() * t, start.y() + segment.y() * t, start.z() + segment.z() * t);
  }

  /** How far along the current step's segment {@link #projectedTarget} is, from 0 to 1. */
  private double projectedFraction(Vec3 playerVec) {
    Vec3 start = trail.node(foremost);
    Vec3 segment = trail.node(foremost + 1).minus(start);
    double lengthSquared = segment.lengthSquared();
    return lengthSquared == 0.0
        ? 0.0
        : Math.clamp(playerVec.minus(start).dot(segment) / lengthSquared, 0.0, 1.0);
  }

  /** Where the trail starts this tick, or null if it isn't drawn; for tests. */
  Vec3 trailHead() {
    return trailHead;
  }

  /** The trail's node {@code node} as smoothed so far (node 0 = the origin), for tests. */
  Vec3 trailNode(int node) {
    return trail.node(node);
  }

  private static boolean sameWorld(String playerWorld, String stepWorld) {
    return playerWorld != null && playerWorld.equals(stepWorld);
  }
}
