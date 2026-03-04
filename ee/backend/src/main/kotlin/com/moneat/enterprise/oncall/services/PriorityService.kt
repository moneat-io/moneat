// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

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
    val severity: String,
    val priorityLevel: String,
    val isPageable: Boolean,
    val label: String,
    val description: String,
)

private val DEFAULT_PRIORITIES = listOf(
    DefaultPriority("CRITICAL", "P0", true, "P0 - Critical", "Immediate response required"),
    DefaultPriority("HIGH", "P1", true, "P1 - High", "Urgent, respond within 15 minutes"),
    DefaultPriority("MEDIUM", "P2", false, "P2 - Medium", "Respond within 1 hour"),
    DefaultPriority("LOW", "P3", false, "P3 - Low", "Respond within 1 business day"),
)

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
                it[severity] = d.severity
                it[priorityLevel] = d.priorityLevel
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
        severity: String,
    ): AlertPriority? =
        transaction {
            seedDefaultsIfEmpty(organizationId)
            AlertPriorities
                .selectAll()
                .where {
                    (AlertPriorities.organizationId eq organizationId) and
                        (AlertPriorities.severity eq severity.uppercase())
                }.singleOrNull()
                ?.let { row ->
                    AlertPriority(
                        id = row[AlertPriorities.id].value,
                        organizationId = row[AlertPriorities.organizationId],
                        severity = row[AlertPriorities.severity],
                        priorityLevel = row[AlertPriorities.priorityLevel],
                        isPageable = row[AlertPriorities.isPageable],
                        label = row[AlertPriorities.label],
                        description = row[AlertPriorities.description],
                        createdAt = row[AlertPriorities.createdAt].toString(),
                        updatedAt = row[AlertPriorities.updatedAt].toString(),
                    )
                }
        }

    fun isPageable(
        organizationId: Int,
        priorityLevel: String,
    ): Boolean =
        transaction {
            AlertPriorities
                .selectAll()
                .where {
                    (AlertPriorities.organizationId eq organizationId) and
                        (AlertPriorities.priorityLevel eq priorityLevel)
                }.singleOrNull()
                ?.get(AlertPriorities.isPageable) ?: false
        }

    fun getAllPriorities(organizationId: Int): List<AlertPriority> =
        transaction {
            seedDefaultsIfEmpty(organizationId)
            AlertPriorities
                .selectAll()
                .where { AlertPriorities.organizationId eq organizationId }
                .orderBy(AlertPriorities.priorityLevel to SortOrder.ASC)
                .map { row ->
                    AlertPriority(
                        id = row[AlertPriorities.id].value,
                        organizationId = row[AlertPriorities.organizationId],
                        severity = row[AlertPriorities.severity],
                        priorityLevel = row[AlertPriorities.priorityLevel],
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
        severity: String,
        priorityLevel: String,
        isPageable: Boolean,
        label: String,
        description: String?,
    ): AlertPriority? =
        transaction {
            val now = Clock.System.now()

            val updated = AlertPriorities.update({
                (AlertPriorities.organizationId eq organizationId) and
                    (AlertPriorities.severity eq severity.uppercase())
            }) {
                it[AlertPriorities.priorityLevel] = priorityLevel
                it[AlertPriorities.isPageable] = isPageable
                it[AlertPriorities.label] = label
                it[AlertPriorities.description] = description
                it[AlertPriorities.updatedAt] = now
            }

            if (updated == 0) {
                AlertPriorities.insert {
                    it[AlertPriorities.organizationId] = organizationId
                    it[AlertPriorities.severity] = severity.uppercase()
                    it[AlertPriorities.priorityLevel] = priorityLevel
                    it[AlertPriorities.isPageable] = isPageable
                    it[AlertPriorities.label] = label
                    it[AlertPriorities.description] = description
                    it[AlertPriorities.createdAt] = now
                    it[AlertPriorities.updatedAt] = now
                }
            }

            resolvePriority(organizationId, severity)
        }
}
