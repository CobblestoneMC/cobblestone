/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.whimxiqal.odyssey.minecraft.ChunkProvider;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Caches {@link PaperWorld} wrappers by world key and wraps Bukkit players. */
final class PaperWorlds {

  private final ChunkProvider provider;
  private final Map<String, PaperWorld> byKey = new ConcurrentHashMap<>();

  PaperWorlds(ChunkProvider provider) {
    this.provider = provider;
  }

  PaperWorld wrap(World world) {
    return byKey.computeIfAbsent(world.getKey().asString(), key -> new PaperWorld(world, provider));
  }

  PaperPlayer wrap(Player player) {
    return new PaperPlayer(player);
  }
}
