// Moneat - Mobile-First Error Monitoring Platform
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

package com.moneat.services.oncall

import com.moneat.models.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class PriorityService {
    
    fun resolvePriority(organizationId: Int, severity: String): AlertPriority? = transaction {
        AlertPriorities
            .selectAll()
            .where { 
                (AlertPriorities.organizationId eq organizationId) and 
                (AlertPriorities.severity eq severity.uppercase())
            }
            .singleOrNull()
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
                    updatedAt = row[AlertPriorities.updatedAt].toString()
                )
            }
    }
    
    fun isPageable(organizationId: Int, priorityLevel: String): Boolean = transaction {
        AlertPriorities
            .selectAll()
            .where { 
                (AlertPriorities.organizationId eq organizationId) and 
                (AlertPriorities.priorityLevel eq priorityLevel)
            }
            .singleOrNull()
            ?.get(AlertPriorities.isPageable) ?: false
    }
    
    fun getAllPriorities(organizationId: Int): List<AlertPriority> = transaction {
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
                    updatedAt = row[AlertPriorities.updatedAt].toString()
                )
            }
    }
    
    fun updatePriority(
        organizationId: Int,
        severity: String,
        priorityLevel: String,
        isPageable: Boolean,
        label: String,
        description: String?
    ): AlertPriority? = transaction {
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
