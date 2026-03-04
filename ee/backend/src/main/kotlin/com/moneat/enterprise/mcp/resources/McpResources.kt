// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.resources

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.McpResource
import com.moneat.enterprise.mcp.protocol.ResourceContent
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val jsonFmt = Json { prettyPrint = true }
private val monitorService = MonitorService()
private val alertService = MonitorAlertService()

class OrgOverviewResource : McpResource {
    override val uri = "moneat://org/overview"
    override val name = "Organization Overview"
    override val description =
        "Summary of the organization: project count, host count, alerts"

    override suspend fun read(context: McpContext): ResourceContent {
        val (orgName, projectCount) = transaction {
            val name = Organizations
                .selectAll()
                .where { Organizations.id eq context.organizationId }
                .firstOrNull()
                ?.get(Organizations.name) ?: "Unknown"
            val count = Projects
                .selectAll()
                .where { Projects.organization_id eq context.organizationId }
                .count()
            name to count
        }

        val systems = monitorService.listHosts(context.organizationId)
        val silences = alertService.listSilencePeriods(
            context.organizationId
        )

        val overview = buildJsonObject {
            put("organization", orgName)
            put("organizationId", context.organizationId)
            put("projectCount", projectCount)
            put("hostCount", systems.size)
            put("activeSilencePeriods", silences.size)
        }

        return ResourceContent(
            uri = uri,
            text = jsonFmt.encodeToString(overview)
        )
    }
}

class ProjectsListResource : McpResource {
    override val uri = "moneat://projects"
    override val name = "Projects List"
    override val description = "All projects in the organization"

    override suspend fun read(context: McpContext): ResourceContent {
        val projects = transaction {
            Projects
                .selectAll()
                .where {
                    Projects.organization_id eq context.organizationId
                }
                .map { row ->
                    buildJsonObject {
                        put("id", row[Projects.id])
                        put("name", row[Projects.name])
                        put("slug", row[Projects.slug])
                        put(
                            "framework",
                            row[Projects.framework] ?: "unknown"
                        )
                    }
                }
        }

        val arr = buildJsonArray { projects.forEach { add(it) } }
        return ResourceContent(
            uri = uri,
            text = jsonFmt.encodeToString(arr)
        )
    }
}

class HostsStatusResource : McpResource {
    override val uri = "moneat://hosts/status"
    override val name = "Hosts Status"
    override val description = "All hosts: id and name (status not included)"

    override suspend fun read(context: McpContext): ResourceContent {
        val systems = monitorService.listHosts(context.organizationId)
        val result = buildJsonArray {
            systems.forEach { sys ->
                add(
                    buildJsonObject {
                        put("id", sys.id.toString())
                        put("name", sys.displayName ?: sys.hostname)
                    }
                )
            }
        }
        return ResourceContent(
            uri = uri,
            text = jsonFmt.encodeToString(result)
        )
    }
}

class AlertSilencesResource : McpResource {
    override val uri = "moneat://alerts/silences"
    override val name = "Alert Silences"
    override val description = "Currently active alert silence periods"

    override suspend fun read(context: McpContext): ResourceContent {
        val silences = alertService.listSilencePeriods(
            context.organizationId
        )
        return ResourceContent(
            uri = uri,
            text = jsonFmt.encodeToString(silences)
        )
    }
}
