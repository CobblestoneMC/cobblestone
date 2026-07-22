/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.api;

import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.minecraft.api.PlatformTransition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.joml.Vector3i;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface PaperTransitionProvider {

    CompletableFuture<List<? extends PlatformTransition<WorldRegion<World, Vector3i>, Location>>> compute(Player player);

    default void register(Plugin plugin) {
        Bukkit.getServicesManager().register(PaperTransitionProvider.class, this, plugin, ServicePriority.Normal);
    }

}
