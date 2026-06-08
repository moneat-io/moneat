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

import kotlinx.serialization.Serializable

@Serializable
data class CatalogOwner(
    val team: String,
    val oncall: String,
    val slack: String,
    val repo: String
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
data class CatalogResourceTelemetry(
    val cpuPct: Int,
    val memPct: Int,
    val latencyMs: Int? = null,
    val errorRatePct: Double? = null,
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
    val monthlyUsd: Double,
    val costTrendPct: Double,
    val costBreakdown: List<CatalogCostItem>,
    val relationships: List<CatalogRelationship>,
    val changes: List<CatalogChangeEvent>,
    val metadata: List<CatalogMetaItem>,
    val firstSeen: String,
    val lastChange: String
)
