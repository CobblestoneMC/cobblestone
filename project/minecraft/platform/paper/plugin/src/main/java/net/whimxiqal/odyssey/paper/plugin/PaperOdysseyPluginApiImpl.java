/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import net.whimxiqal.odyssey.paper.api.PaperOdysseyApi;
import net.whimxiqal.odyssey.paper.plugin.api.PaperOdysseyPluginApi;
import net.whimxiqal.odyssey.plugin.OdysseyPluginApiImpl;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The Paper binding of the plugin-extension API. It reuses the shared delegation/registration logic
 * in {@link OdysseyPluginApiImpl}, forwarding navigation to the native {@link PaperOdysseyApi} it is
 * constructed with, so downstream Paper plugins navigate and register purely in
 * {@link Player}/{@link Location} terms.
 */
public final class PaperOdysseyPluginApiImpl extends OdysseyPluginApiImpl<Player, Location>
    implements PaperOdysseyPluginApi {

  /**
   * Creates the Paper plugin API over the native platform API.
   *
   * @param platform the Paper navigation library
   */
  public PaperOdysseyPluginApiImpl(PaperOdysseyApi platform) {
    super(platform);
  }
}
