/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.api;

import net.whimxiqal.odyssey.minecraft.api.PlatformNavigationService;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;

/**
 * The Sponge-flavored developer entry point, obtained from {@link
 * OdysseyCoreAPI#navigationService()}. It lets other Sponge plugins request navigation in native
 * terms ({@link ServerPlayer}, {@link ServerLocation}) without touching Odyssey's generic core
 * types.
 */
public interface NavigationService
    extends PlatformNavigationService<ServerPlayer, ServerLocation> {}
