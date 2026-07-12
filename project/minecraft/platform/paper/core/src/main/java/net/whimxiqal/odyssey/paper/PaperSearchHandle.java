/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.api.FailureReason;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.api.MinecraftWorld;
import org.bukkit.Location;

/**
 * The Paper-facing {@link SearchHandle}. It does two jobs at once:
 *
 * <ul>
 *   <li><b>Deferred start</b> — the underlying core search can't begin until the player's
 *       transitions have been gathered asynchronously, so this exposes a stable future/cancel
 *       immediately and wires them to the real handle once it exists.</li>
 *   <li><b>Position mapping</b> — the core produces steps located by {@link Position}; this maps
 *       each into a step located by a native Bukkit {@link Location} before handing it back.</li>
 * </ul>
 */
final class PaperSearchHandle
    implements SearchHandle<Step<Location, MinecraftStepType, MinecraftInstruction>> {

  private final CompletableFuture<NavigationResult<Step<Location, MinecraftStepType, MinecraftInstruction>>>
      future = new CompletableFuture<>();
  private volatile SearchHandle<Step<Position<MinecraftWorld>, MinecraftStepType, MinecraftInstruction>> inner;
  private volatile boolean cancelled;

  PaperSearchHandle(
      CompletableFuture<SearchHandle<Step<Position<MinecraftWorld>, MinecraftStepType, MinecraftInstruction>>>
          handleFuture) {
    handleFuture.whenComplete((handle, error) -> {
      if (error != null || handle == null) {
        future.complete(new NavigationResult.Failure<>(FailureReason.ERROR));
        return;
      }
      inner = handle;
      if (cancelled) {
        handle.cancel();
      }
      handle.future().whenComplete((result, err) -> {
        if (err != null || result == null) {
          future.complete(new NavigationResult.Failure<>(FailureReason.ERROR));
        } else {
          future.complete(map(result));
        }
      });
    });
  }

  private static NavigationResult<Step<Location, MinecraftStepType, MinecraftInstruction>> map(
      NavigationResult<Step<Position<MinecraftWorld>, MinecraftStepType, MinecraftInstruction>> result) {
    if (result instanceof NavigationResult.Success<Step<Position<MinecraftWorld>, MinecraftStepType,
            MinecraftInstruction>>(Path<Step<Position<MinecraftWorld>, MinecraftStepType, MinecraftInstruction>> path)) {
        List<Step<Location, MinecraftStepType, MinecraftInstruction>> steps = new ArrayList<>();
      for (Step<Position<MinecraftWorld>, MinecraftStepType, MinecraftInstruction> step : path.steps()) {
        steps.add(new Step<>(
            PaperConversions.location(step.position()),
            step.cumulativeCost(),
            step.stepType(),
            step.instruction()));
      }
      return new NavigationResult.Success<>(new PaperPath(steps, path.cost()));
    }
    NavigationResult.Failure<Step<Position<MinecraftWorld>, MinecraftStepType, MinecraftInstruction>> failure =
        (NavigationResult.Failure<Step<Position<MinecraftWorld>, MinecraftStepType, MinecraftInstruction>>) result;
    return new NavigationResult.Failure<>(failure.reason());
  }

  @Override
  public CompletableFuture<NavigationResult<Step<Location, MinecraftStepType, MinecraftInstruction>>> future() {
    return future;
  }

  @Override
  public void cancel() {
    cancelled = true;
    SearchHandle<Step<Position<MinecraftWorld>, MinecraftStepType, MinecraftInstruction>> handle = inner;
    if (handle != null) {
      handle.cancel();
    }
  }
}
