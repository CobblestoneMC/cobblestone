/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin.api;

import net.whimxiqal.odyssey.plugin.api.DestinationProvider;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

public interface PaperDestinationProvider extends DestinationProvider<World, Vector3i, Player> {
}
