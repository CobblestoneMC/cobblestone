/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.integration.citizens;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.whimxiqal.odyssey.paper.plugin.api.Destination;
import net.whimxiqal.odyssey.paper.plugin.api.DestinationService;
import net.whimxiqal.odyssey.paper.plugin.api.DestinationTree;
import net.whimxiqal.odyssey.plugin.api.PlatformDestinationTree;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * Surfaces the server's Citizens NPCs as navigation targets: {@code citizens → npc → <name>-<id>},
 * one leaf per NPC. Names aren't unique in Citizens, so each key carries the NPC id to
 * disambiguate. Navigating is gated by Odyssey's {@code odyssey.navigate.citizens.npc.*} permission
 * (default-allow), and — because a server may have hundreds of NPCs — {@code /navigate}'s
 * tab-completion only offers matches once the player has narrowed the set down. Each NPC's location
 * is re-read (by id) when the destination is resolved, so it reflects where the NPC currently
 * stands.
 */
final class CitizensDestinationService implements DestinationService {

  static final String TREE_KEY = "citizens";
  static final String NPC_KEY = "npc";

  @Override
  public Map<String, Supplier<PlatformDestinationTree<World, Vector3i>>> provide(Player player) {
    // Strict: a server can hold hundreds of NPCs, and none of them should be promoted to the
    // root of /navigate — the player asks for "npc" first.
    DestinationTree npcNode = DestinationTree.builder().strict();
    boolean any = false;
    for (NPC npc : CitizensAPI.getNPCRegistry().sorted()) {
      int id = npc.getId();
      String label = label(npc);
      // Re-fetch by id at resolution time: the NPC may have moved (or been removed) since listing.
      npcNode.leaf(
          key(npc),
          () -> {
            NPC current = CitizensAPI.getNPCRegistry().getById(id);
            return Destination.at(current == null ? null : current.getStoredLocation(), label);
          });
      any = true;
    }
    return any
        ? Map.of(TREE_KEY, () -> DestinationTree.builder().subtree(NPC_KEY, npcNode).build())
        : Map.of();
  }

  /** The command token for an NPC: its slugged name plus id, unique across same-named NPCs. */
  private static String key(NPC npc) {
    return slug(stripColor(npc.getName())) + "-" + npc.getId();
  }

  /** The player-facing label: the NPC's (color-stripped) name and id. */
  private static String label(NPC npc) {
    String name = stripColor(npc.getName());
    return (name.isBlank() ? "NPC" : name) + " #" + npc.getId();
  }

  private static String slug(String name) {
    String slug =
        name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    return slug.isEmpty() ? "npc" : slug;
  }

  /** Strips legacy section-sign/ampersand color codes so names read cleanly in commands. */
  private static String stripColor(String name) {
    return name == null ? "" : name.replaceAll("[§&][0-9A-Fa-fK-Ok-orRxX]", "");
  }
}
