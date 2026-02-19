// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.services.oncall

import com.moneat.enterprise.models.AlertPriorities
import com.moneat.enterprise.models.AlertPriority
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

class PriorityService {
    fun resolvePriority(
        organizationId: Int,
        severity: String,
    ): AlertPriority? =
        transaction {
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

            AlertPriorities.update({
                (AlertPriorities.organizationId eq organizationId) and
                    (AlertPriorities.severity eq severity.uppercase())
            }) {
                it[AlertPriorities.priorityLevel] = priorityLevel
                it[AlertPriorities.isPageable] = isPageable
                it[AlertPriorities.label] = label
                it[AlertPriorities.description] = description
                it[AlertPriorities.updatedAt] = now
            }

            resolvePriority(organizationId, severity)
        }
}
