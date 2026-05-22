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

import com.moneat.mcp.auth.McpAuthorization
import com.moneat.mcp.auth.McpAuthorizationException
import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.McpContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Interface for MCP resource providers.
 */
interface McpResource {
    val uri: String
    val name: String
    val description: String
    val mimeType: String get() = "application/json"
    val requiredScopes: Set<String> get() = setOf(McpScopes.ORG_READ)

    suspend fun read(context: McpContext): ResourceContent
}

/**
 * Registry for MCP resources. Handles discovery and reading.
 */
class McpResourceRegistry {
    private val resources = ConcurrentHashMap<String, McpResource>()

    fun register(resource: McpResource) {
        resources[resource.uri] = resource
        logger.debug { "Registered MCP resource: ${resource.uri}" }
    }

    fun listResources(): List<ResourceDefinition> {
        return resources.values.map { resource ->
            ResourceDefinition(
                uri = resource.uri,
                name = resource.name,
                description = resource.description,
                mimeType = resource.mimeType,
                requiredScopes = resource.requiredScopes,
            )
        }
    }

    suspend fun readResource(
        uri: String,
        context: McpContext
    ): ResourceReadResult {
        val resource = resources[uri]
            ?: run {
                val errorJson = JsonObject(mapOf("error" to JsonPrimitive("Unknown resource: $uri")))
                return ResourceReadResult(
                    contents = listOf(
                        ResourceContent(
                            uri = uri,
                            text = errorJson.toString()
                        )
                    )
                )
            }

        return try {
            McpAuthorization.requireScopes(context, resource.requiredScopes)
            ResourceReadResult(contents = listOf(resource.read(context)))
        } catch (e: McpAuthorizationException) {
            logger.warn(e) { "MCP authorization failed for resource $uri" }
            val errorJson = JsonObject(mapOf("error" to JsonPrimitive(e.message ?: "MCP authorization failed")))
            ResourceReadResult(
                contents = listOf(
                    ResourceContent(
                        uri = uri,
                        text = errorJson.toString()
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(e) { "Error reading MCP resource $uri" }
            val errorJson = JsonObject(mapOf("error" to JsonPrimitive(e.message ?: "Unknown error")))
            ResourceReadResult(
                contents = listOf(
                    ResourceContent(
                        uri = uri,
                        text = errorJson.toString()
                    )
                )
            )
        }
    }

    fun hasResource(uri: String): Boolean = resources.containsKey(uri)
}
