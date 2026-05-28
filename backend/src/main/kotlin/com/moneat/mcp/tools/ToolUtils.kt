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

package com.moneat.mcp.tools

import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.mcp.protocol.ToolContent
import com.moneat.shared.services.ProjectIdResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

val toolJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = true
}

private val projectIdResolver = ProjectIdResolver()

fun textResult(text: String): ToolCallResult {
    return ToolCallResult(content = listOf(ToolContent(text = text)))
}

fun errorResult(message: String): ToolCallResult {
    return ToolCallResult(
        content = listOf(ToolContent(text = message)),
        isError = true
    )
}

inline fun <reified T> jsonResult(data: T): ToolCallResult {
    val text = toolJson.encodeToString(data)
    return ToolCallResult(content = listOf(ToolContent(text = text)))
}

fun JsonObject.projectIdArg(name: String = "project_id"): Long? =
    this[name]?.jsonPrimitive?.contentOrNull?.let(projectIdResolver::resolve)

fun schemaProjectId(description: String = "Project resource ID or legacy numeric project ID"): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive(description)
    )
)
