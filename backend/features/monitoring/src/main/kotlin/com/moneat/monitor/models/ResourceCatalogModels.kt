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

package com.moneat.monitor.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class CatalogOwner(
    val teamId: String,
    val teamName: String,
    val slack: String? = null,
    val repo: String? = null,
    val onCallScheduleId: String? = null,
    val escalationPolicyId: String? = null,
    val currentOnCall: CatalogCurrentOnCall? = null
)

@Serializable
data class ResourceOwnershipClaim(
    val resourceId: String,
    val teamId: String
)

@Serializable
data class CatalogCurrentOnCall(
    val userId: String,
    val userName: String
)

@Serializable
data class CatalogVulnerabilityCounts(
    val critical: Int = 0,
    val high: Int = 0,
    val medium: Int = 0,
    val low: Int = 0
)

@Serializable
data class CatalogRelationship(
    val relation: String,
    val name: String,
    val kind: String,
    val health: String,
    val targetId: String? = null
)

@Serializable
data class CatalogChangeEvent(
    val ts: String,
    val kind: String,
    val summary: String,
    val actor: String
)

@Serializable
data class CatalogCostItem(
    val label: String,
    val usd: Double
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class CatalogResourceTelemetry(
    val cpuPct: Int? = null,
    val memPct: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val latencyMs: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val errorRatePct: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val throughput: String? = null
)

@Serializable
data class CatalogMetaItem(
    val label: String,
    val value: String
)

@Serializable
data class CatalogPosture(
    val label: String,
    val pass: Boolean
)

@Serializable
data class CatalogSecurityFinding(
    val id: String,
    val severity: String,
    val pkg: String,
    val fixedVersion: String? = null,
    val cvss: Double? = null
)

@Serializable
data class CatalogResource(
    val id: String,
    val name: String,
    val kind: String,
    val health: String,
    val environment: String,
    val region: String,
    val cloud: String,
    val owner: CatalogOwner?,
    val tags: List<String>,
    val telemetry: CatalogResourceTelemetry,
    val vulns: CatalogVulnerabilityCounts,
    val sbomComponents: Int,
    val posture: List<CatalogPosture>,
    val findings: List<CatalogSecurityFinding> = emptyList(),
    val monthlyUsd: Double,
    val costTrendPct: Double,
    val costBreakdown: List<CatalogCostItem>,
    val relationships: List<CatalogRelationship>,
    val changes: List<CatalogChangeEvent>,
    val metadata: List<CatalogMetaItem>,
    val firstSeen: String,
    val lastChange: String
)
