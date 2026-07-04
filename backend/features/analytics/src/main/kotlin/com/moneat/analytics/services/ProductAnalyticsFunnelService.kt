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

package com.moneat.analytics.services

import com.moneat.analytics.models.AnalyticsFilter
import com.moneat.analytics.models.EventPropertyFilter
import com.moneat.analytics.models.FunnelResponse
import com.moneat.analytics.models.ProductAnalyticsFunnels
import com.moneat.analytics.models.SavedProductFunnel
import com.moneat.analytics.models.SavedProductFunnelCreateRequest
import com.moneat.analytics.models.SavedProductFunnelDeleteResponse
import com.moneat.analytics.models.SavedProductFunnelListResponse
import com.moneat.analytics.models.SavedProductFunnelUpdateRequest
import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Projects
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val FEATURE_FLAG_FUNNEL_WINDOW_SECONDS = 86400
private const val MAX_FUNNEL_STEPS = 12
private const val PERCENTAGE_MULTIPLIER = 100
private const val PERCENTAGE_ROUNDING_SCALE = 10.0
private val productAnalyticsJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

class ProductAnalyticsFunnelService(
    private val analyticsService: AnalyticsService = AnalyticsService(),
) {
    fun listFunnels(
        organizationId: Int,
        projectId: Long,
    ): SavedProductFunnelListResponse = transaction {
        ensureProjectInOrganization(organizationId, projectId) ?: return@transaction SavedProductFunnelListResponse(
            emptyList(),
        )
        SavedProductFunnelListResponse(
            ProductAnalyticsFunnels
                .join(
                    Projects,
                    JoinType.INNER,
                    ProductAnalyticsFunnels.projectId,
                    Projects.id,
                )
                .selectAll()
                .where {
                    (ProductAnalyticsFunnels.organizationId eq organizationId) and
                        (ProductAnalyticsFunnels.projectId eq projectId) and
                        ProductAnalyticsFunnels.archivedAt.isNull()
                }
                .orderBy(ProductAnalyticsFunnels.updatedAt to SortOrder.DESC)
                .map(::mapFunnelRow),
        )
    }

    fun getFunnel(
        organizationId: Int,
        funnelResourceId: Uuid,
    ): SavedProductFunnel? = transaction {
        loadFunnel(organizationId, funnelResourceId)
    }

    fun createFunnel(
        organizationId: Int,
        actorUserId: Int,
        request: SavedProductFunnelCreateRequest,
    ): SavedProductFunnel {
        require(request.name.isNotBlank()) { "name is required" }
        validateFunnelDefinition(request.steps, request.groupBy)
        return transaction {
            ensureProjectInOrganization(organizationId, request.projectId)
                ?: throw IllegalArgumentException("Project not found")
            val now = Clock.System.now()
            val resourceId = ProductAnalyticsFunnels.insert {
                it[ProductAnalyticsFunnels.organizationId] = organizationId
                it[ProductAnalyticsFunnels.projectId] = request.projectId
                it[ProductAnalyticsFunnels.name] = request.name.trim()
                it[ProductAnalyticsFunnels.description] = request.description?.trim()?.takeIf(String::isNotBlank)
                it[ProductAnalyticsFunnels.stepsJson] = encodeSteps(request.steps)
                it[ProductAnalyticsFunnels.filtersJson] = encodeFilters(request.filters)
                it[ProductAnalyticsFunnels.propFiltersJson] = encodePropFilters(request.propFilters)
                it[ProductAnalyticsFunnels.groupBy] = request.groupBy
                it[ProductAnalyticsFunnels.sourceFilter] = request.source?.trim()?.takeIf(String::isNotBlank)
                it[ProductAnalyticsFunnels.createdBy] = actorUserId
                it[ProductAnalyticsFunnels.createdAt] = now
                it[ProductAnalyticsFunnels.updatedAt] = now
            }[ProductAnalyticsFunnels.resourceId]
            loadFunnel(organizationId, resourceId) ?: error("Created funnel could not be loaded")
        }
    }

    fun updateFunnel(
        organizationId: Int,
        funnelResourceId: Uuid,
        request: SavedProductFunnelUpdateRequest,
    ): SavedProductFunnel? {
        request.steps?.let { steps -> validateFunnelDefinition(steps, request.groupBy ?: "session_id") }
        request.groupBy?.let { groupBy -> validateGroupBy(groupBy) }
        return transaction {
            val current = loadFunnelRow(organizationId, funnelResourceId) ?: return@transaction null
            request.steps?.let { steps ->
                validateFunnelDefinition(
                    steps,
                    request.groupBy ?: current[ProductAnalyticsFunnels.groupBy],
                )
            }
            val now = Clock.System.now()
            ProductAnalyticsFunnels.update({
                (ProductAnalyticsFunnels.id eq current[ProductAnalyticsFunnels.id]) and
                    ProductAnalyticsFunnels.archivedAt.isNull()
            }) {
                request.name?.trim()?.takeIf(String::isNotBlank)?.let { value ->
                    it[ProductAnalyticsFunnels.name] = value
                }
                request.description?.trim()?.let { value ->
                    it[ProductAnalyticsFunnels.description] = value.takeIf(String::isNotBlank)
                }
                request.steps?.let { value -> it[ProductAnalyticsFunnels.stepsJson] = encodeSteps(value) }
                request.filters?.let { value -> it[ProductAnalyticsFunnels.filtersJson] = encodeFilters(value) }
                request.propFilters?.let { value ->
                    it[ProductAnalyticsFunnels.propFiltersJson] = encodePropFilters(value)
                }
                request.groupBy?.let { value -> it[ProductAnalyticsFunnels.groupBy] = value }
                request.source?.trim()?.let { value ->
                    it[ProductAnalyticsFunnels.sourceFilter] = value.takeIf(String::isNotBlank)
                }
                it[ProductAnalyticsFunnels.updatedAt] = now
            }
            loadFunnel(organizationId, funnelResourceId)
        }
    }

    fun deleteFunnel(
        organizationId: Int,
        funnelResourceId: Uuid,
    ): SavedProductFunnelDeleteResponse = transaction {
        val current = loadFunnelRow(organizationId, funnelResourceId)
            ?: return@transaction SavedProductFunnelDeleteResponse(funnelResourceId.toString(), false)
        val updated = ProductAnalyticsFunnels.update({
            (ProductAnalyticsFunnels.id eq current[ProductAnalyticsFunnels.id]) and
                ProductAnalyticsFunnels.archivedAt.isNull()
        }) {
            it[ProductAnalyticsFunnels.archivedAt] = Clock.System.now()
            it[ProductAnalyticsFunnels.updatedAt] = Clock.System.now()
        }
        SavedProductFunnelDeleteResponse(funnelResourceId.toString(), updated > 0)
    }

    suspend fun runFunnel(
        organizationId: Int,
        funnelResourceId: Uuid,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): SavedProductFunnelRunResult? {
        val funnel = getFunnel(organizationId, funnelResourceId) ?: return null
        val projectId = transaction { resolveInternalProjectId(organizationId, funnel.projectId) } ?: return null
        val result = analyticsService.getFunnel(
            projectId = projectId,
            query = AnalyticsFunnelQuery(
                dateFrom = dateFrom,
                dateTo = dateTo,
                steps = funnel.steps,
                groupBy = funnel.groupBy,
                source = funnel.source,
                filters = funnel.filters,
                propFilters = funnel.propFilters,
            ),
        )
        return SavedProductFunnelRunResult(funnel, dateFrom.toString(), dateTo.toString(), result)
    }

    suspend fun compareFunnelByFeatureFlag(
        organizationId: Int,
        definition: FeatureFlagFunnelComparisonDefinition,
    ): FeatureFlagFunnelComparisonResponse {
        transaction {
            ensureProjectInOrganization(organizationId, definition.projectId)
                ?: throw IllegalArgumentException("Project not found")
        }
        validateFunnelDefinition(definition.steps, definition.groupBy)
        val sql = featureFlagComparisonSql(organizationId, definition)
        val rows = parseJsonRows(ClickHouseClient.executeWithFormat(sql, "JSONEachRow"))
        val variants = rows
            .groupBy { it.stringValue("variant_key") }
            .filterKeys(String::isNotBlank)
            .map { (variantKey, variantRows) ->
                val levelCounts = variantRows.associate {
                    it.longValue("level").toInt() to it.longValue("cnt")
                }
                val firstStepVisitors = levelCounts.filter { it.key >= 1 }.values.sum()
                val steps = definition.steps.mapIndexed { index, name ->
                    val stepNumber = index + 1
                    val visitors = levelCounts.filter { it.key >= stepNumber }.values.sum()
                    val previous = if (stepNumber == 1) {
                        visitors
                    } else {
                        levelCounts.filter { it.key >= stepNumber - 1 }.values.sum()
                    }
                    FeatureFlagFunnelStep(
                        name = name,
                        visitors = visitors,
                        dropoff = if (stepNumber == 1) {
                            0.0
                        } else {
                            percentage(previous - visitors, previous)
                        },
                        conversionRate = percentage(visitors, firstStepVisitors),
                    )
                }
                FeatureFlagFunnelVariantResult(
                    variantKey = variantKey,
                    evaluations = variantRows.maxOfOrNull { it.longValue("evaluations") } ?: 0,
                    uniqueTargets = variantRows.maxOfOrNull { it.longValue("unique_targets") } ?: 0,
                    steps = steps,
                    overallConversion = percentage(steps.lastOrNull()?.visitors ?: 0, firstStepVisitors),
                )
            }
            .sortedByDescending { it.steps.firstOrNull()?.visitors ?: 0 }

        return FeatureFlagFunnelComparisonResponse(
            dateFrom = definition.dateFrom.toString(),
            dateTo = definition.dateTo.toString(),
            flagKey = definition.flagKey,
            environment = definition.environment,
            groupBy = definition.groupBy,
            source = definition.source,
            filters = definition.filters,
            propFilters = definition.propFilters,
            variants = variants,
            identityCaveat = "Feature flag targeting_key must match the selected product analytics group_by identity.",
        )
    }

    private fun featureFlagComparisonSql(
        organizationId: Int,
        definition: FeatureFlagFunnelComparisonDefinition,
    ): String {
        val identityColumn = resolveGroupByColumn(definition.groupBy)
        val escapedFlagKey = AnalyticsIngestionWorker.escapeCH(definition.flagKey)
        val environmentClause = definition.environment?.let {
            "AND environment = '${AnalyticsIngestionWorker.escapeCH(it)}'"
        }.orEmpty()
        val sourceClause = definition.source?.let {
            "AND e.source = '${AnalyticsIngestionWorker.escapeCH(it)}'"
        }.orEmpty()
        val filterClause = analyticsFilterClause(definition.filters)
        val propFilterClause = eventPropertyFilterClause(definition.propFilters)
        val eventPredicates = definition.steps.joinToString(", ") {
            "e.event_name = '${AnalyticsIngestionWorker.escapeCH(it)}'"
        }
        val from = definition.dateFrom.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val to = definition.dateTo.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val orgPredicate = if (organizationId < 0) {
            "toInt32(organization_id) = $organizationId"
        } else {
            "organization_id = $organizationId"
        }

        return """
            WITH flag_assignments AS (
                SELECT
                    targeting_key,
                    argMax(variant_key, event_time) AS variant_key,
                    count() AS evaluations
                FROM feature_flag_evaluations
                WHERE $orgPredicate
                  AND flag_key = '$escapedFlagKey'
                  $environmentClause
                  AND event_time >= '$from'
                  AND event_time < '$to'
                  AND targeting_key != ''
                GROUP BY targeting_key
            ),
            assignment_counts AS (
                SELECT
                    variant_key,
                    sum(evaluations) AS evaluations,
                    uniqExact(targeting_key) AS unique_targets
                FROM flag_assignments
                GROUP BY variant_key
            ),
            funnel_levels AS (
                SELECT
                    f.variant_key,
                    windowFunnel($FEATURE_FLAG_FUNNEL_WINDOW_SECONDS)(toDateTime(e.timestamp), $eventPredicates)
                        AS level
                FROM analytics_events AS e
                INNER JOIN flag_assignments AS f ON f.targeting_key = e.$identityColumn
                WHERE e.project_id = toUInt64(${definition.projectId})
                  AND e.timestamp >= '$from'
                  AND e.timestamp < '$to'
                  $sourceClause
                  $filterClause
                  $propFilterClause
                  AND e.$identityColumn != ''
                GROUP BY f.variant_key, e.$identityColumn
            )
            SELECT
                levels.variant_key AS variant_key,
                levels.level AS level,
                count() AS cnt,
                any(counts.evaluations) AS evaluations,
                any(counts.unique_targets) AS unique_targets
            FROM funnel_levels AS levels
            INNER JOIN assignment_counts AS counts ON counts.variant_key = levels.variant_key
            WHERE levels.level > 0
            GROUP BY levels.variant_key, levels.level
            ORDER BY levels.variant_key, levels.level
            FORMAT JSONEachRow
        """.trimIndent()
    }

    private fun analyticsFilterClause(filters: List<AnalyticsFilter>): String {
        val parts = filters.mapNotNull { filter ->
            val column = resolveFilterColumn(filter.property) ?: return@mapNotNull null
            val value = AnalyticsIngestionWorker.escapeCH(filter.value)
            when (filter.operator) {
                "is" -> "AND $column = '$value'"
                "is_not" -> "AND $column != '$value'"
                "contains" -> "AND $column LIKE '%$value%'"
                "not_contains" -> "AND $column NOT LIKE '%$value%'"
                else -> null
            }
        }
        return parts.joinToString("\n")
    }

    private fun eventPropertyFilterClause(filters: List<EventPropertyFilter>): String {
        val parts = filters.map { filter ->
            val key = AnalyticsIngestionWorker.escapeCH(filter.key)
            val value = AnalyticsIngestionWorker.escapeCH(filter.value)
            val contains = "mapContains(e.props, '$key')"
            val property = "e.props['$key']"
            when (filter.operator) {
                "is" -> "AND $contains AND $property = '$value'"
                "is_not" -> "AND $contains AND $property != '$value'"
                "contains" -> "AND $contains AND $property LIKE '%$value%'"
                "not_contains" -> "AND $contains AND $property NOT LIKE '%$value%'"
                else -> throw IllegalArgumentException("Unsupported event property filter operator: ${filter.operator}")
            }
        }
        return parts.joinToString("\n")
    }

    private fun resolveFilterColumn(property: String): String? =
        when (property) {
            "page", "pathname" -> "e.pathname"
            "source", "referrer_source" -> "e.referrer_source"
            "country", "country_code" -> "e.country_code"
            "browser" -> "e.browser"
            "os" -> "e.os"
            "device", "device_type" -> "e.device_type"
            "utm_source" -> "e.utm_source"
            "utm_medium" -> "e.utm_medium"
            "utm_campaign" -> "e.utm_campaign"
            "utm_term" -> "e.utm_term"
            "utm_content" -> "e.utm_content"
            "event", "event_name" -> "e.event_name"
            else -> null
        }

    private fun resolveGroupByColumn(groupBy: String): String =
        when (groupBy) {
            "user_id" -> "user_id"
            else -> "session_id"
        }

    private fun validateFunnelDefinition(
        steps: List<String>,
        groupBy: String,
    ) {
        require(steps.size >= 2) { "steps must include at least 2 event names" }
        require(steps.size <= MAX_FUNNEL_STEPS) { "steps cannot include more than $MAX_FUNNEL_STEPS event names" }
        require(steps.none(String::isBlank)) { "steps must not contain blank values" }
        validateGroupBy(groupBy)
    }

    private fun validateGroupBy(groupBy: String) {
        require(groupBy == "session_id" || groupBy == "user_id") {
            "group_by must be one of: session_id, user_id"
        }
    }

    private fun loadFunnel(
        organizationId: Int,
        funnelResourceId: Uuid,
    ): SavedProductFunnel? =
        loadFunnelRow(organizationId, funnelResourceId)?.let(::mapFunnelRow)

    private fun loadFunnelRow(
        organizationId: Int,
        funnelResourceId: Uuid,
    ) = ProductAnalyticsFunnels
        .join(
            Projects,
            JoinType.INNER,
            ProductAnalyticsFunnels.projectId,
            Projects.id,
        )
        .selectAll()
        .where {
            (ProductAnalyticsFunnels.organizationId eq organizationId) and
                (ProductAnalyticsFunnels.resourceId eq funnelResourceId) and
                ProductAnalyticsFunnels.archivedAt.isNull()
        }
        .firstOrNull()

    private fun ensureProjectInOrganization(
        organizationId: Int,
        projectId: Long,
    ): String? =
        Projects
            .select(Projects.resource_id)
            .where {
                (Projects.id eq projectId) and
                    (Projects.organization_id eq organizationId)
            }
            .firstOrNull()
            ?.get(Projects.resource_id)
            ?.toString()

    private fun resolveInternalProjectId(
        organizationId: Int,
        projectResourceId: String,
    ): Long? =
        Projects
            .select(Projects.id)
            .where {
                (Projects.resource_id eq Uuid.parse(projectResourceId)) and
                    (Projects.organization_id eq organizationId)
            }
            .firstOrNull()
            ?.get(Projects.id)

    private fun mapFunnelRow(row: org.jetbrains.exposed.v1.core.ResultRow): SavedProductFunnel =
        SavedProductFunnel(
            id = row[ProductAnalyticsFunnels.resourceId].toString(),
            projectId = row[Projects.resource_id].toString(),
            name = row[ProductAnalyticsFunnels.name],
            description = row[ProductAnalyticsFunnels.description],
            steps = decodeSteps(row[ProductAnalyticsFunnels.stepsJson]),
            filters = decodeFilters(row[ProductAnalyticsFunnels.filtersJson]),
            propFilters = decodePropFilters(row[ProductAnalyticsFunnels.propFiltersJson]),
            groupBy = row[ProductAnalyticsFunnels.groupBy],
            source = row[ProductAnalyticsFunnels.sourceFilter],
            createdAt = row[ProductAnalyticsFunnels.createdAt].toString(),
            updatedAt = row[ProductAnalyticsFunnels.updatedAt].toString(),
        )

    private fun encodeSteps(steps: List<String>): String =
        productAnalyticsJson.encodeToString(ListSerializer(String.serializer()), steps.map(String::trim))

    private fun decodeSteps(value: String): List<String> =
        productAnalyticsJson.decodeFromString(ListSerializer(String.serializer()), value)

    private fun encodeFilters(filters: List<AnalyticsFilter>): String =
        productAnalyticsJson.encodeToString(ListSerializer(AnalyticsFilter.serializer()), filters)

    private fun decodeFilters(value: String): List<AnalyticsFilter> =
        productAnalyticsJson.decodeFromString(ListSerializer(AnalyticsFilter.serializer()), value)

    private fun encodePropFilters(filters: List<EventPropertyFilter>): String =
        productAnalyticsJson.encodeToString(ListSerializer(EventPropertyFilter.serializer()), filters)

    private fun decodePropFilters(value: String): List<EventPropertyFilter> =
        productAnalyticsJson.decodeFromString(ListSerializer(EventPropertyFilter.serializer()), value)

    private fun parseJsonRows(body: String): List<JsonObject> {
        if (body.isBlank()) return emptyList()
        return body.trim().lines().mapNotNull { line ->
            try {
                productAnalyticsJson.parseToJsonElement(line).jsonObject
            } catch (_: SerializationException) {
                null
            }
        }
    }

    private fun JsonObject.longValue(key: String): Long =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0

    private fun JsonObject.stringValue(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun percentage(
        numerator: Long,
        denominator: Long,
    ): Double =
        if (denominator > 0) {
            (numerator.toDouble() / denominator * PERCENTAGE_MULTIPLIER * PERCENTAGE_ROUNDING_SCALE)
                .roundToInt() / PERCENTAGE_ROUNDING_SCALE
        } else {
            0.0
        }
}

@kotlinx.serialization.Serializable
data class SavedProductFunnelRunResult(
    val funnel: SavedProductFunnel,
    val dateFrom: String,
    val dateTo: String,
    val result: FunnelResponse,
)

data class FeatureFlagFunnelComparisonDefinition(
    val projectId: Long,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val steps: List<String>,
    val groupBy: String,
    val source: String?,
    val filters: List<AnalyticsFilter>,
    val propFilters: List<EventPropertyFilter>,
    val flagKey: String,
    val environment: String?,
)

@kotlinx.serialization.Serializable
data class FeatureFlagFunnelComparisonResponse(
    val dateFrom: String,
    val dateTo: String,
    val flagKey: String,
    val environment: String? = null,
    val groupBy: String,
    val source: String? = null,
    val filters: List<AnalyticsFilter>,
    val propFilters: List<EventPropertyFilter>,
    val variants: List<FeatureFlagFunnelVariantResult>,
    val identityCaveat: String,
)

@kotlinx.serialization.Serializable
data class FeatureFlagFunnelVariantResult(
    val variantKey: String,
    val evaluations: Long,
    val uniqueTargets: Long,
    val steps: List<FeatureFlagFunnelStep>,
    val overallConversion: Double,
)

@kotlinx.serialization.Serializable
data class FeatureFlagFunnelStep(
    val name: String,
    val visitors: Long,
    val dropoff: Double,
    val conversionRate: Double,
)
