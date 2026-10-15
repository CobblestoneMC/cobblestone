/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.utility;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Stonebrick utility: the in-game side of the benchmark.
 *
 * <p>Development tooling, never shipped. It captures terrain into the benchmark format, which the
 * corpus provisioner drives over the console, and it is where a developer picks scenarios: marking
 * their two ends, and checking that the bench can make use of them. All of it under {@code
 * /stonebrick}, or {@code /sb}.
 *
 * <p>It is deliberately not called a Cobblestone plugin. Copying chunks into a benchmark format has
 * nothing to do with navigation, and an admin who finds this jar on a server should be able to tell
 * from its name that it is not part of the thing they installed.
 */
public final class StonebrickUtilityPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    UtilityState state = new UtilityState(getDataFolder().toPath().resolve("out"));
    ScenarioChecker checker = new ScenarioChecker(this, state);
    try {
      checker.installLoadouts();
    } catch (java.io.IOException e) {
      getLogger().warning("Could not write the loadouts for /stonebrick check: " + e.getMessage());
    }
    UtilityCommand command = new UtilityCommand(this, state, checker);
    getLifecycleManager()
        .registerEventHandler(
            LifecycleEvents.COMMANDS,
            event ->
                event
                    .registrar()
                    .register(
                        command.build(),
                        "Capture terrain and pick scenarios for the Stonebrick benchmark",
                        List.of("sb")));
    getLogger()
        .info(
            "Captures will be written to "
                + getDataFolder().toPath().resolve("out").resolve("captures"));
  }
}
