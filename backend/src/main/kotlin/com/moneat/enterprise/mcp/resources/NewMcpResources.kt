// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.mcp.resources

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.McpResource
import com.moneat.enterprise.mcp.protocol.ResourceContent
import com.moneat.monitor.services.MonitorService
import com.moneat.statuspage.services.StatusPageService
import com.moneat.uptime.services.UptimeService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val jsonFmt = Json { prettyPrint = true }

class ActiveIncidentsResource : McpResource {
    override val uri = "moneat://incidents/active"
    override val name = "Active Incidents"
    override val description =
        "Currently active on-call incidents"

    override suspend fun read(
        context: McpContext
    ): ResourceContent {
        // Incidents are managed by on-call module;
        // return placeholder indicating use of list_incidents tool
        val result = buildJsonObject {
            put("hint", "Use list_incidents tool with status=triggered")
        }
        return ResourceContent(
            uri = uri,
            text = jsonFmt.encodeToString(result)
        )
    }
}

class UptimeSummaryResource : McpResource {
    private val uptimeService = UptimeService()

    override val uri = "moneat://uptime/summary"
    override val name = "Uptime Summary"
    override val description =
        "All uptime monitors with 24h/7d/30d percentages"

    override suspend fun read(
        context: McpContext
    ): ResourceContent {
        val monitors = uptimeService.listMonitors(
            context.organizationId
        )
        val result = buildJsonArray {
            monitors.forEach { m ->
                add(
                    buildJsonObject {
                        put("id", m.id)
                        put("name", m.name)
                        put("status", m.status)
                        put("active", m.active)
                        m.uptime24h?.let { put("uptime24h", it) }
                        m.uptime7d?.let { put("uptime7d", it) }
                        m.uptime30d?.let { put("uptime30d", it) }
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

class StatusPagesResource : McpResource {
    private val statusPageService = StatusPageService()

    override val uri = "moneat://status-pages"
    override val name = "Status Pages"
    override val description =
        "Status pages and current status"

    override suspend fun read(
        context: McpContext
    ): ResourceContent {
        val pages = statusPageService.listStatusPages(
            context.organizationId
        )
        return ResourceContent(
            uri = uri,
            text = jsonFmt.encodeToString(pages)
        )
    }
}

class InfrastructureHealthResource : McpResource {
    private val monitorService = MonitorService()
    private val uptimeService = UptimeService()

    override val uri = "moneat://infrastructure/health"
    override val name = "Infrastructure Health"
    override val description =
        "Quick health: host statuses, alert counts, uptime"

    override suspend fun read(
        context: McpContext
    ): ResourceContent {
        val systems = monitorService.listHosts(
            context.organizationId
        )
        val monitors = uptimeService.listMonitors(
            context.organizationId
        )

        val online = systems.count { it.status == "online" }
        val offline = systems.count { it.status == "offline" }
        val alertCount = systems.sumOf { sys ->
            monitorService.listAlerts(sys.id, context.organizationId).count {
                it.lastTriggeredAt != null
            }
        }
        val monitorsDown = monitors.count {
            it.status == "down"
        }

        val result = buildJsonObject {
            put("hostsOnline", online)
            put("hostsOffline", offline)
            put("totalHosts", systems.size)
            put("activeAlerts", alertCount)
            put("uptimeMonitors", monitors.size)
            put("monitorsDown", monitorsDown)
        }
        return ResourceContent(
            uri = uri,
            text = jsonFmt.encodeToString(result)
        )
    }
}
