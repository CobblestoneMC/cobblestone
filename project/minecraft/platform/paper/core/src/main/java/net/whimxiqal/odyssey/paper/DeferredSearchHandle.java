/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.api.FailureReason;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.api.MinecraftWorld;

/**
 * A {@link SearchHandle} for a search that can't start until its transitions have been gathered
 * asynchronously (from the registered {@link net.whimxiqal.odyssey.minecraft.api.TransitionProvider}s).
 * It exposes a stable future/cancel immediately, wiring them to the real handle once it exists.
 */
final class DeferredSearchHandle
    implements SearchHandle<MinecraftStepType, MinecraftInstruction, MinecraftWorld> {

  private final CompletableFuture<NavigationResult<MinecraftStepType, MinecraftInstruction, MinecraftWorld>>
      future = new CompletableFuture<>();
  private volatile SearchHandle<MinecraftStepType, MinecraftInstruction, MinecraftWorld> inner;
  private volatile boolean cancelled;

  DeferredSearchHandle(
      CompletableFuture<SearchHandle<MinecraftStepType, MinecraftInstruction, MinecraftWorld>> handleFuture) {
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
          future.complete(result);
        }
      });
    });
  }

  @Override
  public CompletableFuture<NavigationResult<MinecraftStepType, MinecraftInstruction, MinecraftWorld>> future() {
    return future;
  }

  @Override
  public void cancel() {
    cancelled = true;
    SearchHandle<MinecraftStepType, MinecraftInstruction, MinecraftWorld> handle = inner;
    if (handle != null) {
      handle.cancel();
    }
  }
}
