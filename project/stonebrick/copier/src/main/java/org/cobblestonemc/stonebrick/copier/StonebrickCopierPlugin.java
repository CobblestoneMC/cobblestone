/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Stonebrick capture tool.
 *
 * <p>Development tooling, never shipped: it exists so that a benchmark corpus can be taken from a
 * real world and checked into version control. Its only job is {@code /copier}.
 *
 * <p>It is deliberately not called a Cobblestone plugin. Copying chunks into a benchmark format has
 * nothing to do with navigation, and an admin who finds this jar on a server should be able to tell
 * from its name that it is not part of the thing they installed.
 */
public final class StonebrickCopierPlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    CopierState state = new CopierState(getDataFolder().toPath().resolve("out"));
    CopierCommand command = new CopierCommand(this, state);
    getLifecycleManager()
        .registerEventHandler(
            LifecycleEvents.COMMANDS,
            event ->
                event
                    .registrar()
                    .register(
                        command.build(),
                        "Capture world data into the Stonebrick benchmark format",
                        List.of()));
    getLogger()
        .info(
            "Captures will be written to "
                + getDataFolder().toPath().resolve("out").resolve("captures"));
  }
}
