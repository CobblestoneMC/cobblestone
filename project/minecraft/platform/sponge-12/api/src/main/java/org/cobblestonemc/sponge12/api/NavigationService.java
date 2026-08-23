/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.api;

import org.cobblestonemc.minecraft.api.PlatformNavigationService;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;

/**
 * The Sponge-flavored developer entry point, obtained from {@link
 * CobblestoneCoreApi#navigationService()}. It lets other Sponge plugins request navigation in
 * native terms ({@link ServerPlayer}, {@link ServerLocation}) without touching Cobblestone's
 * generic core types.
 */
public interface NavigationService
    extends PlatformNavigationService<ServerPlayer, ServerLocation> {}
