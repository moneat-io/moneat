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

package com.moneat.mcp.models

import com.moneat.mcp.protocol.InputSchema
import kotlinx.serialization.Serializable

@Serializable
data class CreateMcpApiKeyRequest(
    val name: String,
    val enabledTools: List<String>,
    val enabledResources: List<String> = emptyList(),
    val expiresInDays: Int? = null,
)

@Serializable
data class UpdateMcpApiKeyRequest(
    val name: String? = null,
    val enabledTools: List<String>? = null,
    val enabledResources: List<String>? = null,
    val expiresInDays: Int? = null,
)

@Serializable
data class CreateMcpApiKeyResponse(
    val id: String,
    val name: String,
    val keyPrefix: String,
    val key: String,
    val enabledTools: List<String>,
    val enabledResources: List<String>,
    val expiresAt: String? = null,
    val createdAt: String,
)

@Serializable
data class McpApiKeyResponse(
    val id: String,
    val name: String,
    val keyPrefix: String,
    val enabledTools: List<String>,
    val enabledResources: List<String>,
    val lastUsedAt: String? = null,
    val expiresAt: String? = null,
    val createdAt: String,
)

@Serializable
data class McpApiKeysResponse(
    val keys: List<McpApiKeyResponse>,
)

@Serializable
data class McpToolCatalogResponse(
    val sections: List<McpToolCatalogSection>,
    val resources: List<McpResourceCatalogItem>,
)

@Serializable
data class McpToolCatalogSection(
    val id: String,
    val label: String,
    val description: String,
    val tools: List<McpToolCatalogItem>,
)

@Serializable
data class McpToolCatalogItem(
    val name: String,
    val description: String,
    val inputSchema: InputSchema,
    val readOnly: Boolean,
)

@Serializable
data class McpResourceCatalogItem(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null,
)

data class McpApiKeyValidationResult(
    val keyId: Int,
    val organizationId: Int,
    val userId: Int,
    val enabledTools: Set<String>,
    val enabledResources: Set<String>,
)
