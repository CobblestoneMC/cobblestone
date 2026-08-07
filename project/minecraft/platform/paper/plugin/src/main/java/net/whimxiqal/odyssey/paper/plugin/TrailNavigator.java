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
 * The default {@code trail} navigator: a line of redstone-dust particles along the path ahead of the
 * player, shown only to that player. It advances via {@link TrailProgress} (projecting the player
 * onto the foremost segment), renders up to {@code bufferCells} steps ahead plus a "return to trail"
 * line back to the trail head, and — when the next step is a discrete action (a portal or a command
 * transition) — prompts the player instead of silently expecting them to walk it.
 *
 * <p>Rendering is Paper-specific and verified on a live server (per the implementation plan); the
 * follow geometry it relies on is unit-tested in plugin-core.
 */
final class TrailNavigator implements Navigator<Location> {

  private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.RED, 1.0f);
  private static final double COMPLETION_RADIUS_SQUARED = 4.0; // within 2 blocks of the goal
  private static final double RETURN_LINE_SPACING = 1.0;

  private final Player player;
  private final int bufferCells;
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
      Messages messages) {
    this.player = player;
    this.bufferCells = bufferCells;
    this.messages = messages;
    this.locale = player.locale();
    setPath(path);
  }

  private void setPath(Path<Step<Location, MinecraftStepPayload>> path) {
    this.steps = path.steps();
    this.points = new ArrayList<>(steps.size());
    for (Step<Location, MinecraftStepPayload> step : steps) {
      Location location = step.position();
      points.add(new Vec3(location.getX(), location.getY(), location.getZ()));
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

    // Advance only while the player is in the same world as the trail head; a cross-domain hop
    // (portal/command) is handled by the action prompt below, not by geometry.
    if (sameWorld(playerWorld, steps.get(foremost).position())) {
      foremost = TrailProgress.advance(points, foremost, playerVec);
    }

    if (reachedGoal(playerVec, playerWorld)) {
      complete = true;
      return;
    }

    promptForActionIfNeeded();
    renderTrail(playerWorld);
    renderReturnLine(playerLocation, playerWorld);
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

  private void renderTrail(World playerWorld) {
    int end = Math.min(steps.size(), foremost + bufferCells);
    for (int i = foremost; i < end; i++) {
      Location location = steps.get(i).position();
      if (sameWorld(playerWorld, location)) {
        player.spawnParticle(Particle.DUST, center(location), 1, DUST);
      }
    }
  }

  private void renderReturnLine(Location playerLocation, World playerWorld) {
    Location head = steps.get(foremost).position();
    if (!sameWorld(playerWorld, head)) {
      return;
    }
    Vec3 from = new Vec3(playerLocation.getX(), playerLocation.getY(), playerLocation.getZ());
    Vec3 to = new Vec3(head.getX() + 0.5, head.getY() + 0.5, head.getZ() + 0.5);
    Vec3 delta = to.minus(from);
    double distance = Math.sqrt(delta.lengthSquared());
    int dots = (int) (distance / RETURN_LINE_SPACING);
    for (int i = 1; i < dots; i++) {
      double t = i / (double) dots;
      Location dot = new Location(playerWorld,
          from.x() + delta.x() * t, from.y() + delta.y() * t, from.z() + delta.z() * t);
      player.spawnParticle(Particle.DUST, dot, 1, DUST);
    }
  }

  private static Location center(Location blockLocation) {
    return blockLocation.clone().add(0.5, 0.5, 0.5);
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
