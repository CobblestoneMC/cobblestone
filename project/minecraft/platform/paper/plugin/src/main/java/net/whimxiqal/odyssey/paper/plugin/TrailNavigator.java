/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
  private static final float DUST_SIZE = 1.0f;

  private final Player player;
  private final int bufferCells;
  private final List<Particle.DustOptions> dusts;
  private final int density;
  private final int abandonDistance;
  private final Messages messages;
  private final Locale locale;

  private List<Step<Location, MinecraftStepPayload>> steps;
  private List<Vec3> points;
  private int foremost;
  private int lastPromptedIndex = -1;
  private boolean complete;

  TrailNavigator(
      Player player,
      Path<Step<Location, MinecraftStepPayload>> path,
      int bufferCells,
      List<Color> palette,
      int density,
      int abandonDistance,
      Messages messages) {
    this.player = player;
    this.bufferCells = bufferCells;
    List<Color> colors = palette.isEmpty() ? List.of(Color.AQUA) : palette;
    this.dusts = new ArrayList<>(colors.size());
    for (Color color : colors) {
      dusts.add(new Particle.DustOptions(color, DUST_SIZE));
    }
    this.density = Math.max(1, density);
    this.abandonDistance = abandonDistance;
    this.messages = messages;
    this.locale = player.locale();
    setPath(path);
  }

  private void setPath(Path<Step<Location, MinecraftStepPayload>> path) {
    this.steps = path.steps();
    this.points = new ArrayList<>(steps.size());
    for (Step<Location, MinecraftStepPayload> step : steps) {
      Location location = step.position();
      // Block centres, so projection and rendering share the same reference points.
      points.add(new Vec3(location.getBlockX() + 0.5, location.getBlockY() + 0.5, location.getBlockZ() + 0.5));
    }
    this.foremost = 0;
    this.lastPromptedIndex = -1;
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
    Location playerLocation = player.getLocation();
    World playerWorld = playerLocation.getWorld();
    Vec3 playerVec = new Vec3(playerLocation.getX(), playerLocation.getY(), playerLocation.getZ());
    boolean onTrailWorld = sameWorld(playerWorld, steps.get(foremost).position());

    // Advance only in the trail head's world; a cross-domain hop is handled by the action prompt.
    if (onTrailWorld) {
      foremost = TrailProgress.advance(points, foremost, playerVec);
    }

    if (reachedGoal(playerVec, playerWorld)) {
      complete = true;
      return;
    }

    if (onTrailWorld && abandonDistance > 0
        && playerVec.minus(projectedTarget(playerVec)).lengthSquared() > (double) abandonDistance * abandonDistance) {
      messages.send(player, locale, OdysseyMessages.NAV_TRAIL_ABANDONED);
      complete = true; // the trip manager untracks a completed trip
      return;
    }

    promptForActionIfNeeded();
    ThreadLocalRandom random = ThreadLocalRandom.current();
    renderTrail(playerVec, playerWorld, random);
    renderGuideLine(playerVec, playerWorld, random);
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
      if (!sameWorld(playerWorld, steps.get(i).position())) {
        continue;
      }
      Vec3 centre = points.get(i);
      if (playerVec.minus(centre).lengthSquared() < NEAR_BUFFER_SQUARED) {
        continue; // keep the player's immediate view clear
      }
      scatter(playerWorld, centre, random);
    }
  }

  private void renderGuideLine(Vec3 playerVec, World playerWorld, ThreadLocalRandom random) {
    if (!sameWorld(playerWorld, steps.get(foremost).position())) {
      return;
    }
    Vec3 target = projectedTarget(playerVec);
    Vec3 delta = target.minus(playerVec);
    double distance = Math.sqrt(delta.lengthSquared());
    if (distance <= NEAR_BUFFER) {
      return; // on or beside the path: no guide-line needed
    }
    int dots = (int) (distance / GUIDE_LINE_SPACING);
    for (int i = 1; i <= dots; i++) {
      double t = i / (double) dots;
      Vec3 point = new Vec3(
          playerVec.x() + delta.x() * t, playerVec.y() + delta.y() * t, playerVec.z() + delta.z() * t);
      if (playerVec.minus(point).lengthSquared() < NEAR_BUFFER_SQUARED) {
        continue;
      }
      scatter(playerWorld, point, random); // same scatter as the column, so it matches thickness
    }
  }

  /** Spawns {@code density} Gaussian-scattered particles of random palette colors around a centre. */
  private void scatter(World world, Vec3 centre, ThreadLocalRandom random) {
    for (int p = 0; p < density; p++) {
      Particle.DustOptions dust = dusts.get(random.nextInt(dusts.size()));
      Location dot = new Location(world,
          centre.x() + random.nextGaussian() * SPREAD_HORIZONTAL,
          centre.y() + random.nextGaussian() * SPREAD_VERTICAL,
          centre.z() + random.nextGaussian() * SPREAD_HORIZONTAL);
      player.spawnParticle(Particle.DUST, dot, 1, dust);
    }
  }

  /** The nearest point on the current segment ahead — so a slight deviation nudges back, not rewinds. */
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
