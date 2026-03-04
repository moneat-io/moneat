// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.mcp.protocol

import com.moneat.enterprise.mcp.models.McpContext
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
                mimeType = resource.mimeType
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
            ResourceReadResult(contents = listOf(resource.read(context)))
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
