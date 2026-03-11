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

package com.moneat.shared.services

import com.moneat.shared.models.Memberships
import com.moneat.shared.models.SidebarPreferenceEvents
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

object SidebarPreferenceService {
    // Configurable sidebar items (admin is NOT hideable per user requirement)
    private val CONFIGURABLE_ITEMS =
        setOf(
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
        return transaction {
            val currentItems =
                Memberships
                    .selectAll()
                    .where { Memberships.id eq membershipId }
                    .map { it[Memberships.sidebar_hidden_items] }
                    .firstOrNull() ?: emptyList()

            Memberships.update({ Memberships.id eq membershipId }) {
                it[sidebar_hidden_items] = normalized
            }

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

            normalized
        }
    }

    /**
     * Get sidebar preferences for a membership
     */
    fun getPreferences(membershipId: Int): List<String> =
        transaction {
            Memberships
                .selectAll()
                .where { Memberships.id eq membershipId }
                .map { it[Memberships.sidebar_hidden_items] }
                .firstOrNull() ?: emptyList()
        }
}
