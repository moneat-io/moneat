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

import com.moneat.featureflags.models.CreateFeatureFlagRequest
import com.moneat.featureflags.models.FeatureFlagValueType
import com.moneat.featureflags.models.FeatureFlagVariantRequest
import com.moneat.featureflags.services.FeatureFlagService
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement

private val featureFlagService = FeatureFlagService()
private val featureFlagValueTypes = FeatureFlagValueType.entries.map { it.name }

class ListFeatureFlagsTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "list_feature_flags"
    override val description = "List feature flags and environments for the organization"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "environment" to schemaString(
                    "Optional environment key to include the most relevant config first"
                )
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val environment = args.stringArg("environment")
        return jsonResult(service.listFlags(context.organizationId, environment))
    }
}

class CreateFeatureFlagTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "create_feature_flag"
    override val description = "Create a feature flag with variants for OpenFeature evaluation"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "key" to schemaString("Stable flag key, for example checkout.enabled"),
                "name" to schemaString("Human-readable flag name"),
                "description" to schemaString("Optional internal description"),
                "value_type" to schemaEnum("Variant value type", featureFlagValueTypes),
                "client_visible" to schemaBoolean("Allow client SDK keys to evaluate this flag"),
                "tags" to schemaStringArray("Optional grouping tags"),
                "variants" to schemaVariantArray(),
                "default_variant_key" to schemaString("Default variant key returned when no rule matches"),
                "off_variant_key" to schemaString("Variant key returned while the flag is disabled"),
            )
        ),
        required = listOf("key", "name", "value_type", "variants")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val request = when (val parsed = parseCreateFeatureFlagRequest(args)) {
            is ParseResult.Failure -> return errorResult(parsed.message)
            is ParseResult.Success -> parsed.value
        }
        return try {
            jsonResult(service.createFlag(context.organizationId, context.userId, request))
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid feature flag arguments")
        }
    }
}

private sealed class ParseResult<out T> {
    data class Success<T>(val value: T) : ParseResult<T>()
    data class Failure(val message: String) : ParseResult<Nothing>()
}

private fun parseCreateFeatureFlagRequest(args: JsonObject): ParseResult<CreateFeatureFlagRequest> {
    val key = args.stringArg("key") ?: return ParseResult.Failure("key is required")
    val name = args.stringArg("name") ?: return ParseResult.Failure("name is required")
    val valueType = args.valueTypeArg() ?: return ParseResult.Failure(
        "value_type must be one of ${featureFlagValueTypes.joinToString(", ")}"
    )
    val variants = when (val result = args.variantsArg()) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }
    val clientVisible = when (val result = args.booleanArg("client_visible")) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }
    val tags = when (val result = args.tagsArg()) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }

    return ParseResult.Success(
        CreateFeatureFlagRequest(
            key = key,
            name = name,
            description = args.stringArg("description"),
            valueType = valueType,
            clientVisible = clientVisible,
            tags = tags,
            variants = variants,
            defaultVariantKey = args.stringArg("default_variant_key"),
            offVariantKey = args.stringArg("off_variant_key"),
        )
    )
}

private fun JsonObject.valueTypeArg(): FeatureFlagValueType? {
    val raw = stringArg("value_type")?.uppercase() ?: return null
    return FeatureFlagValueType.entries.firstOrNull { it.name == raw }
}

private fun JsonObject.booleanArg(name: String): ParseResult<Boolean> {
    val value = this[name] ?: return ParseResult.Success(false)
    val primitive = value as? JsonPrimitive ?: return ParseResult.Failure("$name must be true or false")
    return primitive.booleanOrNull?.let { ParseResult.Success(it) }
        ?: ParseResult.Failure("$name must be true or false")
}

private fun JsonObject.tagsArg(): ParseResult<List<String>> {
    val value = this["tags"] ?: return ParseResult.Success(emptyList())
    val tags = value as? JsonArray ?: return ParseResult.Failure("tags must be an array of strings")
    return ParseResult.Success(
        tags.mapNotNull { element ->
            val primitive = element as? JsonPrimitive
                ?: return ParseResult.Failure("tags must be an array of strings")
            if (!primitive.isString) return ParseResult.Failure("tags must be an array of strings")
            primitive.content.trim().takeIf { it.isNotEmpty() }
        }
    )
}

private fun JsonObject.variantsArg(): ParseResult<List<FeatureFlagVariantRequest>> {
    val value = this["variants"] ?: return ParseResult.Failure("variants is required")
    if (value !is JsonArray) {
        return ParseResult.Failure("variants must be an array of objects with key and value")
    }
    return try {
        ParseResult.Success(toolJson.decodeFromJsonElement(value))
    } catch (e: SerializationException) {
        ParseResult.Failure("variants must be an array of objects with key and value")
    } catch (e: IllegalArgumentException) {
        ParseResult.Failure("variants must be an array of objects with key and value")
    }
}

private fun JsonObject.stringArg(name: String): String? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.trim().takeIf { it.isNotEmpty() }
}

private fun schemaStringArray(description: String): JsonObject =
    schemaArray(description, JsonObject(mapOf("type" to JsonPrimitive("string"))))

private fun schemaVariantArray(): JsonObject {
    return schemaArray(
        "Variant definitions. Each item needs key and value; name is optional.",
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(
                    mapOf(
                        "key" to schemaString("Variant key"),
                        "name" to schemaString("Optional variant display name"),
                        "value" to schemaJsonValue("Variant JSON value"),
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive("key"), JsonPrimitive("value"))),
            )
        )
    )
}

private fun schemaArray(description: String, items: JsonObject): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("array"),
            "description" to JsonPrimitive(description),
            "items" to items,
        )
    )

private fun schemaJsonValue(description: String): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonArray(
                listOf(
                    JsonPrimitive("boolean"),
                    JsonPrimitive("string"),
                    JsonPrimitive("number"),
                    JsonPrimitive("object"),
                    JsonPrimitive("array"),
                    JsonPrimitive("null"),
                )
            ),
            "description" to JsonPrimitive(description),
        )
    )
