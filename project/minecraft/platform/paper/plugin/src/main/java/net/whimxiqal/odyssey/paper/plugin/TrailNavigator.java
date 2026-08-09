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
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * The default {@code trail} navigator: a column of dust particles along the path ahead of the player
 * (shown only to them). Particles are scattered around each cell with a Gaussian falloff (dense on
 * the path, sparse at the edges) and each takes a random color from the configured palette (aqua,
 * gold, white by default — discrete, not blended, so they sparkle). It advances via
 * {@link TrailProgress} (parallel projection, so walking <i>alongside</i> counts), draws a same-width
 * guide column back to the nearest point on the path ahead when the player drifts off, prompts on
 * discrete-action steps, and auto-abandons the trip if the player strays past {@code abandonDistance}.
 *
 * <p>A 1-block "personal bubble" around the player is left clear so particles don't render in the
 * player's face.
 *
 * <p>Rendering is Paper-specific and verified on a live server; the follow geometry is unit-tested in
 * plugin-core.
 */
final class TrailNavigator implements Navigator<Location> {

  private static final double COMPLETION_RADIUS_SQUARED = 4.0; // within 2 blocks of the goal
  private static final double GUIDE_LINE_SPACING = 1.0;        // ~1 block between guide-line samples
  private static final double SPREAD_HORIZONTAL = 0.30;        // Gaussian sigma across the column
  private static final double SPREAD_VERTICAL = 0.20;
  private static final double NEAR_BUFFER = 1.0;               // clear bubble around the player, in blocks
  private static final double NEAR_BUFFER_SQUARED = NEAR_BUFFER * NEAR_BUFFER;
  private static final int RECALC_COOLDOWN_TICKS = 20;         // at most one stray-recalc per second
  private static final int GUIDE_COOLDOWN_TICKS = 20;          // re-request the guide path ~1x/second
  private static final float DUST_SIZE = 1.0f;
  private static final Particle.DustOptions MINE_DUST = new Particle.DustOptions(Color.RED, 1.0f);
  private static final double MINE_MARGIN = 0.05;             // cage hugs the block to mine
  private static final double MINE_SPACING = 0.25;            // particle spacing along the marker lines
  private static final int MINE_FLASH_PERIOD = 16;           // ~0.8s: flash the cage rather than spam it

  /**
   * Identifies a path step by its exact block, for the "player stood on a later step" shortcut.
   */
  private record BlockKey(int x, int y, int z) {
  }

  private final Player player;
  private final int bufferCells;
  private final List<Particle> particles;
  private final List<Particle.DustOptions> dusts;
  private final double density;
  private final int recalcDistance;
  private final Messages messages;
  private final Locale locale;

  private List<Step<Location, MinecraftStepPayload>> steps;
  private List<Vec3> points;
  private Map<BlockKey, Integer> stepByBlock;
  private int foremost;
  private int lastPromptedIndex = -1;
  private boolean complete;
  private boolean recalcRequested;
  private int recalcCooldown;
  private int tickCounter;
  private boolean guideRequested;
  private int guideCooldown;
  private List<Vec3> guidePoints;   // a real short path back to the trail; null when on-trail
  private String guideWorld;        // world key of the current guide path

  TrailNavigator(
      Player player,
      Path<Step<Location, MinecraftStepPayload>> path,
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

  private void setPath(Path<Step<Location, MinecraftStepPayload>> path) {
    this.steps = path.steps();
    this.points = new ArrayList<>(steps.size());
    this.stepByBlock = new HashMap<>();
    for (int i = 0; i < steps.size(); i++) {
      Location location = steps.get(i).position();
      // Block centres, so projection and rendering share the same reference points.
      points.add(new Vec3(location.getBlockX() + 0.5, location.getBlockY() + 0.5, location.getBlockZ() + 0.5));
      // Highest index wins, so standing on a repeated block jumps to the furthest occurrence.
      stepByBlock.put(new BlockKey(location.getBlockX(), location.getBlockY(), location.getBlockZ()), i);
    }
    this.foremost = 0;
    this.lastPromptedIndex = -1;
    this.recalcCooldown = 0;
    this.complete = steps.isEmpty();
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
      foremost = TrailProgress.advance(points, foremost, playerVec);
      // Shortcut: if the player is standing exactly on a later step within the buffer (e.g. they cut
      // a curve the projection didn't credit), jump the trail forward to it.
      Integer atBlock = stepByBlock.get(new BlockKey(
          playerLocation.getBlockX(), playerLocation.getBlockY(), playerLocation.getBlockZ()));
      if (atBlock != null && atBlock > foremost && atBlock <= foremost + bufferCells) {
        foremost = atBlock;
      }
    }

    if (reachedGoal(playerVec, playerWorld)) {
      complete = true;
      return;
    }

    double deviationSquared = onTrailWorld
        ? playerVec.minus(projectedTarget(playerVec)).lengthSquared() : 0.0;
    // Strayed too far: quietly ask the trip to recalculate from the player's new position.
    if (onTrailWorld && recalcDistance > 0 && recalcCooldown <= 0
        && deviationSquared > (double) recalcDistance * recalcDistance) {
      recalcRequested = true;
      recalcCooldown = RECALC_COOLDOWN_TICKS;
    }
    // Drifted off the path: periodically request a short guide path back to the current step.
    if (onTrailWorld && deviationSquared > NEAR_BUFFER_SQUARED) {
      if (guideCooldown <= 0) {
        guideRequested = true;
        guideCooldown = GUIDE_COOLDOWN_TICKS;
      }
    } else {
      guidePoints = null; // back on the trail: drop any guide
    }

    promptForActionIfNeeded();
    ThreadLocalRandom random = ThreadLocalRandom.current();
    renderTrail(playerVec, playerWorld, random);
    renderGuide(playerVec, playerWorld, random);
  }

  @Override
  public void update(Path<Step<Location, MinecraftStepPayload>> newPath) {
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

  @Override
  public void setGuidePath(Path<Step<Location, MinecraftStepPayload>> guide) {
    List<Step<Location, MinecraftStepPayload>> guideSteps = guide.steps();
    if (guideSteps.isEmpty()) {
      guidePoints = null;
      return;
    }
    List<Vec3> pts = new ArrayList<>(guideSteps.size());
    for (Step<Location, MinecraftStepPayload> step : guideSteps) {
      Location location = step.position();
      pts.add(new Vec3(location.getBlockX() + 0.5, location.getBlockY() + 0.5, location.getBlockZ() + 0.5));
    }
    World world = guideSteps.get(0).position().getWorld();
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
    if (foremost < points.size() - 1) {
      return false;
    }
    Vec3 goal = points.get(points.size() - 1);
    return sameWorld(playerWorld, steps.get(steps.size() - 1).position())
        && playerVec.minus(goal).lengthSquared() <= COMPLETION_RADIUS_SQUARED;
  }

  private void promptForActionIfNeeded() {
    int next = foremost + 1;
    if (next >= steps.size() || lastPromptedIndex == foremost) {
      return;
    }
    MinecraftStepPayload payload = steps.get(next).payload();
    if (payload == null || !isAction(payload.stepType())) {
      return;
    }
    lastPromptedIndex = foremost;
    if (payload.instruction() instanceof MinecraftInstruction.CommandInstruction(String command)) {
      messages.send(player, locale, OdysseyMessages.NAV_TRAIL_PROMPT_COMMAND, command);
    } else {
      messages.send(player, locale, OdysseyMessages.NAV_TRAIL_PROMPT_ACTION);
    }
  }

  private void renderTrail(Vec3 playerVec, World playerWorld, ThreadLocalRandom random) {
    int end = Math.min(steps.size(), foremost + bufferCells);
    for (int i = foremost; i < end; i++) {
      Location location = steps.get(i).position();
      if (!sameWorld(playerWorld, location)) {
        continue;
      }
      Vec3 centre = points.get(i);
      if (playerVec.minus(centre).lengthSquared() < NEAR_BUFFER_SQUARED) {
        continue; // keep the player's immediate view clear
      }
      MinecraftStepPayload payload = steps.get(i).payload();
      if (payload != null && payload.stepType() == MinecraftStepType.MINE
          && playerWorld.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ())
          .getType().isSolid()) {
        // Still a solid block to dig: flash a distinct red cage (rather than spam particles).
        if (tickCounter % MINE_FLASH_PERIOD == 0) {
          renderMineMarker(playerWorld, centre);
        }
      } else {
        // Not a mine step, or the block has been broken — render as normal trail.
        scatter(playerWorld, centre, random);
      }
    }
  }

  /**
   * A red wireframe cage just outside the block plus an X on each face — "mine this block".
   */
  private void renderMineMarker(World world, Vec3 centre) {
    double h = 0.5 + MINE_MARGIN;
    double cx = centre.x();
    double cy = centre.y();
    double cz = centre.z();
    // 12 edges of the cube.
    for (double sy : new double[]{-h, h}) {
      for (double sz : new double[]{-h, h}) {
        redLine(world, cx - h, cy + sy, cz + sz, cx + h, cy + sy, cz + sz);
      }
    }
    for (double sx : new double[]{-h, h}) {
      for (double sz : new double[]{-h, h}) {
        redLine(world, cx + sx, cy - h, cz + sz, cx + sx, cy + h, cz + sz);
      }
    }
    for (double sx : new double[]{-h, h}) {
      for (double sy : new double[]{-h, h}) {
        redLine(world, cx + sx, cy + sy, cz - h, cx + sx, cy + sy, cz + h);
      }
    }
  }

  private void redLine(World world, double x1, double y1, double z1, double x2, double y2, double z2) {
    double dx = x2 - x1;
    double dy = y2 - y1;
    double dz = z2 - z1;
    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
    int dots = Math.max(1, (int) (length / MINE_SPACING));
    for (int i = 0; i <= dots; i++) {
      double t = i / (double) dots;
      Particle.DUST.builder()
          .location(new Location(world, x1 + dx * t, y1 + dy * t, z1 + dz * t))
          .receivers(player)
          .color(255, 0, 0)
          .count(0)
          .extra(0)
          .spawn();
    }
  }

  private void renderGuide(Vec3 playerVec, World playerWorld, ThreadLocalRandom random) {
    if (!sameWorld(playerWorld, steps.get(foremost).position())) {
      return;
    }
    // Prefer the real short guide path (computed by the trip) if we have one for this world.
    if (guidePoints != null && guideWorld != null && guideWorld.equals(playerWorld.getKey().asString())) {
      for (Vec3 point : guidePoints) {
        if (playerVec.minus(point).lengthSquared() < NEAR_BUFFER_SQUARED) {
          continue;
        }
        scatter(playerWorld, point, random);
      }
      return;
    }
    // Fallback while the guide search is pending/failed: a straight column toward the path.
    Vec3 target = projectedTarget(playerVec);
    Vec3 delta = target.minus(playerVec);
    double distance = Math.sqrt(delta.lengthSquared());
    if (distance <= NEAR_BUFFER) {
      return; // on or beside the path: nothing to draw
    }
    int dots = (int) (distance / GUIDE_LINE_SPACING);
    for (int i = 1; i <= dots; i++) {
      double t = i / (double) dots;
      Vec3 point = new Vec3(
          playerVec.x() + delta.x() * t, playerVec.y() + delta.y() * t, playerVec.z() + delta.z() * t);
      if (playerVec.minus(point).lengthSquared() < NEAR_BUFFER_SQUARED) {
        continue;
      }
      scatter(playerWorld, point, random);
    }
  }

  /**
   * Spawns ~{@code density} Gaussian-scattered particles around a centre, each a random configured
   * type (DUST takes a random palette color). A fractional density is probabilistic (0.7 → 70%).
   */
  private void scatter(World world, Vec3 centre, ThreadLocalRandom random) {
    int count = (int) density;
    if (random.nextDouble() < density - count) {
      count++;
    }
    for (int p = 0; p < count; p++) {
      Location dot = new Location(world,
          centre.x() + random.nextGaussian() * SPREAD_HORIZONTAL,
          centre.y() + random.nextGaussian() * SPREAD_VERTICAL,
          centre.z() + random.nextGaussian() * SPREAD_HORIZONTAL);
      Particle particle = particles.get(random.nextInt(particles.size()));
      if (particle == Particle.DUST) {
        Particle.DUST.builder()
            .location(dot)
            .receivers(player)
            .data(dusts.get(random.nextInt(dusts.size())))
            .spawn();
      } else {
        particle.builder()
            .location(dot)
            .receivers(player)
            .spawn();
      }
    }
  }

  /**
   * The nearest point on the current segment ahead — so a slight deviation nudges back, not rewinds.
   */
  private Vec3 projectedTarget(Vec3 playerVec) {
    Vec3 start = points.get(foremost);
    if (foremost + 1 >= points.size()) {
      return start;
    }
    Vec3 segment = points.get(foremost + 1).minus(start);
    double lengthSquared = segment.lengthSquared();
    double t = lengthSquared == 0.0
        ? 0.0 : Math.clamp(playerVec.minus(start).dot(segment) / lengthSquared, 0.0, 1.0);
    return new Vec3(start.x() + segment.x() * t, start.y() + segment.y() * t, start.z() + segment.z() * t);
  }

  private static boolean sameWorld(World playerWorld, Location stepLocation) {
    return playerWorld != null && playerWorld.equals(stepLocation.getWorld());
  }

  private static boolean isAction(MinecraftStepType type) {
    return switch (type) {
      case OPEN_DOOR, PLACE_BOAT, MOUNT_HORSE, PORTAL, COMMAND -> true;
      default -> false;
    };
  }
}
