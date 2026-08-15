/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.plugin.api.Navigator;
import net.whimxiqal.odyssey.plugin.message.Messages;
import net.whimxiqal.odyssey.plugin.message.OdysseyMessages;
import net.whimxiqal.odyssey.plugin.navigator.TrailProgress;
import net.whimxiqal.odyssey.plugin.navigator.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * The default {@code trail} navigator: a column of dust particles along the path ahead of the
 * player (shown only to them). Particles are scattered around each cell with a Gaussian falloff
 * (dense on the path, sparse at the edges) and each takes a random color from the configured
 * palette (aqua, gold, white by default — discrete, not blended, so they sparkle). It advances via
 * {@link TrailProgress} (parallel projection, so walking <i>alongside</i> counts), draws a
 * same-width guide column back to the nearest point on the path ahead when the player drifts off,
 * prompts on discrete-action steps, and auto-abandons the trip if the player strays past {@code
 * abandonDistance}.
 *
 * <p>A 1-block "personal bubble" around the player is left clear so particles don't render in the
 * player's face.
 *
 * <p>Rendering is Paper-specific and verified on a live server; the follow geometry is unit-tested
 * in plugin-core.
 */
final class TrailNavigator implements Navigator<Location> {

  private static final double COMPLETION_RADIUS_SQUARED = 4.0; // within 2 blocks of the goal
  private static final double GUIDE_LINE_SPACING = 1.0; // ~1 block between guide-line samples
  private static final double SPREAD_HORIZONTAL = 0.30; // Gaussian sigma across the column
  private static final double SPREAD_VERTICAL = 0.20;
  private static final double NEAR_BUFFER = 1.0; // clear bubble around the player, in blocks
  private static final double NEAR_BUFFER_SQUARED = NEAR_BUFFER * NEAR_BUFFER;
  private static final double CALC_GUIDE_THRESHOLD = NEAR_BUFFER + 1.0;
  private static final double CALC_GUIDE_THRESHOLD_SQUARED =
      CALC_GUIDE_THRESHOLD * CALC_GUIDE_THRESHOLD;
  private static final int RECALC_COOLDOWN_TICKS = 20; // at most one stray-recalc per second
  private static final int GUIDE_COOLDOWN_TICKS = 20; // re-request the guide path ~1x/second
  private static final float DUST_SIZE = 1.0f;
  private static final double MINE_MARGIN = 0.05; // cage hugs the block to mine

  /** Identifies a path step by its exact block, for the "player stood on a later step" shortcut. */
  private record BlockKey(int x, int y, int z) {}

  private final Player player;
  private final int bufferCells;
  private final List<Particle> particles;
  private final List<Particle.DustOptions> dusts;
  private final double density;
  private final int recalcDistance;
  private final Messages messages;
  private final Locale locale;

  private List<Step<Location, MinecraftStepPayload>> steps;
  private List<Vec3> points; // step destinations, index-aligned with steps
  private Vec3
      origin; // where step 0 departs from (the player's start); the segment before points[0]
  private Map<BlockKey, Integer> stepByBlock;
  private int foremost; // the step the player still needs to complete (0 = the first step)
  private int lastPromptedIndex = -1;
  private boolean complete;
  private boolean recalcRequested;
  private int recalcCooldown;
  private int tickCounter;
  private boolean guideRequested;
  private int guideCooldown;
  private List<Step<Location, MinecraftStepPayload>> guideSteps;
  private List<Vec3> guidePoints; // a real short path back to the trail; null when on-trail
  private String guideWorld; // world key of the current guide path\
  // record last prompted instruction so we don't repeat ourselves on a recalculation
  private MinecraftInstruction lastPromptedInstruction;

  TrailNavigator(
      Player player,
      Path<Location, MinecraftStepPayload> path,
      int bufferCells,
      List<Particle> particleTypes,
      List<Color> palette,
      double density,
      int recalcDistance,
      Messages messages) {
    this.player = player;
    this.bufferCells = bufferCells;
    this.particles = particleTypes.isEmpty() ? List.of(Particle.DUST) : List.copyOf(particleTypes);
    List<Color> colors = palette.isEmpty() ? List.of(Color.AQUA) : palette;
    this.dusts = new ArrayList<>(colors.size());
    for (Color color : colors) {
      dusts.add(new Particle.DustOptions(color, DUST_SIZE));
    }
    this.density = Math.max(0.0, density);
    this.recalcDistance = recalcDistance;
    this.messages = messages;
    this.locale = player.locale();
    setPath(path);
  }

  private void setPath(Path<Location, MinecraftStepPayload> path) {
    this.steps = path.steps();
    // points are the step destinations, index-aligned with steps; the origin (where step 0 departs
    // from) is tracked separately. So foremost == i means "step i is not yet completed" — no step
    // is
    // credited until the player actually projects past its destination.
    this.origin = pathLocationToRenderPoint(path.origin());
    this.points = new ArrayList<>(steps.size());
    this.stepByBlock = new HashMap<>();
    for (int i = 0; i < steps.size(); i++) {
      Location location = steps.get(i).position();
      // Block centers, so projection and rendering share the same reference points.
      points.add(pathLocationToRenderPoint(location));
      // Highest index wins, so standing on a repeated block jumps to the furthest occurrence.
      stepByBlock.put(
          new BlockKey(location.getBlockX(), location.getBlockY(), location.getBlockZ()), i);
    }
    this.foremost = 0;
    this.lastPromptedIndex = -1;
    this.recalcCooldown = 0;
    this.complete = steps.isEmpty();

    // reset guide steps for new main path
    guideSteps = null;
    guidePoints = null;
  }

  @Override
  public void start() {
    // Nothing to set up; the first tick renders the trail.
  }

  @Override
  public void tick() {
    if (complete || !player.isOnline() || steps.isEmpty()) {
      return;
    }
    tickCounter++;
    if (recalcCooldown > 0) {
      recalcCooldown--;
    }
    if (guideCooldown > 0) {
      guideCooldown--;
    }
    Location playerLocation = player.getLocation();
    World playerWorld = playerLocation.getWorld();
    Vec3 playerVec = new Vec3(playerLocation.getX(), playerLocation.getY(), playerLocation.getZ());
    boolean onTrailWorld = sameWorld(playerWorld, steps.get(foremost).position());

    // Advance only in the trail head's world; a cross-domain hop is handled by the action prompt.
    if (onTrailWorld) {
      // advance returns points.size() once every step is done; clamp so it stays a valid step
      // index.
      foremost =
          Math.min(TrailProgress.advance(points, origin, foremost, playerVec), steps.size() - 1);
    }
    // Shortcut: if the player is standing exactly on a later step within the buffer (e.g. they
    // cut
    // a curve the projection didn't credit), jump the trail forward to it. Standing on step i
    // means
    // the player has reached point i + 1.
    Integer atBlock =
        stepByBlock.get(
            new BlockKey(
                playerLocation.getBlockX(),
                playerLocation.getBlockY(),
                playerLocation.getBlockZ()));
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
      guideSteps = null;
      guidePoints = null;
    }

    promptForActionIfNeeded();
    ThreadLocalRandom random = ThreadLocalRandom.current();
    renderTrail(playerVec, playerWorld, random);
    if (nextStepIsVanilla) {
      renderGuide(playerVec, playerWorld, random);
    }
  }

  @Override
  public void update(Path<Location, MinecraftStepPayload> newPath) {
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
  public Optional<Location> consumeGuideRequest() {
    if (!guideRequested || steps.isEmpty()) {
      return Optional.empty();
    }
    guideRequested = false;
    return Optional.of(steps.get(foremost).position()); // guide the player toward the current step
  }

  private Vec3 pathLocationToRenderPoint(Location location) {
    // Y is raised by 0.9 instead of 0.5 so it's in the center of the player's body-ish
    return new Vec3(
        location.getBlockX() + 0.5, location.getBlockY() + 0.9, location.getBlockZ() + 0.5);
  }

  @Override
  public void setGuidePath(Path<Location, MinecraftStepPayload> guide) {
    if (guide.steps().isEmpty()) {
      guideSteps = null;
      guidePoints = null;
      return;
    }
    guideSteps = List.copyOf(guide.steps());
    List<Vec3> pts = new ArrayList<>(guideSteps.size());
    for (Step<Location, MinecraftStepPayload> step : guideSteps) {
      Location location = step.position();
      pts.add(pathLocationToRenderPoint(location));
    }
    World world = guideSteps.getFirst().position().getWorld();
    guideWorld = world == null ? null : world.getKey().asString();
    guidePoints = pts;
  }

  @Override
  public double remainingSeconds() {
    double total = 0.0;
    for (int i = foremost; i < steps.size(); i++) {
      total += steps.get(i).time();
    }
    // Add an estimate for walking the guide-line back to the path: its length times the current
    // step's per-block time (we can't know the real terrain in between).
    if (!steps.isEmpty() && player.isOnline()) {
      Location location = player.getLocation();
      if (sameWorld(location.getWorld(), steps.get(foremost).position())) {
        Vec3 playerVec = new Vec3(location.getX(), location.getY(), location.getZ());
        double distance = Math.sqrt(playerVec.minus(projectedTarget(playerVec)).lengthSquared());
        total += distance * steps.get(foremost).time();
      }
    }
    return total;
  }

  private boolean reachedGoal(Vec3 playerVec, World playerWorld) {
    Vec3 goal = points.getLast();
    return sameWorld(playerWorld, steps.getLast().position())
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
    // do not prompt again if the starting prompt on this path is exactly the same as what we've
    // already seen,
    // potentially from a different path before it updated via a live search
    if (foremost == 0
        && lastPromptedInstruction != null
        && lastPromptedInstruction.equals(instruction)) {
      return;
    }
    lastPromptedInstruction = instruction;
    switch (instruction) {
      case MinecraftInstruction.CommandInstruction commandInstruction -> {
        messages.send(
            player, locale, OdysseyMessages.NAV_TRAIL_PROMPT_COMMAND, commandInstruction.command());
      }
      case MinecraftInstruction.None _ -> {
        // do nothing
      }
    }
  }

  private void renderTrail(Vec3 playerVec, World playerWorld, ThreadLocalRandom random) {
    int end = Math.min(steps.size(), foremost + bufferCells);
    for (int i = foremost; i < end; i++) {
      Location location = steps.get(i).position();
      if (!sameWorld(playerWorld, location)) {
        continue;
      }
      Vec3 center = points.get(i);
      MinecraftStepPayload payload = steps.get(i).payload();
      boolean highlight = false;
      if (i == steps.size() - 1) {
        // the end should be highlighted
        highlight = true;
      } else if (i + 1 < end && steps.get(i + 1).payload().stepType().isAction()) {
        // if the next step is an action, highlight this step
        highlight = true;
      }
      renderBlock(playerWorld, location.toVector(), center, playerVec, payload, highlight, random);
    }
  }

  private void renderBlock(
      World world,
      Vector locationVector,
      Vec3 center,
      Vec3 playerVec,
      MinecraftStepPayload payload,
      boolean highlight,
      ThreadLocalRandom random) {
    if (playerVec.minus(center).lengthSquared() < NEAR_BUFFER_SQUARED) {
      return; // keep the player's immediate view clear
    }
    scatter(world, center, highlight, random);

    if (payload != null && payload.stepType() == MinecraftStepType.MINE) {
      Block block =
          world.getBlockAt(
              locationVector.getBlockX(), locationVector.getBlockY(), locationVector.getBlockZ());
      if (Bukkit.isOwnedByCurrentRegion(block) && block.getType().isSolid()) {
        renderMineMarker(world, locationVector, random);
      }
      Vector upOne = locationVector.add(new Vector(0, 1, 0));
      Block aboveBlock = world.getBlockAt(upOne.getBlockX(), upOne.getBlockY(), upOne.getBlockZ());
      if (Bukkit.isOwnedByCurrentRegion(aboveBlock) && aboveBlock.getType().isSolid()) {
        renderMineMarker(world, upOne, random);
      }
    }
  }

  /** A cage just outside the block plus an X on each face — "mine this block". */
  private void renderMineMarker(World world, Vector locationVector, ThreadLocalRandom random) {
    double h = 0.5 + MINE_MARGIN;
    double cx = locationVector.getBlockX() + 0.5;
    double cy = locationVector.getBlockY() + 0.5;
    double cz = locationVector.getBlockZ() + 0.5;
    // 6 faces of the cube.
    for (double sx : new double[] {-h, h}) {
      renderMineFace(random, world, cx + sx, cy - h, cz - h, cx + sx, cy + h, cz + h);
    }
    for (double sy : new double[] {-h, h}) {
      renderMineFace(random, world, cx - h, cy + sy, cz - h, cx + h, cy + sy, cz + h);
    }
    for (double sz : new double[] {-h, h}) {
      renderMineFace(random, world, cx - h, cy - h, cz + sz, cx + h, cy + h, cz + sz);
    }
  }

  private void renderMineFace(
      ThreadLocalRandom random,
      World world,
      double x1,
      double y1,
      double z1,
      double x2,
      double y2,
      double z2) {
    int count = (int) density;
    if (random.nextDouble() < density - count) {
      count++;
    }
    for (int p = 0; p < count; p++) {
      Location dot =
          new Location(
              world,
              x1 == x2 ? x1 : random.nextDouble(x1, x2),
              y1 == y2 ? y1 : random.nextDouble(y1, y2),
              z1 == z2 ? z1 : random.nextDouble(z1, z2));
      renderTrailParticle(random, dot);
    }
  }

  private void renderGuide(Vec3 playerVec, World playerWorld, ThreadLocalRandom random) {
    if (!sameWorld(playerWorld, steps.get(foremost).position())) {
      return;
    }
    // Prefer the real short guide path (computed by the trip) if we have one for this world.
    if (guidePoints == null
        || guideWorld == null
        || guideWorld.equals(playerWorld.getKey().asString())) {
      // No fallback while the guide search is pending/failed
      return;
    }
    for (int i = 0; i < guidePoints.size(); i++) {
      Vec3 point = guidePoints.get(i);
      MinecraftStepPayload payload = guideSteps.get(i).payload();
      renderBlock(
          playerWorld,
          new Vector(point.x(), point.y(), point.z()),
          point,
          playerVec,
          payload,
          false,
          random);
    }
  }

  /**
   * Spawns ~{@code density} Gaussian-scattered particles around a center, each a random configured
   * type (DUST takes a random palette color). A fractional density is probabilistic (0.7 → 70%).
   */
  private void scatter(World world, Vec3 center, boolean highlight, ThreadLocalRandom random) {
    double modifiedDensity = density;
    if (highlight) {
      modifiedDensity *= 2;
    }
    int count = (int) modifiedDensity;
    if (random.nextDouble() < modifiedDensity - count) {
      count++;
    }
    for (int p = 0; p < count; p++) {
      Location dot =
          new Location(
              world,
              center.x() + random.nextGaussian() * SPREAD_HORIZONTAL,
              center.y() + random.nextGaussian() * SPREAD_VERTICAL,
              center.z() + random.nextGaussian() * SPREAD_HORIZONTAL);
      renderTrailParticle(random, dot);
    }
  }

  private void renderTrailParticle(ThreadLocalRandom random, Location location) {
    if (!Bukkit.isOwnedByCurrentRegion(location)) {
      return;
    }
    Particle particle = particles.get(random.nextInt(particles.size()));
    if (particle == Particle.DUST) {
      Particle.DUST
          .builder()
          .location(location)
          .receivers(player)
          .data(dusts.get(random.nextInt(dusts.size())))
          .spawn();
    } else {
      particle.builder().location(location).receivers(player).spawn();
    }
  }

  /**
   * The nearest point on the current step's segment — so a slight deviation nudges back, not
   * rewinds. The segment runs from the previous destination (or the origin for step 0) to {@code
   * points[foremost]}.
   */
  private Vec3 projectedTarget(Vec3 playerVec) {
    Vec3 start = foremost == 0 ? origin : points.get(foremost - 1);
    Vec3 segment = points.get(foremost).minus(start);
    double lengthSquared = segment.lengthSquared();
    double t =
        lengthSquared == 0.0
            ? 0.0
            : Math.clamp(playerVec.minus(start).dot(segment) / lengthSquared, 0.0, 1.0);
    return new Vec3(
        start.x() + segment.x() * t, start.y() + segment.y() * t, start.z() + segment.z() * t);
  }

  private static boolean sameWorld(World playerWorld, Location stepLocation) {
    return playerWorld != null && playerWorld.equals(stepLocation.getWorld());
  }
}
