/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin.api;

import net.whimxiqal.odyssey.plugin.api.NavigatorFactory;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface PaperNavigatorFactory extends NavigatorFactory<Player, Location> {
}
