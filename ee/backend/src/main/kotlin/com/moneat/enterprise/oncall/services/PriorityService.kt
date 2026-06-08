// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.alerts.models.AlertPriority as AlertPriorityValue
import com.moneat.enterprise.oncall.models.AlertPriorities
import com.moneat.enterprise.oncall.models.AlertPriority
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private data class DefaultPriority(
    val priority: String,
    val isPageable: Boolean,
    val label: String,
    val description: String,
)

private val DEFAULT_PRIORITIES = listOf(
    DefaultPriority("P0", true, "P0", "Immediate response required"),
    DefaultPriority("P1", true, "P1", "Urgent, respond within 15 minutes"),
    DefaultPriority("P2", true, "P2", "Respond within 1 hour"),
    DefaultPriority("P3", false, "P3", "Respond within 1 business day"),
    DefaultPriority("P4", false, "P4", "Lower urgency, no page by default"),
    DefaultPriority("P5", false, "P5", "Informational alert, no page by default"),
)

private fun normalizedPriority(value: String): String =
    AlertPriorityValue.wireValue(value) ?: value.trim().uppercase()

class PriorityService {
    /** Insert the four default priorities for an org if none exist yet. Must be called inside a transaction. */
    private fun seedDefaultsIfEmpty(organizationId: Int) {
        val existing = AlertPriorities.selectAll()
            .where { AlertPriorities.organizationId eq organizationId }
            .count()
        if (existing > 0L) return
        val now = Clock.System.now()
        DEFAULT_PRIORITIES.forEach { d ->
            AlertPriorities.insert {
                it[AlertPriorities.organizationId] = organizationId
                it[priority] = d.priority
                it[isPageable] = d.isPageable
                it[label] = d.label
                it[description] = d.description
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    fun resolvePriority(
        organizationId: Int,
        priority: String,
    ): AlertPriority? {
        val normalized = normalizedPriority(priority)
        return transaction {
            seedDefaultsIfEmpty(organizationId)
            AlertPriorities
                .selectAll()
                .where {
                    (AlertPriorities.organizationId eq organizationId) and (AlertPriorities.priority eq normalized)
                }.firstOrNull()
                ?.let { row ->
                    AlertPriority(
                        id = row[AlertPriorities.id].value,
                        organizationId = row[AlertPriorities.organizationId],
                        priority = normalizedPriority(row[AlertPriorities.priority]),
                        isPageable = row[AlertPriorities.isPageable],
                        label = row[AlertPriorities.label],
                        description = row[AlertPriorities.description],
                        createdAt = row[AlertPriorities.createdAt].toString(),
                        updatedAt = row[AlertPriorities.updatedAt].toString(),
                    )
                }
        }
    }

    fun isPageable(
        organizationId: Int,
        priority: String,
    ): Boolean {
        val normalized = normalizedPriority(priority)
        return transaction {
            AlertPriorities
                .selectAll()
                .where {
                    (AlertPriorities.organizationId eq organizationId) and
                        (AlertPriorities.priority eq normalized)
                }.singleOrNull()
                ?.get(AlertPriorities.isPageable) ?: false
        }
    }

    fun getAllPriorities(organizationId: Int): List<AlertPriority> =
        transaction {
            seedDefaultsIfEmpty(organizationId)
            AlertPriorities
                .selectAll()
                .where { AlertPriorities.organizationId eq organizationId }
                .orderBy(AlertPriorities.priority to SortOrder.ASC)
                .map { row ->
                    AlertPriority(
                        id = row[AlertPriorities.id].value,
                        organizationId = row[AlertPriorities.organizationId],
                        priority = normalizedPriority(row[AlertPriorities.priority]),
                        isPageable = row[AlertPriorities.isPageable],
                        label = row[AlertPriorities.label],
                        description = row[AlertPriorities.description],
                        createdAt = row[AlertPriorities.createdAt].toString(),
                        updatedAt = row[AlertPriorities.updatedAt].toString(),
                    )
                }
        }

    fun updatePriority(
        organizationId: Int,
        priority: String,
        isPageable: Boolean,
        label: String,
        description: String?,
    ): AlertPriority? {
        val normalizedPriority = normalizedPriority(priority)
        return transaction {
            val now = Clock.System.now()

            val updated = AlertPriorities.update({
                (AlertPriorities.organizationId eq organizationId) and
                    (AlertPriorities.priority eq normalizedPriority)
            }) {
                it[AlertPriorities.isPageable] = isPageable
                it[AlertPriorities.label] = label
                it[AlertPriorities.description] = description
                it[AlertPriorities.updatedAt] = now
            }

            if (updated == 0) {
                AlertPriorities.insert {
                    it[AlertPriorities.organizationId] = organizationId
                    it[AlertPriorities.priority] = normalizedPriority
                    it[AlertPriorities.isPageable] = isPageable
                    it[AlertPriorities.label] = label
                    it[AlertPriorities.description] = description
                    it[AlertPriorities.createdAt] = now
                    it[AlertPriorities.updatedAt] = now
                }
            }

            resolvePriority(organizationId, normalizedPriority)
        }
    }
}
