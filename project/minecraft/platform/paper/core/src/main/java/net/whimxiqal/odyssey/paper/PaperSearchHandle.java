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
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
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
    implements SearchHandle<Step<Location, MinecraftStepPayload>> {

  private final CompletableFuture<NavigationResult<Step<Location, MinecraftStepPayload>>>
      future = new CompletableFuture<>();
  private volatile SearchHandle<Step<Position<MinecraftWorld>, MinecraftStepPayload>> inner;
  private volatile boolean cancelled;

  PaperSearchHandle(
      CompletableFuture<SearchHandle<Step<Position<MinecraftWorld>, MinecraftStepPayload>>>
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

  private static NavigationResult<Step<Location, MinecraftStepPayload>> map(
      NavigationResult<Step<Position<MinecraftWorld>, MinecraftStepPayload>> result) {
    if (result instanceof NavigationResult.Success<Step<Position<MinecraftWorld>, MinecraftStepPayload>>(Path<Step<Position<MinecraftWorld>, MinecraftStepPayload>> path)) {
        List<Step<Location, MinecraftStepPayload>> steps = new ArrayList<>();
      for (Step<Position<MinecraftWorld>, MinecraftStepPayload> step : path.steps()) {
        steps.add(new Step<>(
            PaperConversions.location(step.position()),
            step.cost(),
            step.time(),
            step.payload()));
      }
      return new NavigationResult.Success<>(new PaperPath(steps));
    }
    NavigationResult.Failure<Step<Position<MinecraftWorld>, MinecraftStepPayload>> failure =
        (NavigationResult.Failure<Step<Position<MinecraftWorld>, MinecraftStepPayload>>) result;
    return new NavigationResult.Failure<>(failure.reason());
  }

  @Override
  public CompletableFuture<NavigationResult<Step<Location, MinecraftStepPayload>>> future() {
    return future;
  }

  @Override
  public void cancel() {
    cancelled = true;
    SearchHandle<Step<Position<MinecraftWorld>, MinecraftStepPayload>> handle = inner;
    if (handle != null) {
      handle.cancel();
    }
  }
}
