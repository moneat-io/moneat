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

import com.moneat.analytics.models.AnalyticsFilter
import com.moneat.analytics.models.BreakdownRow
import com.moneat.analytics.models.EventPropertyFilter
import com.moneat.analytics.models.FunnelStep
import com.moneat.analytics.models.ProductRetentionCohortRow
import com.moneat.analytics.services.AnalyticsService
import com.moneat.analytics.services.ProductRetentionRequest
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeParseException

private const val DEFAULT_ANALYTICS_PERIOD = "30d"
private const val DEFAULT_EVENT_LIMIT = 50
private const val MAX_EVENT_LIMIT = 200
private const val MIN_FUNNEL_STEPS = 2
private const val MAX_FUNNEL_STEPS = 12
private const val DEFAULT_PRODUCT_RETENTION_PERIOD_COUNT = 7
private const val MIN_PRODUCT_RETENTION_PERIOD_COUNT = 1
private const val MAX_PRODUCT_RETENTION_PERIOD_COUNT = 12
private const val PERIOD_7D_OFFSET_DAYS = 6L
private const val PERIOD_30D_OFFSET_DAYS = 29L
private const val PERIOD_6MO_MONTHS = 6L
private const val PERIOD_12MO_MONTHS = 12L
private const val MAX_ANALYTICS_RANGE_DAYS = 366L
private const val MAX_EVENT_PROPERTY_KEY_LENGTH = 128
private const val FILTER_PARTS_COUNT = 3
private const val PRODUCT_RETENTION_MODE_KEY_ACTION = "key_action"
private const val PRODUCT_RETENTION_MODE_ANY_SESSION = "any_session"
private const val PRODUCT_RETENTION_MODE_CUSTOM = "custom"

private val analyticsPeriodValues = setOf("today", "7d", "30d", "month", "6mo", "12mo", "custom")
private val analyticsGroupByValues = setOf("session_id", "user_id")
private val analyticsFilterOperators = setOf("is", "is_not", "contains", "not_contains")
private val analyticsFilterProperties = setOf(
    "page",
    "pathname",
    "entry_page",
    "exit_page",
    "source",
    "referrer_source",
    "country",
    "country_code",
    "browser",
    "os",
    "device",
    "device_type",
    "utm_source",
    "utm_medium",
    "utm_campaign",
    "utm_term",
    "utm_content",
    "event",
    "event_name",
)
private val productRetentionModeValues = setOf(
    PRODUCT_RETENTION_MODE_KEY_ACTION,
    PRODUCT_RETENTION_MODE_ANY_SESSION,
    PRODUCT_RETENTION_MODE_CUSTOM,
)

class GetProductFunnelTool(
    private val analyticsService: AnalyticsService = AnalyticsService(),
) : McpTool {
    override val name = "get_product_funnel"
    override val description =
        "Get product analytics funnel conversion for a project from canonical analytics events"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            productAnalyticsBaseProperties() + mapOf(
                "filters" to schemaAnalyticsFilters(),
                "prop_filters" to schemaEventPropertyFilters(),
                "steps" to schemaStringArray("Ordered analytics event names in the funnel"),
                "group_by" to schemaEnum("Conversion identity", analyticsGroupByValues.toList()),
                "source" to schemaString("Optional telemetry source filter, such as server or web"),
            ),
        ),
        required = listOf("project_id", "steps"),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult = withRequiredProjectId(args) { projectId ->
        val range = parseProductAnalyticsDateRange(args).getOrElse {
            return@withRequiredProjectId errorResult(it.message!!)
        }
        val steps = parseStringArray(args, "steps").getOrElse {
            return@withRequiredProjectId errorResult(it.message!!)
        }
        if (steps.size < MIN_FUNNEL_STEPS) {
            return@withRequiredProjectId errorResult("steps must include at least $MIN_FUNNEL_STEPS event names")
        }
        if (steps.size > MAX_FUNNEL_STEPS) {
            return@withRequiredProjectId errorResult("steps cannot include more than $MAX_FUNNEL_STEPS event names")
        }
        val groupBy = parseGroupBy(args).getOrElse { return@withRequiredProjectId errorResult(it.message!!) }
        val filters = parseAnalyticsFilters(args).getOrElse {
            return@withRequiredProjectId errorResult(it.message!!)
        }
        val propFilters = parseEventPropertyFilters(args).getOrElse {
            return@withRequiredProjectId errorResult(it.message!!)
        }
        val source = args.stringContent("source")
        val result = analyticsService.getFunnel(
            projectId = projectId,
            dateFrom = range.dateFrom,
            dateTo = range.dateTo,
            steps = steps,
            groupBy = groupBy,
            source = source,
            filters = filters,
            propFilters = propFilters,
        )

        jsonResult(
            ProductFunnelToolResponse(
                dateFrom = range.dateFrom.toString(),
                dateTo = range.dateTo.toString(),
                groupBy = groupBy,
                source = source,
                filters = filters,
                propFilters = propFilters,
                steps = result.steps,
                overallConversion = result.overallConversion,
            ),
        )
    }
}

class GetProductEventsTool(
    private val analyticsService: AnalyticsService = AnalyticsService(),
) : McpTool {
    override val name = "get_product_events"
    override val description =
        "Get product analytics event counts and unique identity counts for a project"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            productAnalyticsBaseProperties() + mapOf(
                "filters" to schemaAnalyticsFilters(),
                "prop_filters" to schemaEventPropertyFilters(),
                "group_by" to schemaEnum("Identity used for unique counts", analyticsGroupByValues.toList()),
                "source" to schemaString("Optional telemetry source filter, such as server or web"),
                "limit" to schemaNumber("Maximum events to return (default 50, max 200)"),
            ),
        ),
        required = listOf("project_id"),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult = withRequiredProjectId(args) { projectId ->
        val range = parseProductAnalyticsDateRange(args).getOrElse {
            return@withRequiredProjectId errorResult(it.message!!)
        }
        val filters = parseAnalyticsFilters(args).getOrElse {
            return@withRequiredProjectId errorResult(it.message!!)
        }
        val propFilters = parseEventPropertyFilters(args).getOrElse {
            return@withRequiredProjectId errorResult(it.message!!)
        }
        val groupBy = parseGroupBy(args).getOrElse {
            return@withRequiredProjectId errorResult(it.message!!)
        }
        val source = args.stringContent("source")
        val limit = parseBoundedInt(args, "limit", DEFAULT_EVENT_LIMIT, 1, MAX_EVENT_LIMIT)
            .getOrElse { return@withRequiredProjectId errorResult(it.message!!) }
        val result = analyticsService.getEvents(
            projectId = projectId,
            dateFrom = range.dateFrom,
            dateTo = range.dateTo,
            filters = filters,
            limit = limit,
            groupBy = groupBy,
            source = source,
            propFilters = propFilters,
        )

        jsonResult(
            ProductEventsToolResponse(
                dateFrom = range.dateFrom.toString(),
                dateTo = range.dateTo.toString(),
                groupBy = groupBy,
                source = source,
                filters = filters,
                propFilters = propFilters,
                events = result.results,
            ),
        )
    }
}

class GetProductRetentionTool(
    private val analyticsService: AnalyticsService = AnalyticsService(),
) : McpTool {
    override val name = "get_product_retention"
    override val description =
        "Get product signup cohort retention by key action, any session, or a custom product event"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            productAnalyticsBaseProperties() + mapOf(
                "filters" to schemaAnalyticsFilters(),
                "mode" to schemaEnum("Retention return behavior", productRetentionModeValues.toList()),
                "custom_event" to schemaString("Required when mode is custom"),
                "period_count" to schemaNumber("Weekly periods to return (default 7, max 12)"),
            ),
        ),
        required = listOf("project_id"),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult = withRequiredProjectId(args) { projectId ->
        val range = parseProductAnalyticsDateRange(args).getOrElse {
            return@withRequiredProjectId errorResult(it.message!!)
        }
        val filters = parseAnalyticsFilters(args).getOrElse {
            return@withRequiredProjectId errorResult(it.message!!)
        }
        val mode = args.stringContent("mode") ?: PRODUCT_RETENTION_MODE_KEY_ACTION
        if (mode !in productRetentionModeValues) {
            return@withRequiredProjectId errorResult(
                "mode must be one of: ${productRetentionModeValues.joinToString(", ")}",
            )
        }
        val customEvent = args.stringContent("custom_event")
        if (mode == PRODUCT_RETENTION_MODE_CUSTOM && customEvent == null) {
            return@withRequiredProjectId errorResult("custom_event is required when mode is custom")
        }
        val periodCount = parseBoundedInt(
            args,
            "period_count",
            DEFAULT_PRODUCT_RETENTION_PERIOD_COUNT,
            MIN_PRODUCT_RETENTION_PERIOD_COUNT,
            MAX_PRODUCT_RETENTION_PERIOD_COUNT,
        ).getOrElse { return@withRequiredProjectId errorResult(it.message!!) }
        val result = analyticsService.getProductRetention(
            projectId,
            ProductRetentionRequest(
                dateFrom = range.dateFrom,
                dateTo = range.dateTo,
                filters = filters,
                mode = mode,
                customEvent = customEvent,
                periodCount = periodCount,
            ),
        )

        jsonResult(
            ProductRetentionToolResponse(
                dateFrom = range.dateFrom.toString(),
                dateTo = range.dateTo.toString(),
                mode = result.mode,
                filters = filters,
                periods = result.periods,
                cohorts = result.cohorts,
            ),
        )
    }
}

internal data class ProductAnalyticsDateRange(
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
)

@Serializable
private data class ProductFunnelToolResponse(
    val dateFrom: String,
    val dateTo: String,
    val groupBy: String,
    val source: String? = null,
    val filters: List<AnalyticsFilter>,
    val propFilters: List<EventPropertyFilter>,
    val steps: List<FunnelStep>,
    val overallConversion: Double,
)

@Serializable
private data class ProductEventsToolResponse(
    val dateFrom: String,
    val dateTo: String,
    val groupBy: String,
    val source: String? = null,
    val filters: List<AnalyticsFilter>,
    val propFilters: List<EventPropertyFilter>,
    val events: List<BreakdownRow>,
)

@Serializable
private data class ProductRetentionToolResponse(
    val dateFrom: String,
    val dateTo: String,
    val mode: String,
    val filters: List<AnalyticsFilter>,
    val periods: List<Int>,
    val cohorts: List<ProductRetentionCohortRow>,
)

private fun productAnalyticsBaseProperties(): Map<String, JsonObject> =
    mapOf(
        "project_id" to schemaProjectId(),
        "period" to schemaEnum("Relative period", analyticsPeriodValues.toList()),
        "date_from" to schemaString("Start date in YYYY-MM-DD format"),
        "date_to" to schemaString("End date in YYYY-MM-DD format"),
    )

internal fun parseProductAnalyticsDateRange(args: JsonObject): Result<ProductAnalyticsDateRange> = runCatching {
    val explicitFrom = args.stringContent("date_from") ?: args.stringContent("from")
    val explicitTo = args.stringContent("date_to") ?: args.stringContent("to")
    val range = if (explicitFrom != null || explicitTo != null) {
        if (explicitFrom == null || explicitTo == null) {
            throw IllegalArgumentException("date_from and date_to are both required for custom date ranges")
        }
        parseDate(explicitFrom, "date_from") to parseDate(explicitTo, "date_to")
    } else {
        dateRangeForPeriod(args.stringContent("period") ?: DEFAULT_ANALYTICS_PERIOD)
    }
    validateDateRange(range.first, range.second)
    ProductAnalyticsDateRange(range.first, range.second)
}

private fun dateRangeForPeriod(period: String): Pair<LocalDate, LocalDate> {
    if (period !in analyticsPeriodValues) {
        throw IllegalArgumentException("period must be one of: ${analyticsPeriodValues.joinToString(", ")}")
    }
    val now = LocalDate.now()
    return when (period) {
        "today" -> now to now
        "7d" -> now.minusDays(PERIOD_7D_OFFSET_DAYS) to now
        "30d" -> now.minusDays(PERIOD_30D_OFFSET_DAYS) to now
        "month" -> now.withDayOfMonth(1) to now
        "6mo" -> now.minusMonths(PERIOD_6MO_MONTHS) to now
        "12mo" -> now.minusMonths(PERIOD_12MO_MONTHS) to now
        "custom" -> throw IllegalArgumentException("date_from and date_to are required when period is custom")
        else -> error("Unhandled analytics period: $period")
    }
}

private fun parseDate(value: String, fieldName: String): LocalDate =
    try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("$fieldName must be a valid YYYY-MM-DD date")
    }

private fun validateDateRange(dateFrom: LocalDate, dateTo: LocalDate) {
    if (dateFrom.isAfter(dateTo)) {
        throw IllegalArgumentException("date_from must be on or before date_to")
    }
    val days = dateTo.toEpochDay() - dateFrom.toEpochDay() + 1
    if (days > MAX_ANALYTICS_RANGE_DAYS) {
        throw IllegalArgumentException("date range cannot exceed $MAX_ANALYTICS_RANGE_DAYS days")
    }
}

internal fun parseGroupBy(args: JsonObject): Result<String> = runCatching {
    val groupBy = args.stringContent("group_by") ?: "session_id"
    if (groupBy !in analyticsGroupByValues) {
        throw IllegalArgumentException("group_by must be one of: ${analyticsGroupByValues.joinToString(", ")}")
    }
    groupBy
}

internal fun parseStringArray(args: JsonObject, fieldName: String): Result<List<String>> = runCatching {
    val value = args[fieldName] ?: throw IllegalArgumentException("$fieldName is required")
    val values = when (value) {
        is JsonArray -> value.mapIndexed { index, element ->
            element.stringValue("$fieldName[$index]")
        }
        is JsonPrimitive -> value.stringListValue(fieldName)
        else -> throw IllegalArgumentException("$fieldName must be an array of strings")
    }.map(String::trim)

    if (values.any(String::isBlank)) {
        throw IllegalArgumentException("$fieldName must not contain blank values")
    }
    values
}

private fun JsonPrimitive.stringListValue(fieldName: String): List<String> {
    val raw = contentOrNull ?: throw IllegalArgumentException("$fieldName must be an array of strings")
    return raw.split(",").map(String::trim)
}

private fun JsonElement.stringValue(fieldName: String): String =
    try {
        jsonPrimitive.content
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("$fieldName must be a string")
    }

internal fun parseAnalyticsFilters(args: JsonObject): Result<List<AnalyticsFilter>> = runCatching {
    val value = args["filters"] ?: return@runCatching emptyList()
    when (value) {
        is JsonArray -> value.mapIndexed { index, element -> parseAnalyticsFilter(element, "filters[$index]") }
        is JsonPrimitive -> listOf(parseFilterString(value.contentOrNull.orEmpty(), "filters"))
        else -> throw IllegalArgumentException("filters must be an array")
    }
}

internal fun parseEventPropertyFilters(args: JsonObject): Result<List<EventPropertyFilter>> = runCatching {
    val value = args["prop_filters"] ?: args["property_filters"] ?: return@runCatching emptyList()
    when (value) {
        is JsonArray -> value.mapIndexed { index, element ->
            parseEventPropertyFilter(element, "prop_filters[$index]")
        }
        is JsonPrimitive -> listOf(parseEventPropertyFilterString(value.contentOrNull.orEmpty(), "prop_filters"))
        else -> throw IllegalArgumentException("prop_filters must be an array")
    }
}

private fun parseAnalyticsFilter(element: JsonElement, label: String): AnalyticsFilter =
    when (element) {
        is JsonObject -> parseFilterObject(element, label)
        is JsonPrimitive -> parseFilterString(element.contentOrNull.orEmpty(), label)
        else -> throw IllegalArgumentException("$label must be a filter object or property:operator:value string")
    }

private fun parseEventPropertyFilter(element: JsonElement, label: String): EventPropertyFilter =
    when (element) {
        is JsonObject -> parseEventPropertyFilterObject(element, label)
        is JsonPrimitive -> parseEventPropertyFilterString(element.contentOrNull.orEmpty(), label)
        else -> throw IllegalArgumentException("$label must be a filter object or key:operator:value string")
    }

private fun parseFilterObject(element: JsonObject, label: String): AnalyticsFilter {
    val property = element.stringContent("property") ?: throw IllegalArgumentException("$label.property is required")
    val operator = element.stringContent("operator") ?: throw IllegalArgumentException("$label.operator is required")
    val value = element.stringContent("value") ?: throw IllegalArgumentException("$label.value is required")
    return validatedFilter(property, operator, value, label)
}

private fun parseEventPropertyFilterObject(element: JsonObject, label: String): EventPropertyFilter {
    val key = element.stringContent("key") ?: throw IllegalArgumentException("$label.key is required")
    val operator = element.stringContent("operator") ?: throw IllegalArgumentException("$label.operator is required")
    val value = element.stringContent("value") ?: throw IllegalArgumentException("$label.value is required")
    return validatedEventPropertyFilter(key, operator, value, label)
}

private fun parseFilterString(value: String, label: String): AnalyticsFilter {
    val parts = value.split(":", limit = FILTER_PARTS_COUNT)
    if (parts.size != FILTER_PARTS_COUNT) {
        throw IllegalArgumentException("$label must use property:operator:value format")
    }
    return validatedFilter(parts[0].trim(), parts[1].trim(), parts[2].trim(), label)
}

private fun parseEventPropertyFilterString(value: String, label: String): EventPropertyFilter {
    val parts = value.split(":", limit = FILTER_PARTS_COUNT)
    if (parts.size != FILTER_PARTS_COUNT) {
        throw IllegalArgumentException("$label must use key:operator:value format")
    }
    return validatedEventPropertyFilter(parts[0].trim(), parts[1].trim(), parts[2].trim(), label)
}

private fun validatedFilter(
    property: String,
    operator: String,
    value: String,
    label: String,
): AnalyticsFilter {
    if (property !in analyticsFilterProperties) {
        throw IllegalArgumentException(
            "$label.property must be one of: " +
                analyticsFilterProperties.joinToString(", "),
        )
    }
    if (operator !in analyticsFilterOperators) {
        throw IllegalArgumentException(
            "$label.operator must be one of: " +
                analyticsFilterOperators.joinToString(", "),
        )
    }
    if (value.isBlank()) {
        throw IllegalArgumentException("$label.value is required")
    }
    return AnalyticsFilter(property, operator, value)
}

private fun validatedEventPropertyFilter(
    key: String,
    operator: String,
    value: String,
    label: String,
): EventPropertyFilter {
    if (key.isBlank()) {
        throw IllegalArgumentException("$label.key is required")
    }
    if (key.length > MAX_EVENT_PROPERTY_KEY_LENGTH) {
        throw IllegalArgumentException("$label.key cannot exceed $MAX_EVENT_PROPERTY_KEY_LENGTH characters")
    }
    if (operator !in analyticsFilterOperators) {
        throw IllegalArgumentException(
            "$label.operator must be one of: " +
                analyticsFilterOperators.joinToString(", "),
        )
    }
    if (value.isBlank()) {
        throw IllegalArgumentException("$label.value is required")
    }
    return EventPropertyFilter(key, operator, value)
}

internal fun parseBoundedInt(
    args: JsonObject,
    fieldName: String,
    defaultValue: Int,
    min: Int,
    max: Int,
): Result<Int> = runCatching {
    val value = args[fieldName]?.let {
        it.jsonPrimitive.intOrNull
            ?: throw IllegalArgumentException("$fieldName must be an integer")
    } ?: defaultValue
    value.coerceIn(min, max)
}

private fun schemaStringArray(description: String): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("array"),
            "description" to JsonPrimitive(description),
            "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
        ),
    )

internal fun schemaAnalyticsFilters(): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("array"),
            "description" to JsonPrimitive(
                "Optional filters as objects with property, operator, value. " +
                    "Operators: ${analyticsFilterOperators.joinToString(", ")}",
            ),
            "items" to JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "property" to schemaEnum("Filter property", analyticsFilterProperties.toList()),
                            "operator" to schemaEnum("Filter operator", analyticsFilterOperators.toList()),
                            "value" to schemaString("Filter value"),
                        ),
                    ),
                    "required" to JsonArray(
                        listOf("property", "operator", "value").map(::JsonPrimitive),
                    ),
                ),
            ),
        ),
    )

internal fun schemaEventPropertyFilters(): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("array"),
            "description" to JsonPrimitive(
                "Optional event property filters as objects with key, operator, value. " +
                    "Operators: ${analyticsFilterOperators.joinToString(", ")}",
            ),
            "items" to JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "key" to schemaString("Event property key stored in analytics event props"),
                            "operator" to schemaEnum("Filter operator", analyticsFilterOperators.toList()),
                            "value" to schemaString("Filter value"),
                        ),
                    ),
                    "required" to JsonArray(
                        listOf("key", "operator", "value").map(::JsonPrimitive),
                    ),
                ),
            ),
        ),
    )
