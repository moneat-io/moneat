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

import com.moneat.analytics.models.SavedProductFunnelCreateRequest
import com.moneat.analytics.models.SavedProductFunnelUpdateRequest
import com.moneat.analytics.services.FeatureFlagFunnelComparisonDefinition
import com.moneat.analytics.services.ProductAnalyticsFunnelService
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val productFunnelService = ProductAnalyticsFunnelService()
private val savedFunnelGroupByValues = listOf("session_id", "user_id")
private const val FUNNEL_ID_FIELD = "funnel_id"
private const val FLAG_KEY_FIELD = "flag_key"

class ListSavedProductFunnelsTool(
    private val service: ProductAnalyticsFunnelService = productFunnelService,
) : McpTool {
    override val name = "list_saved_product_funnels"
    override val description = "List saved product analytics funnels for a project"
    override val inputSchema = projectIdInputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult = withRequiredProjectId(args) { projectId ->
        jsonResult(service.listFunnels(context.organizationId, projectId))
    }
}

class GetSavedProductFunnelTool(
    private val service: ProductAnalyticsFunnelService = productFunnelService,
) : McpTool {
    override val name = "get_saved_product_funnel"
    override val description = "Get a saved product analytics funnel definition"
    override val inputSchema = savedFunnelIdInputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val funnelId = args.funnelResourceIdResult().getOrElse { return errorResult(it.message!!) }
            ?: return errorResult("$FUNNEL_ID_FIELD is required")
        val funnel = service.getFunnel(context.organizationId, funnelId)
            ?: return errorResult("Saved product funnel not found: $funnelId")
        return jsonResult(funnel)
    }
}

class CreateSavedProductFunnelTool(
    private val service: ProductAnalyticsFunnelService = productFunnelService,
) : McpTool {
    override val name = "create_saved_product_funnel"
    override val description = "Create a saved product analytics funnel for repeated analysis"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            savedFunnelDefinitionProperties() + mapOf(
                "project_id" to schemaProjectId(),
                "name" to schemaString("Saved funnel name"),
                "description" to schemaString("Optional saved funnel description"),
            ),
        ),
        required = listOf("project_id", "name", "steps"),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult = withRequiredProjectId(args) { projectId ->
        val request = parseCreateSavedProductFunnelRequest(projectId, args)
            .getOrElse { return@withRequiredProjectId errorResult(it.message!!) }
        try {
            jsonResult(service.createFunnel(context.organizationId, context.userId, request))
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid saved product funnel")
        }
    }
}

class UpdateSavedProductFunnelTool(
    private val service: ProductAnalyticsFunnelService = productFunnelService,
) : McpTool {
    override val name = "update_saved_product_funnel"
    override val description = "Update a saved product analytics funnel definition"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            savedFunnelDefinitionProperties() + mapOf(
                FUNNEL_ID_FIELD to schemaResourceId("Saved product funnel resource ID"),
                "name" to schemaString("Updated saved funnel name"),
                "description" to schemaString("Updated saved funnel description"),
            ),
        ),
        required = listOf(FUNNEL_ID_FIELD),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val funnelId = args.funnelResourceIdResult().getOrElse { return errorResult(it.message!!) }
            ?: return errorResult("$FUNNEL_ID_FIELD is required")
        val request = parseUpdateSavedProductFunnelRequest(args)
            .getOrElse { return errorResult(it.message!!) }
        try {
            val updated = service.updateFunnel(context.organizationId, funnelId, request)
                ?: return errorResult("Saved product funnel not found: $funnelId")
            return jsonResult(updated)
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Invalid saved product funnel")
        }
    }
}

class DeleteSavedProductFunnelTool(
    private val service: ProductAnalyticsFunnelService = productFunnelService,
) : McpTool {
    override val name = "delete_saved_product_funnel"
    override val description = "Archive a saved product analytics funnel"
    override val readOnly = false
    override val inputSchema = savedFunnelIdInputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val funnelId = args.funnelResourceIdResult().getOrElse { return errorResult(it.message!!) }
            ?: return errorResult("$FUNNEL_ID_FIELD is required")
        return jsonResult(service.deleteFunnel(context.organizationId, funnelId))
    }
}

class RunSavedProductFunnelTool(
    private val service: ProductAnalyticsFunnelService = productFunnelService,
) : McpTool {
    override val name = "run_saved_product_funnel"
    override val description = "Run a saved product analytics funnel over a requested date range"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                FUNNEL_ID_FIELD to schemaResourceId("Saved product funnel resource ID"),
                "period" to schemaString("Relative period such as 7d, 30d, month, 6mo, or 12mo"),
                "date_from" to schemaString("Start date in YYYY-MM-DD format"),
                "date_to" to schemaString("End date in YYYY-MM-DD format"),
            ),
        ),
        required = listOf(FUNNEL_ID_FIELD),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val funnelId = args.funnelResourceIdResult().getOrElse { return errorResult(it.message!!) }
            ?: return errorResult("$FUNNEL_ID_FIELD is required")
        val range = parseProductAnalyticsDateRange(args).getOrElse { return errorResult(it.message!!) }
        val result = service.runFunnel(context.organizationId, funnelId, range.dateFrom, range.dateTo)
            ?: return errorResult("Saved product funnel not found: $funnelId")
        return jsonResult(result)
    }
}

class CompareProductFunnelByFeatureFlagTool(
    private val service: ProductAnalyticsFunnelService = productFunnelService,
) : McpTool {
    override val name = "compare_product_funnel_by_feature_flag"
    override val description =
        "Compare product funnel conversion across feature flag variants using matching analytics identities"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            savedFunnelDefinitionProperties() + mapOf(
                FUNNEL_ID_FIELD to schemaResourceId("Optional saved product funnel resource ID"),
                "project_id" to schemaProjectId("Required when funnel_id is omitted"),
                FLAG_KEY_FIELD to schemaString("Feature flag key to compare"),
                "environment" to schemaString("Optional feature flag environment key"),
                "period" to schemaString("Relative period such as 7d, 30d, month, 6mo, or 12mo"),
                "date_from" to schemaString("Start date in YYYY-MM-DD format"),
                "date_to" to schemaString("End date in YYYY-MM-DD format"),
            ),
        ),
        required = listOf(FLAG_KEY_FIELD),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val flagKey = args.stringContent(FLAG_KEY_FIELD) ?: return errorResult("$FLAG_KEY_FIELD is required")
        val range = parseProductAnalyticsDateRange(args).getOrElse { return errorResult(it.message!!) }
        val definition = funnelComparisonDefinition(args, context, flagKey, range, service)
            .getOrElse { return errorResult(it.message!!) }
        return jsonResult(service.compareFunnelByFeatureFlag(context.organizationId, definition))
    }
}

private fun savedFunnelDefinitionProperties(): Map<String, JsonObject> =
    mapOf(
        "steps" to savedFunnelStringArraySchema("Ordered product analytics event names"),
        "filters" to schemaAnalyticsFilters(),
        "prop_filters" to schemaEventPropertyFilters(),
        "group_by" to schemaEnum("Conversion identity", savedFunnelGroupByValues),
        "source" to schemaString("Optional analytics source filter"),
    )

private fun savedFunnelStringArraySchema(description: String): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("array"),
            "description" to JsonPrimitive(description),
            "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
        ),
    )

private fun savedFunnelIdInputSchema(): InputSchema =
    InputSchema(
        properties = JsonObject(
            mapOf(FUNNEL_ID_FIELD to schemaResourceId("Saved product funnel resource ID")),
        ),
        required = listOf(FUNNEL_ID_FIELD),
    )

private fun parseCreateSavedProductFunnelRequest(
    projectId: Long,
    args: JsonObject,
): Result<SavedProductFunnelCreateRequest> = runCatching {
    SavedProductFunnelCreateRequest(
        projectId = projectId,
        name = args.stringContent("name") ?: throw IllegalArgumentException("name is required"),
        description = args.stringContent("description"),
        steps = parseStringArray(args, "steps").getOrThrow(),
        filters = parseAnalyticsFilters(args).getOrThrow(),
        propFilters = parseEventPropertyFilters(args).getOrThrow(),
        groupBy = parseGroupBy(args).getOrThrow(),
        source = args.stringContent("source"),
    )
}

private fun parseUpdateSavedProductFunnelRequest(args: JsonObject): Result<SavedProductFunnelUpdateRequest> =
    runCatching {
        val steps = if (args.containsKey("steps")) parseStringArray(args, "steps").getOrThrow() else null
        val filters = if (args.containsKey("filters")) parseAnalyticsFilters(args).getOrThrow() else null
        val propFilters = if (args.containsKey("prop_filters")) {
            parseEventPropertyFilters(args).getOrThrow()
        } else {
            null
        }
        val groupBy = if (args.containsKey("group_by")) parseGroupBy(args).getOrThrow() else null
        val request = SavedProductFunnelUpdateRequest(
            name = args.stringContent("name"),
            description = args.stringContent("description"),
            steps = steps,
            filters = filters,
            propFilters = propFilters,
            groupBy = groupBy,
            source = args.stringContent("source"),
        )
        if (
            request.name == null &&
            request.description == null &&
            request.steps == null &&
            request.filters == null &&
            request.propFilters == null &&
            request.groupBy == null &&
            request.source == null
        ) {
            throw IllegalArgumentException("At least one saved funnel field is required")
        }
        request
    }

private fun funnelComparisonDefinition(
    args: JsonObject,
    context: McpContext,
    flagKey: String,
    range: ProductAnalyticsDateRange,
    service: ProductAnalyticsFunnelService,
): Result<FeatureFlagFunnelComparisonDefinition> = runCatching {
    val savedFunnelId = args.funnelResourceIdResult(required = false).getOrThrow()
    val savedFunnel = savedFunnelId?.let { funnelId ->
        service.getFunnel(context.organizationId, funnelId)
            ?: throw IllegalArgumentException("Saved product funnel not found: $funnelId")
    }
    val projectId = savedFunnel?.let { funnel ->
        JsonObject(mapOf("project_id" to JsonPrimitive(funnel.projectId))).projectIdArg()
            ?: throw IllegalArgumentException("Saved product funnel project could not be resolved")
    } ?: args.projectIdArg()
        ?: throw IllegalArgumentException("project_id is required when funnel_id is omitted")

    FeatureFlagFunnelComparisonDefinition(
        projectId = projectId,
        dateFrom = range.dateFrom,
        dateTo = range.dateTo,
        steps = savedFunnel?.steps ?: parseStringArray(args, "steps").getOrThrow(),
        groupBy = savedFunnel?.groupBy ?: parseGroupBy(args).getOrThrow(),
        source = savedFunnel?.source ?: args.stringContent("source"),
        filters = savedFunnel?.filters ?: parseAnalyticsFilters(args).getOrThrow(),
        propFilters = savedFunnel?.propFilters ?: parseEventPropertyFilters(args).getOrThrow(),
        flagKey = flagKey,
        environment = args.stringContent("environment"),
    )
}

private fun JsonObject.funnelResourceIdResult(required: Boolean = true): Result<kotlin.uuid.Uuid?> = runCatching {
    val raw = stringContent(FUNNEL_ID_FIELD)
        ?: run {
            if (required) throw IllegalArgumentException("$FUNNEL_ID_FIELD is required")
            return@runCatching null
        }
    parseResourceId(raw)
        ?: throw IllegalArgumentException("Invalid $FUNNEL_ID_FIELD format")
}
