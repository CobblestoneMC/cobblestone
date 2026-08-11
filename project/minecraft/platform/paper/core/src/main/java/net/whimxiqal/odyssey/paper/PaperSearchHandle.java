/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import org.bukkit.Location;

/**
 * The Paper-facing {@link SearchHandle}. It does two jobs at once:
 *
 * <ul>
 *   <li><b>Deferred start</b> — the underlying core search can't begin until the player's
 *       transitions have been gathered asynchronously, so this exposes a stable future/cancel
 *       immediately and wires them to the real handle once it exists.
 *   <li><b>Position mapping</b> — the core produces steps located by {@link Position}; this maps
 *       each into a step located by a native Bukkit {@link Location} before handing it back.
 * </ul>
 */
final class PaperSearchHandle implements SearchHandle<Location, MinecraftStepPayload> {

  private final CompletableFuture<NavigationResult<Location, MinecraftStepPayload>> future =
      new CompletableFuture<>();
  private volatile SearchHandle<Position<MinecraftWorld>, MinecraftStepPayload> inner;
  private volatile boolean cancelled;

  PaperSearchHandle(
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
                      future.complete(result.map(PaperConversions::location));
                    }
                  });
        });
  }

  @Override
  public CompletableFuture<NavigationResult<Location, MinecraftStepPayload>> future() {
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
