/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
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

  /**
   * A real short path back to the trail, smoothed like the trail.
   *
   * @param steps the guide path's steps
   * @param trail node 0 = the guide path's origin, node i + 1 = step i's destination
   * @param world the key of the guide path's world
   */
  private record Guide<L>(
      List<Step<L, MinecraftStepPayload>> steps, SmoothedTrail trail, String world) {}

  private final int bufferCells;
  private final double density;
  private final int recalcDistance;
  private final Messages messages;
  private final Locale locale;

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

  protected AbstractTrailNavigator(
      int bufferCells, double density, int recalcDistance, Messages messages, Locale locale) {
    this.bufferCells = bufferCells;
    this.density = Math.max(0.0, density);
    this.recalcDistance = recalcDistance;
    this.messages = messages;
    this.locale = locale;
  }

  // --- platform hooks -------------------------------------------------------

  /** Whether the guided player is still connected. */
  protected abstract boolean playerOnline();

  /** The player's exact current position (only queried while online). */
  protected abstract Vec3 playerPoint();

  /** The key of the player's current world, or {@code null} if unresolved. */
  protected abstract String playerWorldKey();

  /** The block-centerd render point (raised toward body height) for a step location. */
  protected abstract Vec3 renderPoint(L location);

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
    // Nothing to set up; the first tick renders the trail.
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
    renderTrail(playerVec, playerWorld, probe, random);
    if (nextStepIsVanilla) {
      renderGuide(playerVec, playerWorld, probe, random);
    }
  }

  @Override
  public void update(Path<L, MinecraftStepPayload> newPath) {
    setPath(newPath); // live re-search hot-swap; the next tick re-advances from the player
  }

  @Override
  public void stop() {
    // Particles are transient (per-tick), so there is nothing to clear.
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

  private void renderTrail(
      Vec3 playerVec,
      String playerWorld,
      TrailSmoother.BlockProbe probe,
      ThreadLocalRandom random) {
    int end = Math.min(steps.size(), foremost + bufferCells);
    for (int i = foremost; i < end; i++) {
      var step = steps.get(i);
      if (!sameWorld(playerWorld, worldKey(step.position()))) {
        continue;
      }
      MinecraftStepPayload payload = step.payload();
      if (payload.stepType().isAction()) {
        // no trail to a cell if we get there by performing an action
        continue;
      }
      boolean highlight = false;
      if (i == steps.size() - 1) {
        // the end should be highlighted
        highlight = true;
      } else if (i + 1 < end && steps.get(i + 1).payload().stepType().isAction()) {
        // if the next step is an action, highlight this step
        highlight = true;
      }
      // Step i is the trail's segment i; skip it where the world isn't readable.
      if (trail.segmentDrawable(i)) {
        renderStep(trail, i, payload, highlight, playerVec, probe, random);
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

  /**
   * Draws step {@code i} of {@code path} (its segment {@code i}), a highlight at its destination if
   * asked, and a mine marker on its unsmoothed block if it mines.
   */
  private void renderStep(
      SmoothedTrail path,
      int i,
      MinecraftStepPayload payload,
      boolean highlight,
      Vec3 playerVec,
      TrailSmoother.BlockProbe probe,
      ThreadLocalRandom random) {
    Vec3 destination = path.node(i + 1);
    if (playerVec.minus(destination).lengthSquared() < NEAR_BUFFER_SQUARED) {
      return; // keep the player's immediate view clear
    }
    scatter(path, i, random);
    if (highlight) {
      highlight(destination, random);
    }

    if (payload != null && payload.stepType() == MinecraftStepType.MINE) {
      var feet = BlockPos.of(path.original(i + 1));
      for (BlockPos mined : List.of(feet, feet.above())) {
        if (probe.at(mined.x(), mined.y(), mined.z()) == TrailBlock.SOLID) {
          renderMineMarker(mined, random);
        }
      }
    }
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

  private void renderGuide(
      Vec3 playerVec,
      String playerWorld,
      TrailSmoother.BlockProbe probe,
      ThreadLocalRandom random) {
    Guide<L> guide = this.guide;
    if (guide == null
        || !sameWorld(playerWorld, worldKey(steps.get(foremost).position()))
        || !sameWorld(playerWorld, guide.world())) {
      // No fallback while the guide search is pending/failed
      return;
    }
    List<Step<L, MinecraftStepPayload>> guideSteps = guide.steps();
    SmoothedTrail path = guide.trail();
    path.refresh(0, path.size() - 1, tickCounter, node -> true, probe);
    path.drawFrom(playerVec); // the guide leads from wherever the player now is
    for (int i = 0; i < guideSteps.size(); i++) {
      if (path.segmentDrawable(i)) {
        renderStep(path, i, guideSteps.get(i).payload(), false, playerVec, probe, random);
      }
    }
  }

  /**
   * Spawns ~{@code density} Gaussian-scattered particles per block around a random point on segment
   * {@code i} of {@code path} as drawn, flowing along it. A fractional density is probabilistic
   * (0.7 → 70%).
   */
  private void scatter(SmoothedTrail path, int i, ThreadLocalRandom random) {
    double floatCount = density * path.segmentLength(i);
    int count = (int) floatCount;
    if (random.nextDouble() < floatCount - count) {
      count++;
    }
    TrailCurve.Sample sample = path.sample(i, random.nextDouble());
    var center = sample.point();
    var velocity = sample.direction().times(PARTICLE_FLOW_SPEED);
    for (int p = 0; p < count; p++) {
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
    double lengthSquared = segment.lengthSquared();
    double t =
        lengthSquared == 0.0
            ? 0.0
            : Math.clamp(playerVec.minus(start).dot(segment) / lengthSquared, 0.0, 1.0);
    return new Vec3(
        start.x() + segment.x() * t, start.y() + segment.y() * t, start.z() + segment.z() * t);
  }

  /** The trail's node {@code node} as smoothed so far (node 0 = the origin), for tests. */
  Vec3 trailNode(int node) {
    return trail.node(node);
  }

  private static boolean sameWorld(String playerWorld, String stepWorld) {
    return playerWorld != null && playerWorld.equals(stepWorld);
  }
}
