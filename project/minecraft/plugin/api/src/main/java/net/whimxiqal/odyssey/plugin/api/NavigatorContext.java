/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.api;

import net.kyori.adventure.audience.Audience;

/**
 * The services a {@link Navigator} needs while ticking: who it is guiding (in native terms) and how
 * to talk to them.
 *
 * <p>This is intentionally minimal for now. As the Trip subsystem lands (Phase 6c), it gains
 * accessors for particle/output helpers, config, and i18n so navigator authors can render and
 * prompt without reaching back into a specific platform.
 *
 * @param <P> the native player type (e.g. {@code org.bukkit.entity.Player})
 */
public interface NavigatorContext<P> {

  /**
   * Returns the player being guided, in native terms.
   *
   * @return the player
   */
  P player();

  /**
   * Returns the player as an Adventure {@link Audience}, for sending messages and prompts.
   *
   * @return the audience
   */
  Audience audience();
}
