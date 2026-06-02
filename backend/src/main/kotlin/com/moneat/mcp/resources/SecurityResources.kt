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

package com.moneat.mcp.resources

import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.McpResource
import com.moneat.mcp.protocol.ResourceContent
import com.moneat.mcp.tools.DefaultSecurityMcpGateway
import com.moneat.mcp.tools.SecurityMcpGateway
import com.moneat.mcp.tools.toolJson
import com.moneat.security.signals.SignalFilters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private val defaultSecurityResourceGateway = DefaultSecurityMcpGateway()

class SecuritySummaryResource(
    private val gateway: SecurityMcpGateway = defaultSecurityResourceGateway,
) : McpResource {
    override val uri = "moneat://security/summary"
    override val name = "Security Summary"
    override val description = "Security signals, detection coverage, vulnerabilities, and compliance summary"
    override val requiredScopes = setOf(McpScopes.SECURITY_READ)

    override suspend fun read(context: McpContext): ResourceContent {
        val openSignals = gateway.listSignals(
            context.organizationId,
            SignalFilters(status = "open"),
            SECURITY_RESOURCE_LIMIT,
            0,
        )
        val coverage = gateway.detectionCoverage(context.organizationId)
        val vulnerabilities = gateway.vulnerabilitySummary(context.organizationId)
        val compliance = gateway.complianceSummary(context.organizationId)
        val result = buildJsonObject {
            put("openSignalCount", openSignals.totalCount)
            put("sampleOpenSignals", toolJson.encodeToJsonElement(openSignals.signals))
            put("detectionCoverage", toolJson.encodeToJsonElement(coverage))
            put("vulnerabilities", toolJson.encodeToJsonElement(vulnerabilities))
            put("compliance", compliance)
        }
        return ResourceContent(uri = uri, text = toolJson.encodeToString(result))
    }
}

class OpenSecuritySignalsResource(
    private val gateway: SecurityMcpGateway = defaultSecurityResourceGateway,
) : McpResource {
    override val uri = "moneat://security/signals/open"
    override val name = "Open Security Signals"
    override val description = "Open security signals for the organization"
    override val requiredScopes = setOf(McpScopes.SECURITY_READ)

    override suspend fun read(context: McpContext): ResourceContent {
        val signals = gateway.listSignals(
            context.organizationId,
            SignalFilters(status = "open"),
            SECURITY_RESOURCE_LIMIT,
            0,
        )
        return ResourceContent(uri = uri, text = toolJson.encodeToString(signals))
    }
}

class SecurityDetectionCoverageResource(
    private val gateway: SecurityMcpGateway = defaultSecurityResourceGateway,
) : McpResource {
    override val uri = "moneat://security/detection/coverage"
    override val name = "Security Detection Coverage"
    override val description = "MITRE ATT&CK coverage for enabled security detection rules"
    override val requiredScopes = setOf(McpScopes.SECURITY_READ)

    override suspend fun read(context: McpContext): ResourceContent {
        val coverage = gateway.detectionCoverage(context.organizationId)
        return ResourceContent(uri = uri, text = toolJson.encodeToString(coverage))
    }
}

private const val SECURITY_RESOURCE_LIMIT = 25
