/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin;

import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.plugin.Permissions;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.service.permission.PermissionDescription;
import org.spongepowered.api.service.permission.PermissionService;
import org.spongepowered.api.util.Tristate;
import org.spongepowered.plugin.PluginContainer;

/**
 * Describes Odyssey's permission nodes to the server's permission service — Sponge's counterpart to
 * Paper's {@code permissions:} block. Without a description a node has no default, so a permission
 * plugin would deny it to everyone; the player-facing nodes must default to allow.
 */
final class SpongePermissions {

  private SpongePermissions() {}

  /** Registers every node in {@link Permissions} with its documented default. */
  static void register(PluginContainer container) {
    PermissionService service = Sponge.server().serviceProvider().permissionService();
    describe(service, container, Permissions.NAVIGATE, "Use of /navigate", true);
    describe(service, container, Permissions.NAVIGATOR, "Use of custom navigators", true);
    describe(service, container, Permissions.LOCATION, "Use of personal locations", true);
    describe(service, container, Permissions.RELOAD, "Reload the Odyssey configuration", false);
    describe(service, container, Permissions.PORTALS, "Clear discovered portals", false);
    describe(
        service, container, Permissions.LOCATION_GLOBAL, "Manage server-wide locations", false);
  }

  private static void describe(
      PermissionService service,
      PluginContainer container,
      Permissions permission,
      String description,
      boolean allowByDefault) {
    service
        .newDescriptionBuilder(container)
        .id(permission.value())
        .description(Component.text(description))
        .defaultValue(allowByDefault ? Tristate.TRUE : Tristate.FALSE)
        .assign(
            allowByDefault ? PermissionDescription.ROLE_USER : PermissionDescription.ROLE_ADMIN,
            true)
        .register();
  }
}
