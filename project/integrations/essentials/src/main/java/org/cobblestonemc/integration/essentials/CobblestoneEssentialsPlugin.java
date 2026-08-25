/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.essentials;

import com.earth2me.essentials.spawn.IEssentialsSpawn;
import net.ess3.api.IEssentials;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.cobblestonemc.paper.api.CobblestoneCoreApi;
import org.cobblestonemc.paper.plugin.api.CobblestonePaperApi;

/**
 * Entry point for the EssentialsX integration. Resolves the Essentials API handles, then registers
 * — as ordinary Bukkit services — a destination provider (navigate to a home/spawn) and a search
 * modifier (route through the /home and /spawn teleports). Cobblestone discovers both
 * automatically.
 */
public final class CobblestoneEssentialsPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    Plugin essentialsPlugin = getServer().getPluginManager().getPlugin("Essentials");
    if (!(essentialsPlugin instanceof IEssentials essentialsApi)) {
      getLogger().severe("EssentialsX not found; disabling CobblestoneEssentials.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    // The spawn module is optional; without it we simply offer no /spawn target.
    Plugin spawnPlugin = getServer().getPluginManager().getPlugin("EssentialsSpawn");
    IEssentialsSpawn spawnApi = spawnPlugin instanceof IEssentialsSpawn spawn ? spawn : null;

    Essentials essentials = new Essentials(essentialsApi, spawnApi);

    CobblestonePaperApi.registrar()
        .registerDestinations(essentialsPlugin, new EssentialsDestinationService(essentials));
    CobblestoneCoreApi.registrar()
        .register(essentialsPlugin, new EssentialsSearchModificationService(essentials));

    getLogger()
        .info(
            "CobblestoneEssentials enabled"
                + (spawnApi == null ? " (EssentialsSpawn absent; /spawn not offered)." : "."));
  }
}
