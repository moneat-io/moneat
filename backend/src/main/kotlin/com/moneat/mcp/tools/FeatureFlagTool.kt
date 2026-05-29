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

import com.moneat.featureflags.models.CreateFeatureFlagEnvironmentRequest
import com.moneat.featureflags.models.CreateFeatureFlagRequest
import com.moneat.featureflags.models.FeatureFlagSdkKeyRequest
import com.moneat.featureflags.models.FeatureFlagSegmentRequest
import com.moneat.featureflags.models.FeatureFlagValueType
import com.moneat.featureflags.models.FeatureFlagVariantRequest
import com.moneat.featureflags.models.UpdateFeatureFlagConfigRequest
import com.moneat.featureflags.models.UpdateFeatureFlagRequest
import com.moneat.featureflags.services.FeatureFlagService
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement

private val featureFlagService = FeatureFlagService()
private val featureFlagValueTypes = FeatureFlagValueType.entries.map { it.name }
private val featureFlagSdkKeyTypes = listOf("server", "client")
private const val KEY_FIELD = "key"
private const val NAME_FIELD = "name"
private const val FLAG_KEY_FIELD = "flag_key"
private const val FEATURE_FLAG_KEY_DESCRIPTION = "Feature flag key"
private const val KEY_REQUIRED_ERROR = "key is required"
private const val NAME_REQUIRED_ERROR = "name is required"
private const val FLAG_KEY_REQUIRED_ERROR = "flag_key is required"
private const val TAGS_ARRAY_ERROR = "tags must be an array of strings"
private const val VARIANTS_ARRAY_ERROR = "variants must be an array of objects with key and value"
private const val DEFAULT_AUDIT_LIMIT = 50
private const val MAX_AUDIT_LIMIT = 100
private const val DEFAULT_ANALYTICS_HOURS = 24
private val defaultSegmentConditions = JsonObject(mapOf("all" to JsonArray(emptyList())))

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

class ListFeatureFlagEnvironmentsTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "list_feature_flag_environments"
    override val description = "List feature flag environments for the organization"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        return jsonResult(mapOf("environments" to service.listEnvironments(context.organizationId)))
    }
}

class CreateFeatureFlagEnvironmentTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "create_feature_flag_environment"
    override val description = "Create a feature flag environment"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                KEY_FIELD to schemaString("Stable environment key, for example production or staging"),
                NAME_FIELD to schemaString("Human-readable environment name"),
                "description" to schemaString("Optional environment description"),
            )
        ),
        required = listOf(KEY_FIELD, NAME_FIELD)
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val request = when (val parsed = parseCreateEnvironmentRequest(args)) {
            is ParseResult.Failure -> return errorResult(parsed.message)
            is ParseResult.Success -> parsed.value
        }
        return try {
            jsonResult(service.createEnvironment(context.organizationId, context.userId, request))
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid feature flag environment arguments")
        }
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
                KEY_FIELD to schemaString("Stable flag key, for example checkout.enabled"),
                NAME_FIELD to schemaString("Human-readable flag name"),
                "description" to schemaString("Optional internal description"),
                "value_type" to schemaEnum("Variant value type", featureFlagValueTypes),
                "client_visible" to schemaBoolean("Allow client SDK keys to evaluate this flag"),
                "tags" to schemaStringArray("Optional grouping tags"),
                "variants" to schemaVariantArray(),
                "default_variant_key" to schemaString("Default variant key returned when no rule matches"),
                "off_variant_key" to schemaString("Variant key returned while the flag is disabled"),
            )
        ),
        required = listOf(KEY_FIELD, NAME_FIELD, "value_type", "variants")
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

class GetFeatureFlagTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "get_feature_flag"
    override val description = "Get a feature flag by key, optionally scoped to one environment config"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                FLAG_KEY_FIELD to schemaString(FEATURE_FLAG_KEY_DESCRIPTION),
                "environment" to schemaString("Optional environment key"),
            )
        ),
        required = listOf(FLAG_KEY_FIELD)
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val flagKey = args.stringArg(FLAG_KEY_FIELD) ?: return errorResult(FLAG_KEY_REQUIRED_ERROR)
        val flag = service.getFlag(context.organizationId, flagKey, args.stringArg("environment"))
            ?: return errorResult("Feature flag not found: $flagKey")
        return jsonResult(flag)
    }
}

class UpdateFeatureFlagTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "update_feature_flag"
    override val description = "Update feature flag metadata, client visibility, tags, or variants"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                FLAG_KEY_FIELD to schemaString(FEATURE_FLAG_KEY_DESCRIPTION),
                NAME_FIELD to schemaString("Updated display name"),
                "description" to schemaString("Updated internal description"),
                "client_visible" to schemaBoolean("Allow client SDK keys to evaluate this flag"),
                "tags" to schemaStringArray("Replacement grouping tags"),
                "variants" to schemaVariantArray(),
            )
        ),
        required = listOf(FLAG_KEY_FIELD)
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val parsed = when (val result = parseUpdateFeatureFlagArgs(args)) {
            is ParseResult.Failure -> return errorResult(result.message)
            is ParseResult.Success -> result.value
        }
        return try {
            val response = service.updateFlag(
                organizationId = context.organizationId,
                actorUserId = context.userId,
                flagKey = parsed.flagKey,
                request = parsed.request,
            ) ?: return errorResult("Feature flag not found: ${parsed.flagKey}")
            jsonResult(response)
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid feature flag arguments")
        }
    }
}

class DeleteFeatureFlagTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "delete_feature_flag"
    override val description = "Archive a feature flag by key"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf(FLAG_KEY_FIELD to schemaString(FEATURE_FLAG_KEY_DESCRIPTION))),
        required = listOf(FLAG_KEY_FIELD)
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val flagKey = args.stringArg(FLAG_KEY_FIELD) ?: return errorResult(FLAG_KEY_REQUIRED_ERROR)
        val deleted = service.archiveFlag(context.organizationId, context.userId, flagKey)
        return if (deleted) {
            textResult("Feature flag archived: $flagKey")
        } else {
            errorResult("Feature flag not found: $flagKey")
        }
    }
}

class UpdateFeatureFlagConfigTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "update_feature_flag_config"
    override val description = "Update a feature flag's environment-specific config, rollout rules, or variants"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                FLAG_KEY_FIELD to schemaString(FEATURE_FLAG_KEY_DESCRIPTION),
                "environment" to schemaString("Environment key"),
                "enabled" to schemaBoolean("Enable or disable this flag in the environment"),
                "default_variant_key" to schemaString("Variant key returned when no rule matches"),
                "off_variant_key" to schemaString("Variant key returned while the flag is disabled"),
                "rules" to schemaObject("Rules JSON object, for example {\"rules\": []}"),
            )
        ),
        required = listOf(FLAG_KEY_FIELD, "environment")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val parsed = when (val result = parseUpdateConfigArgs(args)) {
            is ParseResult.Failure -> return errorResult(result.message)
            is ParseResult.Success -> result.value
        }
        return try {
            val response = service.updateConfig(
                organizationId = context.organizationId,
                actorUserId = context.userId,
                flagKey = parsed.flagKey,
                environmentKey = parsed.environmentKey,
                request = parsed.request,
            ) ?: return errorResult("Feature flag config not found")
            jsonResult(response)
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid feature flag config arguments")
        }
    }
}

class ListFeatureFlagSegmentsTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "list_feature_flag_segments"
    override val description = "List reusable feature flag targeting segments"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        return jsonResult(mapOf("segments" to service.listSegments(context.organizationId)))
    }
}

class UpsertFeatureFlagSegmentTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "upsert_feature_flag_segment"
    override val description = "Create or update a reusable feature flag targeting segment"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                KEY_FIELD to schemaString("Stable segment key"),
                NAME_FIELD to schemaString("Human-readable segment name"),
                "description" to schemaString("Optional segment description"),
                "conditions" to schemaObject("Targeting conditions JSON object, for example {\"all\": []}"),
            )
        ),
        required = listOf(KEY_FIELD, NAME_FIELD)
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val request = when (val result = parseSegmentRequest(args)) {
            is ParseResult.Failure -> return errorResult(result.message)
            is ParseResult.Success -> result.value
        }
        return try {
            jsonResult(service.upsertSegment(context.organizationId, context.userId, request))
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid feature flag segment arguments")
        }
    }
}

class DeleteFeatureFlagSegmentTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "delete_feature_flag_segment"
    override val description = "Archive a feature flag segment by key"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf("segment_key" to schemaString("Feature flag segment key"))),
        required = listOf("segment_key")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val segmentKey = args.stringArg("segment_key") ?: return errorResult("segment_key is required")
        val deleted = service.deleteSegment(context.organizationId, context.userId, segmentKey)
        return if (deleted) {
            textResult("Feature flag segment archived: $segmentKey")
        } else {
            errorResult("Feature flag segment not found: $segmentKey")
        }
    }
}

class ListFeatureFlagSdkKeysTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "list_feature_flag_sdk_keys"
    override val description = "List active feature flag SDK keys"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        return jsonResult(mapOf("keys" to service.listSdkKeys(context.organizationId)))
    }
}

class CreateFeatureFlagSdkKeyTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "create_feature_flag_sdk_key"
    override val description = "Create a feature flag SDK key and return its one-time secret"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "environment_key" to schemaString("Environment key for this SDK key"),
                NAME_FIELD to schemaString("Human-readable SDK key name"),
                "key_type" to schemaEnum("SDK key type", featureFlagSdkKeyTypes),
            )
        ),
        required = listOf("environment_key", NAME_FIELD, "key_type")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val request = when (val result = parseSdkKeyRequest(args)) {
            is ParseResult.Failure -> return errorResult(result.message)
            is ParseResult.Success -> result.value
        }
        return try {
            jsonResult(service.createSdkKey(context.organizationId, context.userId, request))
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid feature flag SDK key arguments")
        }
    }
}

class RevokeFeatureFlagSdkKeyTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "revoke_feature_flag_sdk_key"
    override val description = "Revoke an active feature flag SDK key"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf("sdk_key_id" to schemaInteger("Feature flag SDK key ID"))),
        required = listOf("sdk_key_id")
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val keyId = when (val result = args.requiredIntegerArg("sdk_key_id")) {
            is ParseResult.Failure -> return errorResult(result.message)
            is ParseResult.Success -> result.value
        }
        val revoked = service.revokeSdkKey(context.organizationId, context.userId, keyId)
        return if (revoked) {
            textResult("Feature flag SDK key revoked: $keyId")
        } else {
            errorResult("Feature flag SDK key not found: $keyId")
        }
    }
}

class ListFeatureFlagAuditEventsTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "list_feature_flag_audit_events"
    override val description = "List recent feature flag audit events"
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf("limit" to schemaInteger("Maximum events to return, from 1 to 100")))
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val limit = when (val result = args.optionalIntegerArg("limit")) {
            is ParseResult.Failure -> return errorResult(result.message)
            is ParseResult.Success -> result.value?.coerceIn(1, MAX_AUDIT_LIMIT) ?: DEFAULT_AUDIT_LIMIT
        }
        return jsonResult(mapOf("events" to service.listAuditEvents(context.organizationId, limit)))
    }
}

class GetFeatureFlagAnalyticsTool(
    private val service: FeatureFlagService = featureFlagService,
) : McpTool {
    override val name = "get_feature_flag_analytics"
    override val description = "Get feature flag evaluation and tracking analytics"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "environment" to schemaString("Optional environment key"),
                "hours" to schemaInteger("Lookback window in hours"),
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val hours = when (val result = args.optionalIntegerArg("hours")) {
            is ParseResult.Failure -> return errorResult(result.message)
            is ParseResult.Success -> result.value ?: DEFAULT_ANALYTICS_HOURS
        }
        return jsonResult(service.analytics(context.organizationId, args.stringArg("environment"), hours))
    }
}

private sealed class ParseResult<out T> {
    data class Success<T>(val value: T) : ParseResult<T>()
    data class Failure(val message: String) : ParseResult<Nothing>()
}

private data class UpdateFeatureFlagArgs(
    val flagKey: String,
    val request: UpdateFeatureFlagRequest,
)

private data class UpdateFeatureFlagConfigArgs(
    val flagKey: String,
    val environmentKey: String,
    val request: UpdateFeatureFlagConfigRequest,
)

private fun parseCreateEnvironmentRequest(args: JsonObject): ParseResult<CreateFeatureFlagEnvironmentRequest> {
    val key = args.stringArg(KEY_FIELD) ?: return ParseResult.Failure(KEY_REQUIRED_ERROR)
    val name = args.stringArg(NAME_FIELD) ?: return ParseResult.Failure(NAME_REQUIRED_ERROR)
    val description = when (val result = args.optionalStringArg("description")) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }
    return ParseResult.Success(
        CreateFeatureFlagEnvironmentRequest(
            key = key,
            name = name,
            description = description,
        )
    )
}

private fun parseCreateFeatureFlagRequest(args: JsonObject): ParseResult<CreateFeatureFlagRequest> {
    val key = args.stringArg(KEY_FIELD) ?: return ParseResult.Failure(KEY_REQUIRED_ERROR)
    val name = args.stringArg(NAME_FIELD) ?: return ParseResult.Failure(NAME_REQUIRED_ERROR)
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

private fun parseUpdateFeatureFlagArgs(args: JsonObject): ParseResult<UpdateFeatureFlagArgs> {
    val flagKey = args.stringArg(FLAG_KEY_FIELD) ?: return ParseResult.Failure(FLAG_KEY_REQUIRED_ERROR)
    val clientVisible = when (val result = args.optionalBooleanArg("client_visible")) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }
    val tags = when (val result = args.optionalTagsArg()) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }
    val variants = when (val result = args.optionalVariantsArg()) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }
    val name = when (val result = args.optionalStringArg(NAME_FIELD)) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }
    val description = when (val result = args.optionalStringArg("description")) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }
    return ParseResult.Success(
        UpdateFeatureFlagArgs(
            flagKey = flagKey,
            request = UpdateFeatureFlagRequest(
                name = name,
                description = description,
                clientVisible = clientVisible,
                tags = tags,
                variants = variants,
            )
        )
    )
}

private fun parseUpdateConfigArgs(args: JsonObject): ParseResult<UpdateFeatureFlagConfigArgs> {
    val flagKey = args.stringArg(FLAG_KEY_FIELD) ?: return ParseResult.Failure(FLAG_KEY_REQUIRED_ERROR)
    val environmentKey = args.stringArg("environment") ?: return ParseResult.Failure("environment is required")
    val enabled = when (val result = args.optionalBooleanArg("enabled")) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }
    val rules = when (val result = args.optionalJsonObjectArg("rules")) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }
    return ParseResult.Success(
        UpdateFeatureFlagConfigArgs(
            flagKey = flagKey,
            environmentKey = environmentKey,
            request = UpdateFeatureFlagConfigRequest(
                enabled = enabled,
                defaultVariantKey = args.stringArg("default_variant_key"),
                offVariantKey = args.stringArg("off_variant_key"),
                rules = rules,
            )
        )
    )
}

private fun parseSegmentRequest(args: JsonObject): ParseResult<FeatureFlagSegmentRequest> {
    val key = args.stringArg(KEY_FIELD) ?: return ParseResult.Failure(KEY_REQUIRED_ERROR)
    val name = args.stringArg(NAME_FIELD) ?: return ParseResult.Failure(NAME_REQUIRED_ERROR)
    val description = when (val result = args.optionalStringArg("description")) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value
    }
    val conditions = when (val result = args.optionalJsonObjectArg("conditions")) {
        is ParseResult.Failure -> return result
        is ParseResult.Success -> result.value ?: defaultSegmentConditions
    }
    return ParseResult.Success(
        FeatureFlagSegmentRequest(
            key = key,
            name = name,
            description = description,
            conditions = conditions,
        )
    )
}

private fun parseSdkKeyRequest(args: JsonObject): ParseResult<FeatureFlagSdkKeyRequest> {
    val environmentKey = args.stringArg("environment_key")
        ?: return ParseResult.Failure("environment_key is required")
    val name = args.stringArg(NAME_FIELD) ?: return ParseResult.Failure(NAME_REQUIRED_ERROR)
    val keyType = args.stringArg("key_type")?.lowercase()
        ?: return ParseResult.Failure("key_type must be one of ${featureFlagSdkKeyTypes.joinToString(", ")}")
    if (keyType !in featureFlagSdkKeyTypes) {
        return ParseResult.Failure("key_type must be one of ${featureFlagSdkKeyTypes.joinToString(", ")}")
    }
    return ParseResult.Success(
        FeatureFlagSdkKeyRequest(
            environmentKey = environmentKey,
            name = name,
            keyType = keyType,
        )
    )
}

private fun JsonObject.valueTypeArg(): FeatureFlagValueType? {
    val raw = stringArg("value_type")?.uppercase() ?: return null
    return FeatureFlagValueType.entries.firstOrNull { it.name == raw }
}

private fun JsonObject.optionalBooleanArg(name: String): ParseResult<Boolean?> {
    val value = this[name] ?: return ParseResult.Success(null)
    if (value == JsonNull) return ParseResult.Success(null)
    val primitive = value as? JsonPrimitive ?: return ParseResult.Failure("$name must be true or false")
    return primitive.booleanOrNull?.let { ParseResult.Success(it) }
        ?: ParseResult.Failure("$name must be true or false")
}

private fun JsonObject.booleanArg(name: String): ParseResult<Boolean> {
    val value = this[name] ?: return ParseResult.Success(false)
    val primitive = value as? JsonPrimitive ?: return ParseResult.Failure("$name must be true or false")
    return primitive.booleanOrNull?.let { ParseResult.Success(it) }
        ?: ParseResult.Failure("$name must be true or false")
}

private fun JsonObject.optionalTagsArg(): ParseResult<List<String>?> {
    val value = this["tags"] ?: return ParseResult.Success(null)
    return parseTags(value)
}

private fun JsonObject.tagsArg(): ParseResult<List<String>> {
    val value = this["tags"] ?: return ParseResult.Success(emptyList())
    return parseTags(value)
}

private fun parseTags(value: JsonElement): ParseResult<List<String>> {
    val tags = value as? JsonArray ?: return ParseResult.Failure(TAGS_ARRAY_ERROR)
    return ParseResult.Success(
        tags.mapNotNull { element ->
            val primitive = element as? JsonPrimitive
                ?: return ParseResult.Failure(TAGS_ARRAY_ERROR)
            if (!primitive.isString) return ParseResult.Failure(TAGS_ARRAY_ERROR)
            primitive.content.trim().takeIf { it.isNotEmpty() }
        }
    )
}

private fun JsonObject.optionalVariantsArg(): ParseResult<List<FeatureFlagVariantRequest>?> {
    val value = this["variants"] ?: return ParseResult.Success(null)
    return when (val result = parseVariants(value)) {
        is ParseResult.Failure -> result
        is ParseResult.Success -> ParseResult.Success(result.value)
    }
}

private fun JsonObject.variantsArg(): ParseResult<List<FeatureFlagVariantRequest>> {
    val value = this["variants"] ?: return ParseResult.Failure("variants is required")
    return parseVariants(value)
}

private fun parseVariants(value: JsonElement): ParseResult<List<FeatureFlagVariantRequest>> {
    if (value !is JsonArray) {
        return ParseResult.Failure(VARIANTS_ARRAY_ERROR)
    }
    return try {
        ParseResult.Success(toolJson.decodeFromJsonElement(value))
    } catch (e: SerializationException) {
        ParseResult.Failure(VARIANTS_ARRAY_ERROR)
    } catch (e: IllegalArgumentException) {
        ParseResult.Failure(VARIANTS_ARRAY_ERROR)
    }
}

private fun JsonObject.optionalJsonObjectArg(name: String): ParseResult<JsonElement?> {
    val value = this[name] ?: return ParseResult.Success(null)
    if (value == JsonNull) return ParseResult.Success(null)
    if (value !is JsonObject) {
        return ParseResult.Failure("$name must be a JSON object")
    }
    return ParseResult.Success(value)
}

private fun JsonObject.requiredIntegerArg(name: String): ParseResult<Int> {
    return when (val result = optionalIntegerArg(name)) {
        is ParseResult.Failure -> result
        is ParseResult.Success -> result.value?.let { ParseResult.Success(it) }
            ?: ParseResult.Failure("$name is required")
    }
}

private fun JsonObject.optionalIntegerArg(name: String): ParseResult<Int?> {
    val value = this[name] ?: return ParseResult.Success(null)
    if (value == JsonNull) return ParseResult.Success(null)
    val primitive = value as? JsonPrimitive ?: return ParseResult.Failure("$name must be an integer")
    return primitive.content.toIntOrNull()?.let { ParseResult.Success(it) }
        ?: ParseResult.Failure("$name must be an integer")
}

private fun JsonObject.optionalStringArg(name: String): ParseResult<String?> {
    val value = this[name] ?: return ParseResult.Success(null)
    if (value == JsonNull) return ParseResult.Success(null)
    val primitive = value as? JsonPrimitive ?: return ParseResult.Failure("$name must be a string")
    if (!primitive.isString) return ParseResult.Failure("$name must be a string")
    return ParseResult.Success(primitive.content.trim())
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
                        KEY_FIELD to schemaString("Variant key"),
                        NAME_FIELD to schemaString("Optional variant display name"),
                        "value" to schemaJsonValue("Variant JSON value"),
                    )
                ),
                "required" to JsonArray(listOf(JsonPrimitive(KEY_FIELD), JsonPrimitive("value"))),
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
