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
data class CloudSourceProviderConfig(
    val accountId: String? = null,
    val roleName: String? = null,
    val projectId: String? = null,
    val tenantId: String? = null,
    val subscriptionId: String? = null,
    val billingExportTable: String? = null
)

@Serializable
data class CloudSourceCreateRequest(
    val provider: String,
    val displayName: String,
    val config: CloudSourceProviderConfig = CloudSourceProviderConfig(),
    val collectMetrics: Boolean = true,
    val collectInventory: Boolean = true,
    val collectCost: Boolean = false,
    val collectLogs: Boolean = false
)

@Serializable
data class CloudSourceResponse(
    val id: String,
    val provider: String,
    val displayName: String,
    val status: String,
    val config: CloudSourceProviderConfig,
    val collectMetrics: Boolean,
    val collectInventory: Boolean,
    val collectCost: Boolean,
    val collectLogs: Boolean,
    val externalId: String,
    val lastSyncAt: String?,
    val lastError: String?,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class CloudSourceSetupPreview(
    val provider: String,
    val externalId: String,
    val principal: String,
    val snippetLabel: String,
    val snippetLanguage: String,
    val snippet: String
)

@Serializable
data class CloudSourceSyncResource(
    val resourceId: String,
    val name: String,
    val resourceType: String,
    val provider: String,
    val account: String = "",
    val region: String = "global",
    val health: String = "healthy",
    val tags: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
    val cpuPercent: Double = 0.0,
    val memPercent: Double = 0.0,
    val monthlyUsd: Double = 0.0,
    val costTrendPct: Double = 0.0
)

@Serializable
data class CloudSourceSyncResult(
    val resources: List<CloudSourceSyncResource>
)
