// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.services

import com.moneat.models.Memberships
import com.moneat.models.SidebarPreferenceEvents
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Clock

object SidebarPreferenceService {
    // Configurable sidebar items (admin is NOT hideable per user requirement)
    private val CONFIGURABLE_ITEMS = setOf(
        "dashboard",
        "performance",
        "issues",
        "logs",
        "replays",
        "feedback",
        "releases",
        "ai",
        "uptime",
        "status-pages",
        "monitoring",
        "on-call"
    )

    /**
     * Normalize and validate sidebar hidden items list:
     * - Drop unknown keys
     * - Dedupe
     * - Stable alphabetical order
     */
    fun normalizeHiddenItems(items: List<String>): List<String> {
        return items
            .filter { it in CONFIGURABLE_ITEMS }
            .distinct()
            .sorted()
    }

    /**
     * Update sidebar preferences for a membership and log the event if changed
     */
    fun updatePreferences(
        membershipId: Int,
        userId: Int,
        organizationId: Int,
        hiddenItems: List<String>,
        source: String
    ): List<String> {
        val normalized = normalizeHiddenItems(hiddenItems)

        // Get current preferences
        val currentItems = Memberships.selectAll()
            .where { Memberships.id eq membershipId }
            .map { it[Memberships.sidebar_hidden_items] }
            .firstOrNull() ?: emptyList()

        // Update membership
        Memberships.update({ Memberships.id eq membershipId }) {
            it[sidebar_hidden_items] = normalized
        }

        // Log event only if preferences actually changed
        if (normalized.sorted() != currentItems.sorted()) {
            SidebarPreferenceEvents.insert {
                it[SidebarPreferenceEvents.membership_id] = membershipId
                it[SidebarPreferenceEvents.user_id] = userId
                it[SidebarPreferenceEvents.organization_id] = organizationId
                it[SidebarPreferenceEvents.hidden_items] = normalized
                it[SidebarPreferenceEvents.event_source] = source
                it[created_at] = Clock.System.now()
            }
        }

        return normalized
    }

    /**
     * Get sidebar preferences for a membership
     */
    fun getPreferences(membershipId: Int): List<String> {
        return Memberships.selectAll()
            .where { Memberships.id eq membershipId }
            .map { it[Memberships.sidebar_hidden_items] }
            .firstOrNull() ?: emptyList()
    }
}
