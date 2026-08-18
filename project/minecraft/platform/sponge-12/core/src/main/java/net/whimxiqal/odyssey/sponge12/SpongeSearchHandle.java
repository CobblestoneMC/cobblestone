/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12;

import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import org.spongepowered.api.world.server.ServerLocation;

/**
 * The Sponge-facing {@link SearchHandle}. It exposes a stable future/cancel immediately (the core
 * search can't begin until the player's transitions are gathered asynchronously) and maps each core
 * step located by {@link Position} into one located by a native {@link ServerLocation}.
 */
final class SpongeSearchHandle implements SearchHandle<ServerLocation, MinecraftStepPayload> {

  private final CompletableFuture<NavigationResult<ServerLocation, MinecraftStepPayload>> future =
      new CompletableFuture<>();
  private volatile SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload> inner;
  private volatile boolean cancelled;

  SpongeSearchHandle(
      CompletableFuture<SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload>>
          handleFuture) {
    handleFuture.whenComplete(
        (handle, error) -> {
          if (error != null || handle == null) {
            future.complete(new NavigationResult.Error<>(error));
            return;
          }
          inner = handle;
          if (cancelled) {
            handle.cancel();
          }
          handle
              .future()
              .whenComplete(
                  (result, err) -> {
                    if (err != null || result == null) {
                      future.complete(new NavigationResult.Error<>(err));
                    } else {
                      future.complete(result.map(SpongeConversions::location));
                    }
                  });
        });
  }

  @Override
  public CompletableFuture<NavigationResult<ServerLocation, MinecraftStepPayload>> future() {
    return future;
  }

  @Override
  public void cancel() {
    cancelled = true;
    SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload> handle = inner;
    if (handle != null) {
      handle.cancel();
    }
  }
}
