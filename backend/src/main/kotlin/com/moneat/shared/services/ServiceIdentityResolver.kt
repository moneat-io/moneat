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

import com.moneat.shared.models.OtelServiceProjectMappings
import com.moneat.shared.models.Projects
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ServiceIdentityResolver {
    fun resolveServiceId(
        organizationId: Int,
        serviceName: String,
        serviceNamespace: String = ""
    ): Long? {
        val normalizedName = normalizeServiceName(serviceName) ?: return null
        val normalizedNamespace = serviceNamespace.trim()

        return transaction {
            findMappedServiceId(organizationId, normalizedName, normalizedNamespace)
                ?: findProjectServiceId(organizationId, normalizedName)
        }
    }

    fun serviceNameForProject(projectId: Long): String? =
        transaction {
            Projects
                .selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?.let(::projectServiceName)
        }

    fun serviceNamesForProjects(projectIds: List<Long>): Map<Long, String> {
        if (projectIds.isEmpty()) return emptyMap()
        return transaction {
            Projects
                .selectAll()
                .where { Projects.id inList projectIds }
                .associate { row -> row[Projects.id] to projectServiceName(row) }
        }
    }

    fun normalizeServiceName(serviceName: String?): String? =
        serviceName
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun findMappedServiceId(
        organizationId: Int,
        serviceName: String,
        serviceNamespace: String
    ): Long? =
        OtelServiceProjectMappings
            .selectAll()
            .where {
                (OtelServiceProjectMappings.organization_id eq organizationId) and
                    (OtelServiceProjectMappings.service_namespace eq serviceNamespace) and
                    (OtelServiceProjectMappings.service_name eq serviceName)
            }
            .firstOrNull()
            ?.get(OtelServiceProjectMappings.project_id)

    private fun findProjectServiceId(organizationId: Int, serviceName: String): Long? {
        val normalizedName = serviceName.lowercase()
        return Projects
            .selectAll()
            .where {
                (Projects.organization_id eq organizationId) and
                    (
                        (Projects.slug.lowerCase() eq normalizedName) or
                            (Projects.name.lowerCase() eq normalizedName)
                        )
            }
            .firstOrNull()
            ?.get(Projects.id)
    }

    private fun projectServiceName(row: ResultRow): String =
        row[Projects.slug]
            .trim()
            .ifBlank { row[Projects.name].trim() }
            .ifBlank { row[Projects.id].toString() }
}
