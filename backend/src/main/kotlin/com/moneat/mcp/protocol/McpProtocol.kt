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

package com.moneat.mcp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: InputSchema,
    val readOnly: Boolean = true,
    val requiredScopes: Set<String> = emptySet(),
)

@Serializable
data class InputSchema(
    val type: String = "object",
    val properties: JsonObject = JsonObject(emptyMap()),
    val required: List<String> = emptyList()
)

@Serializable
data class ToolCallResult(
    val content: List<ToolContent>,
    @SerialName("isError") val isError: Boolean = false
)

@Serializable
data class ToolContent(
    val type: String = "text",
    val text: String? = null
) {
    init {
        require(type != "text" || text != null) {
            "text-typed ToolContent must have non-null text"
        }
    }
}

@Serializable
data class ResourceDefinition(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = "application/json",
    val requiredScopes: Set<String> = emptySet()
)

@Serializable
data class ResourceReadResult(
    val contents: List<ResourceContent>
)

@Serializable
data class ResourceContent(
    val uri: String,
    val mimeType: String? = "application/json",
    val text: String? = null
)
