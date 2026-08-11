/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.essentials;

import com.earth2me.essentials.spawn.IEssentialsSpawn;
import net.ess3.api.IEssentials;
import net.whimxiqal.odyssey.paper.plugin.api.Odyssey;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for the EssentialsX integration. Resolves the Essentials API handles, then registers
 * — as ordinary Bukkit services — a destination provider (navigate to a home/spawn) and a search
 * modifier (route through the /home and /spawn teleports). Odyssey discovers both automatically.
 */
public final class OdysseyEssentialsPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    Plugin essentialsPlugin = getServer().getPluginManager().getPlugin("Essentials");
    if (!(essentialsPlugin instanceof IEssentials essentialsApi)) {
      getLogger().severe("EssentialsX not found; disabling OdysseyEssentials.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    // The spawn module is optional; without it we simply offer no /spawn target.
    Plugin spawnPlugin = getServer().getPluginManager().getPlugin("EssentialsSpawn");
    IEssentialsSpawn spawnApi = spawnPlugin instanceof IEssentialsSpawn spawn ? spawn : null;

    Essentials essentials = new Essentials(essentialsApi, spawnApi);

    Odyssey.register(this, new EssentialsDestinationProvider(essentials));
    Odyssey.register(this, new EssentialsTransitionProvider(essentials));

    getLogger()
        .info(
            "OdysseyEssentials enabled"
                + (spawnApi == null ? " (EssentialsSpawn absent; /spawn not offered)." : "."));
  }
}
