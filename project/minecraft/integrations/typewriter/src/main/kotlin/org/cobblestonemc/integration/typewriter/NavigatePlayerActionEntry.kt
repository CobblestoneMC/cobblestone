/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.integration.typewriter

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.utils.toBukkitLocation
import org.cobblestonemc.paper.plugin.api.CobblestonePaperApi
import org.cobblestonemc.plugin.api.NavigatorSettings

@Entry(
    "cobblestone_navigate_player",
    "Guide the player to a location with Cobblestone",
    Colors.GREEN,
    "mdi:map-marker-path",
)
/**
 * The `Navigate Player` action starts an Cobblestone trip guiding the triggering player to a target
 * location, drawing Cobblestone's live trail from the player to that point.
 *
 * ## How could this be used?
 * Point a player at their next quest objective, an NPC to talk to, or any place they need to reach —
 * for example, right after they accept a quest, trigger this action toward the objective.
 */
class NavigatePlayerActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    val target: Var<Position> = ConstVar(Position.ORIGIN),
) : ActionEntry {

    override fun ActionTrigger.execute() {
        val position = target.get(player, context)
        val location = runCatching { position.toBukkitLocation() }.getOrNull() ?: return
        // The entry name is the trip's stable label, so re-triggering this entry (a moving objective)
        // replaces the player's previous trip from it rather than stacking a new one.
        val label = name.ifBlank { "typewriter" }
        CobblestonePaperApi.tripService()
            .navigate(player, location, NavigatorSettings.defaults(), label) { _ ->
                // No route (or the search failed): nothing to do; the flow can retry.
            }
    }
}
