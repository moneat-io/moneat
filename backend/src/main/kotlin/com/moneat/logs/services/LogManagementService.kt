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

package com.moneat.logs.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.isClickHouseError
import com.moneat.logs.models.CreateLogMetricRuleRequest
import com.moneat.logs.models.CreateLogPipelineRequest
import com.moneat.logs.models.CreateLogSavedViewRequest
import com.moneat.logs.models.LogAggregateResponse
import com.moneat.logs.models.LogMetricRuleResponse
import com.moneat.logs.models.LogPipelinePreviewEntry
import com.moneat.logs.models.LogPipelinePreviewRequest
import com.moneat.logs.models.LogPipelinePreviewResult
import com.moneat.logs.models.LogPipelineResponse
import com.moneat.logs.models.LogPipelineStep
import com.moneat.logs.models.LogSavedViewResponse
import com.moneat.logs.models.LogSavedViewState
import com.moneat.logs.models.QueuedLogEntry
import com.moneat.logs.models.UpdateLogMetricRuleRequest
import com.moneat.logs.models.UpdateLogPipelineRequest
import com.moneat.logs.models.UpdateLogSavedViewRequest
import com.moneat.shared.models.LogMetricRules
import com.moneat.shared.models.LogPipelines
import com.moneat.shared.models.LogSavedViews
import com.moneat.shared.services.CacheService
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private val logManagementLogger = KotlinLogging.logger {}
private val logManagementJson = Json { ignoreUnknownKeys = true }
private const val LOG_MANAGEMENT_CACHE_TTL_SECONDS = 60L
private const val DEFAULT_METRIC_GROUP_KEY = ""
private const val DEFAULT_METRIC_GROUP_VALUE = ""
private const val KEY_VALUE_PATTERN = """([A-Za-z0-9_.-]+)=([^\s]+)"""
private const val CLICKHOUSE_ERROR_PREVIEW_CHARS = 500
private const val POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505"

class LogManagementService {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val filterEvaluator = LogEntryFilterEvaluator()

    companion object {
        private val validMetricGroupFields = setOf("level", "service", "environment")

        fun normalizeMetricGroupBy(value: String?): String? {
            val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
            if (normalized !in validMetricGroupFields) {
                throw IllegalArgumentException(
                    "Metric group_by must be one of: ${validMetricGroupFields.joinToString()}"
                )
            }
            return normalized
        }
    }

    fun listPipelines(organizationId: Int): List<LogPipelineResponse> =
        transaction {
            LogPipelines
                .selectAll()
                .where { LogPipelines.organizationId eq organizationId }
                .orderBy(LogPipelines.priority)
                .map { toPipelineResponse(it) }
        }

    fun createPipeline(
        organizationId: Int,
        createdBy: Int,
        request: CreateLogPipelineRequest
    ): LogPipelineResponse {
        val name = request.name.trim()
        if (name.isBlank()) throw IllegalArgumentException("Pipeline name is required")
        ensurePipelineNameAvailable(organizationId, name)
        validatePipelineSteps(request.steps)
        val now = Clock.System.now()
        val id = mapDuplicateName("Pipeline name already exists") {
            transaction {
                LogPipelines.insert {
                    it[LogPipelines.organizationId] = organizationId
                    it[LogPipelines.name] = name
                    it[LogPipelines.description] = request.description.trim()
                    it[LogPipelines.stepsJson] = encodeSteps(request.steps)
                    it[LogPipelines.priority] = request.priority
                    it[LogPipelines.isActive] = request.isActive
                    it[LogPipelines.createdBy] = createdBy
                    it[LogPipelines.createdAt] = now
                    it[LogPipelines.updatedAt] = now
                }[LogPipelines.id]
            }
        }
        invalidatePipelineCache(organizationId)
        return getPipeline(organizationId, id)!!
    }

    fun updatePipeline(
        organizationId: Int,
        pipelineId: Int,
        request: UpdateLogPipelineRequest
    ): LogPipelineResponse? {
        request.name?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
            ensurePipelineNameAvailable(organizationId, name, pipelineId)
        }
        request.steps?.let { validatePipelineSteps(it) }
        val now = Clock.System.now()
        val updated = mapDuplicateName("Pipeline name already exists") {
            transaction {
                LogPipelines.update({
                    (LogPipelines.id eq pipelineId) and
                        (LogPipelines.organizationId eq organizationId)
                }) {
                    request.name?.let { name ->
                        val trimmed = name.trim()
                        if (trimmed.isBlank()) throw IllegalArgumentException("Pipeline name is required")
                        it[LogPipelines.name] = trimmed
                    }
                    request.description?.let { value ->
                        it[LogPipelines.description] = value.trim()
                    }
                    request.steps?.let { steps ->
                        it[LogPipelines.stepsJson] = encodeSteps(steps)
                    }
                    request.priority?.let { value ->
                        it[LogPipelines.priority] = value
                    }
                    request.isActive?.let { value ->
                        it[LogPipelines.isActive] = value
                    }
                    it[LogPipelines.updatedAt] = now
                }
            }
        }
        if (updated == 0) return null
        invalidatePipelineCache(organizationId)
        return getPipeline(organizationId, pipelineId)
    }

    fun deletePipeline(organizationId: Int, pipelineId: Int): Boolean {
        val deleted = transaction {
            LogPipelines.deleteWhere {
                (LogPipelines.id eq pipelineId) and
                    (LogPipelines.organizationId eq organizationId)
            }
        }
        if (deleted > 0) invalidatePipelineCache(organizationId)
        return deleted > 0
    }

    suspend fun getActivePipelinesCached(organizationId: Int): List<LogPipelineResponse> =
        CacheService.cached("logpipeline:active:$organizationId", LOG_MANAGEMENT_CACHE_TTL_SECONDS) {
            transaction {
                LogPipelines
                    .selectAll()
                    .where {
                        (LogPipelines.organizationId eq organizationId) and
                            (LogPipelines.isActive eq true)
                    }
                    .orderBy(LogPipelines.priority)
                    .map { toPipelineResponse(it) }
            }
        }

    fun previewPipeline(request: LogPipelinePreviewRequest): List<LogPipelinePreviewResult> {
        validatePipelineSteps(request.steps)
        return request.sampleLogs.map { sample ->
            val state = PipelineEntryState.fromPreview(sample)
            val transformed = applySteps(state, request.steps)
            LogPipelinePreviewResult(
                before = sample,
                after = transformed?.toPreview(),
                dropped = transformed == null
            )
        }
    }

    fun applyPipelines(
        entries: List<QueuedLogEntry>,
        pipelines: List<LogPipelineResponse>
    ): List<QueuedLogEntry> {
        if (entries.isEmpty() || pipelines.isEmpty()) return entries
        val steps = pipelines.flatMap { pipeline -> pipeline.steps }
        if (steps.isEmpty()) return entries

        return entries.mapNotNull { entry ->
            val state = PipelineEntryState.fromQueued(entry)
            applySteps(state, steps)?.toQueued(entry)
        }
    }

    fun listSavedViews(
        organizationId: Int,
        userId: Int
    ): List<LogSavedViewResponse> =
        transaction {
            LogSavedViews
                .selectAll()
                .where {
                    (LogSavedViews.organizationId eq organizationId) and
                        ((LogSavedViews.isShared eq true) or (LogSavedViews.createdBy eq userId))
                }
                .orderBy(LogSavedViews.name)
                .map { toSavedViewResponse(it) }
        }

    fun createSavedView(
        organizationId: Int,
        createdBy: Int,
        request: CreateLogSavedViewRequest
    ): LogSavedViewResponse {
        val name = request.name.trim()
        if (name.isBlank()) throw IllegalArgumentException("Saved view name is required")
        ensureSavedViewNameAvailable(organizationId, name)
        val now = Clock.System.now()
        val id = mapDuplicateName("Saved view name already exists") {
            transaction {
                LogSavedViews.insert {
                    it[LogSavedViews.organizationId] = organizationId
                    it[LogSavedViews.name] = name
                    it[LogSavedViews.viewStateJson] = encodeViewState(request.state)
                    it[LogSavedViews.isShared] = request.isShared
                    it[LogSavedViews.createdBy] = createdBy
                    it[LogSavedViews.createdAt] = now
                    it[LogSavedViews.updatedAt] = now
                }[LogSavedViews.id]
            }
        }
        return getSavedView(organizationId, id, createdBy)!!
    }

    fun updateSavedView(
        organizationId: Int,
        viewId: Int,
        userId: Int,
        request: UpdateLogSavedViewRequest
    ): LogSavedViewResponse? {
        request.name?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
            ensureSavedViewNameAvailable(organizationId, name, viewId)
        }
        val now = Clock.System.now()
        val updated = mapDuplicateName("Saved view name already exists") {
            transaction {
                LogSavedViews.update({
                    savedViewAccessCondition(organizationId, viewId, userId)
                }) {
                    request.name?.let { name ->
                        val trimmed = name.trim()
                        if (trimmed.isBlank()) throw IllegalArgumentException("Saved view name is required")
                        it[LogSavedViews.name] = trimmed
                    }
                    request.state?.let { state ->
                        it[LogSavedViews.viewStateJson] = encodeViewState(state)
                    }
                    request.isShared?.let { value ->
                        it[LogSavedViews.isShared] = value
                    }
                    it[LogSavedViews.updatedAt] = now
                }
            }
        }
        if (updated == 0) return null
        return getSavedView(organizationId, viewId, userId)
    }

    fun deleteSavedView(
        organizationId: Int,
        viewId: Int,
        userId: Int
    ): Boolean =
        transaction {
            LogSavedViews.deleteWhere {
                savedViewAccessCondition(organizationId, viewId, userId)
            }
        } > 0

    fun listMetricRules(organizationId: Int): List<LogMetricRuleResponse> =
        transaction {
            LogMetricRules
                .selectAll()
                .where { LogMetricRules.organizationId eq organizationId }
                .orderBy(LogMetricRules.name)
                .map { toMetricRuleResponse(it) }
        }

    fun createMetricRule(
        organizationId: Int,
        createdBy: Int,
        request: CreateLogMetricRuleRequest
    ): LogMetricRuleResponse {
        val name = request.name.trim()
        if (name.isBlank()) throw IllegalArgumentException("Metric rule name is required")
        ensureMetricRuleNameAvailable(organizationId, name)
        val groupBy = normalizeMetricGroupBy(request.groupBy)
        val now = Clock.System.now()
        val id = mapDuplicateName("Metric rule name already exists") {
            transaction {
                LogMetricRules.insert {
                    it[LogMetricRules.organizationId] = organizationId
                    it[LogMetricRules.name] = name
                    it[LogMetricRules.query] = request.query.trim()
                    it[LogMetricRules.levelsJson] = encodeLevels(request.levels)
                    it[LogMetricRules.groupBy] = groupBy
                    it[LogMetricRules.interval] = request.interval
                    it[LogMetricRules.isActive] = request.isActive
                    it[LogMetricRules.createdBy] = createdBy
                    it[LogMetricRules.createdAt] = now
                    it[LogMetricRules.updatedAt] = now
                }[LogMetricRules.id]
            }
        }
        return getMetricRule(organizationId, id)!!
    }

    fun updateMetricRule(
        organizationId: Int,
        ruleId: Int,
        request: UpdateLogMetricRuleRequest
    ): LogMetricRuleResponse? {
        request.name?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
            ensureMetricRuleNameAvailable(organizationId, name, ruleId)
        }
        val now = Clock.System.now()
        val updated = mapDuplicateName("Metric rule name already exists") {
            transaction {
                LogMetricRules.update({
                    (LogMetricRules.id eq ruleId) and
                        (LogMetricRules.organizationId eq organizationId)
                }) {
                    request.name?.let { name ->
                        val trimmed = name.trim()
                        if (trimmed.isBlank()) throw IllegalArgumentException("Metric rule name is required")
                        it[LogMetricRules.name] = trimmed
                    }
                    request.query?.let { value ->
                        it[LogMetricRules.query] = value.trim()
                    }
                    request.levels?.let { levels ->
                        it[LogMetricRules.levelsJson] = encodeLevels(levels)
                    }
                    request.groupBy?.let { value ->
                        it[LogMetricRules.groupBy] = normalizeMetricGroupBy(value)
                    }
                    request.interval?.let { value ->
                        it[LogMetricRules.interval] = value
                    }
                    request.isActive?.let { value ->
                        it[LogMetricRules.isActive] = value
                    }
                    it[LogMetricRules.updatedAt] = now
                }
            }
        }
        if (updated == 0) return null
        return getMetricRule(organizationId, ruleId)
    }

    fun deleteMetricRule(organizationId: Int, ruleId: Int): Boolean =
        transaction {
            LogMetricRules.deleteWhere {
                (LogMetricRules.id eq ruleId) and
                    (LogMetricRules.organizationId eq organizationId)
            }
        } > 0

    suspend fun recordMetricPoints(
        organizationId: Long,
        rule: LogMetricRuleResponse,
        aggregate: LogAggregateResponse
    ): Int {
        val rows = aggregate.buckets.flatMap { bucket ->
            if (bucket.groups.isEmpty()) {
                listOf(metricRow(organizationId, rule, bucket.timestamp, DEFAULT_METRIC_GROUP_VALUE, bucket.count))
            } else {
                bucket.groups.map { (groupValue, count) ->
                    metricRow(organizationId, rule, bucket.timestamp, groupValue, count)
                }
            }
        }
        if (rows.isEmpty()) return 0

        val sql = """
            INSERT INTO `$clickhouseDb`.log_metric_points
            (organization_id, metric_name, rule_id, timestamp, group_key, group_value, value, source_query)
            VALUES
            ${rows.joinToString(",\n")}
        """.trimIndent()

        val response = ClickHouseClient.execute(sql)
        val body = response.bodyAsText()
        if (response.isClickHouseError(body)) {
            logManagementLogger.warn {
                "Failed to record log metric points: ${body.take(CLICKHOUSE_ERROR_PREVIEW_CHARS)}"
            }
            throw IllegalStateException("Failed to record log metric points")
        }
        return rows.size
    }

    fun getMetricRule(
        organizationId: Int,
        ruleId: Int
    ): LogMetricRuleResponse? =
        transaction {
            LogMetricRules
                .selectAll()
                .where {
                    (LogMetricRules.id eq ruleId) and
                        (LogMetricRules.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.let { toMetricRuleResponse(it) }
        }

    private fun getPipeline(
        organizationId: Int,
        pipelineId: Int
    ): LogPipelineResponse? =
        transaction {
            LogPipelines
                .selectAll()
                .where {
                    (LogPipelines.id eq pipelineId) and
                        (LogPipelines.organizationId eq organizationId)
                }
                .firstOrNull()
                ?.let { toPipelineResponse(it) }
        }

    private fun getSavedView(
        organizationId: Int,
        viewId: Int,
        userId: Int
    ): LogSavedViewResponse? =
        transaction {
            LogSavedViews
                .selectAll()
                .where { savedViewAccessCondition(organizationId, viewId, userId) }
                .firstOrNull()
                ?.let { toSavedViewResponse(it) }
        }

    private fun applySteps(
        initial: PipelineEntryState,
        steps: List<LogPipelineStep>
    ): PipelineEntryState? {
        return steps.fold(initial as PipelineEntryState?) { current, step ->
            if (current == null || !step.enabled || !matchesCondition(step, current)) {
                current
            } else {
                applyStep(current, step)
            }
        }
    }

    private fun matchesCondition(
        step: LogPipelineStep,
        state: PipelineEntryState
    ): Boolean {
        return step.condition.isBlank() ||
            suspendRunCatching {
                filterEvaluator.matches(step.condition, state.toFilterMap())
            }.getOrElse { false }
    }

    private fun applyStep(
        state: PipelineEntryState,
        step: LogPipelineStep
    ): PipelineEntryState? {
        return when (step.type.lowercase()) {
            "drop" -> null
            "redact" -> applyRedaction(state, step)
            "remap" -> applyRemap(state, step)
            "enrich" -> applyEnrichment(state, step)
            "parse" -> applyParsing(state, step)
            else -> state
        }
    }

    private fun applyRedaction(
        state: PipelineEntryState,
        step: LogPipelineStep
    ): PipelineEntryState {
        if (step.pattern.isBlank()) return state
        val regex = suspendRunCatching { Regex(step.pattern) }.getOrNull() ?: return state
        val replacement = step.replacement.ifBlank { "[redacted]" }
        return when (step.sourceField.ifBlank { "message" }) {
            "message" -> state.copy(message = regex.replace(state.message, replacement))
            "body" -> state.copy(body = regex.replace(state.body, replacement))
            else -> state.copy(
                message = regex.replace(state.message, replacement),
                body = regex.replace(state.body, replacement)
            )
        }
    }

    private fun applyRemap(
        state: PipelineEntryState,
        step: LogPipelineStep
    ): PipelineEntryState {
        val sourceValue = state.get(step.sourceField)
        if (sourceValue.isNullOrBlank() || step.targetField.isBlank()) return state
        return state.set(step.targetField, sourceValue)
    }

    private fun applyEnrichment(
        state: PipelineEntryState,
        step: LogPipelineStep
    ): PipelineEntryState {
        val withTags = state.copy(tags = state.tags + step.tags)
        if (step.targetField.isBlank() || step.value.isBlank()) return withTags
        return withTags.set(step.targetField, step.value)
    }

    private fun applyParsing(
        state: PipelineEntryState,
        step: LogPipelineStep
    ): PipelineEntryState {
        val sourceValue = state.get(step.sourceField.ifBlank { "message" }) ?: return state
        val regex = suspendRunCatching {
            Regex(step.pattern.ifBlank { KEY_VALUE_PATTERN })
        }.getOrElse { e ->
            logManagementLogger.warn(e) { "Invalid log pipeline parse pattern" }
            return state
        }
        val extracted = regex.findAll(sourceValue)
            .mapNotNull { match ->
                val key = match.groups[1]?.value ?: return@mapNotNull null
                val value = match.groups[2]?.value ?: return@mapNotNull null
                key to value
            }
            .toMap()
        if (extracted.isEmpty()) return state
        return state.copy(tags = state.tags + extracted)
    }

    private fun metricRow(
        organizationId: Long,
        rule: LogMetricRuleResponse,
        timestamp: String,
        groupValue: String,
        value: Long
    ): String {
        val groupKey = rule.groupBy ?: DEFAULT_METRIC_GROUP_KEY
        return "($organizationId, '${escapeSql(rule.name)}', ${rule.id}, " +
            "parseDateTime64BestEffort('${escapeSql(timestamp)}'), " +
            "'${escapeSql(groupKey)}', '${escapeSql(groupValue)}', $value, '${escapeSql(rule.query)}')"
    }

    private fun toPipelineResponse(row: ResultRow): LogPipelineResponse =
        LogPipelineResponse(
            id = row[LogPipelines.id],
            name = row[LogPipelines.name],
            description = row[LogPipelines.description],
            steps = decodeSteps(row[LogPipelines.stepsJson]),
            priority = row[LogPipelines.priority],
            isActive = row[LogPipelines.isActive],
            createdAt = row[LogPipelines.createdAt].toString(),
            updatedAt = row[LogPipelines.updatedAt].toString()
        )

    private fun toSavedViewResponse(row: ResultRow): LogSavedViewResponse =
        LogSavedViewResponse(
            id = row[LogSavedViews.id],
            name = row[LogSavedViews.name],
            state = decodeViewState(row[LogSavedViews.viewStateJson]),
            isShared = row[LogSavedViews.isShared],
            createdAt = row[LogSavedViews.createdAt].toString(),
            updatedAt = row[LogSavedViews.updatedAt].toString()
        )

    private fun toMetricRuleResponse(row: ResultRow): LogMetricRuleResponse =
        LogMetricRuleResponse(
            id = row[LogMetricRules.id],
            name = row[LogMetricRules.name],
            query = row[LogMetricRules.query],
            levels = decodeLevels(row[LogMetricRules.levelsJson]),
            groupBy = row[LogMetricRules.groupBy],
            interval = row[LogMetricRules.interval],
            isActive = row[LogMetricRules.isActive],
            createdAt = row[LogMetricRules.createdAt].toString(),
            updatedAt = row[LogMetricRules.updatedAt].toString()
        )

    private fun validatePipelineSteps(steps: List<LogPipelineStep>) {
        steps.forEach { step ->
            if (step.enabled && step.type.trim().lowercase() == "drop" && step.condition.isBlank()) {
                throw IllegalArgumentException("Drop pipeline steps require a condition")
            }
        }
    }

    private fun ensurePipelineNameAvailable(
        organizationId: Int,
        name: String,
        excludeId: Int? = null
    ) {
        val duplicate = transaction {
            LogPipelines
                .selectAll()
                .where {
                    (LogPipelines.organizationId eq organizationId) and
                        (LogPipelines.name eq name)
                }
                .firstOrNull()
                ?.let { excludeId == null || it[LogPipelines.id] != excludeId }
                ?: false
        }
        if (duplicate) throw IllegalArgumentException("Pipeline name already exists")
    }

    private fun ensureSavedViewNameAvailable(
        organizationId: Int,
        name: String,
        excludeId: Int? = null
    ) {
        val duplicate = transaction {
            LogSavedViews
                .selectAll()
                .where {
                    (LogSavedViews.organizationId eq organizationId) and
                        (LogSavedViews.name eq name)
                }
                .firstOrNull()
                ?.let { excludeId == null || it[LogSavedViews.id] != excludeId }
                ?: false
        }
        if (duplicate) throw IllegalArgumentException("Saved view name already exists")
    }

    private fun ensureMetricRuleNameAvailable(
        organizationId: Int,
        name: String,
        excludeId: Int? = null
    ) {
        val duplicate = transaction {
            LogMetricRules
                .selectAll()
                .where {
                    (LogMetricRules.organizationId eq organizationId) and
                        (LogMetricRules.name eq name)
                }
                .firstOrNull()
                ?.let { excludeId == null || it[LogMetricRules.id] != excludeId }
                ?: false
        }
        if (duplicate) throw IllegalArgumentException("Metric rule name already exists")
    }

    private fun savedViewAccessCondition(
        organizationId: Int,
        viewId: Int,
        userId: Int
    ): Op<Boolean> =
        (LogSavedViews.id eq viewId) and
            (LogSavedViews.organizationId eq organizationId) and
            ((LogSavedViews.isShared eq true) or (LogSavedViews.createdBy eq userId))

    private fun <T> mapDuplicateName(message: String, block: () -> T): T =
        try {
            block()
        } catch (error: ExposedSQLException) {
            if (error.sqlState == POSTGRES_UNIQUE_VIOLATION_SQLSTATE) {
                throw IllegalArgumentException(message)
            }
            throw error
        }

    private fun encodeSteps(steps: List<LogPipelineStep>): String =
        logManagementJson.encodeToString(ListSerializer(LogPipelineStep.serializer()), steps)

    private fun decodeSteps(value: String): List<LogPipelineStep> =
        suspendRunCatching {
            logManagementJson.decodeFromString(ListSerializer(LogPipelineStep.serializer()), value)
        }.getOrElse { emptyList() }

    private fun encodeViewState(state: LogSavedViewState): String =
        logManagementJson.encodeToString(LogSavedViewState.serializer(), state)

    private fun decodeViewState(value: String): LogSavedViewState =
        suspendRunCatching {
            logManagementJson.decodeFromString(LogSavedViewState.serializer(), value)
        }.getOrElse { LogSavedViewState() }

    private fun encodeLevels(levels: List<String>): String =
        logManagementJson.encodeToString(ListSerializer(String.serializer()), levels)

    private fun decodeLevels(value: String): List<String> =
        suspendRunCatching {
            logManagementJson.decodeFromString(ListSerializer(String.serializer()), value)
        }.getOrElse { emptyList() }

    private fun invalidatePipelineCache(organizationId: Int) {
        CacheService.invalidate("logpipeline:active:$organizationId")
    }
}

private data class PipelineEntryState(
    val level: String,
    val message: String,
    val body: String,
    val service: String,
    val environment: String,
    val host: String,
    val source: String,
    val containerName: String,
    val containerId: String,
    val containerImage: String,
    val traceId: String,
    val spanId: String,
    val tags: Map<String, String>,
    val resourceAttributes: Map<String, String>
) {
    fun get(field: String): String? {
        return when (field) {
            "level" -> level
            "message" -> message
            "body" -> body
            "service" -> service
            "environment" -> environment
            "host" -> host
            "source" -> source
            "container_name" -> containerName
            "container_id" -> containerId
            "container_image" -> containerImage
            "trace_id" -> traceId
            "span_id" -> spanId
            else -> tags[field]
                ?: resourceAttributes[field]
                ?: field.removePrefix("tags.").takeIf { it != field }?.let { tags[it] }
                ?: field.removePrefix("resource_attributes.").takeIf { it != field }?.let { resourceAttributes[it] }
        }
    }

    fun set(
        field: String,
        value: String
    ): PipelineEntryState {
        return when (field) {
            "level" -> copy(level = value)
            "message" -> copy(message = value)
            "body" -> copy(body = value)
            "service" -> copy(service = value)
            "environment" -> copy(environment = value)
            "host" -> copy(host = value)
            "container_name" -> copy(containerName = value)
            "container_id" -> copy(containerId = value)
            "container_image" -> copy(containerImage = value)
            "trace_id" -> copy(traceId = value)
            "span_id" -> copy(spanId = value)
            else -> {
                val tagKey = field.removePrefix("tags.").takeIf { it != field } ?: field
                copy(tags = tags + (tagKey to value))
            }
        }
    }

    fun toFilterMap(): Map<String, String> =
        mapOf(
            "level" to level,
            "message" to message,
            "body" to body,
            "service" to service,
            "environment" to environment,
            "host" to host,
            "source" to source,
            "container_name" to containerName,
            "container_id" to containerId,
            "container_image" to containerImage,
            "trace_id" to traceId,
            "span_id" to spanId
        ) + tags + resourceAttributes

    fun toQueued(original: QueuedLogEntry): QueuedLogEntry =
        original.copy(
            level = level,
            message = message,
            body = body,
            service = service,
            environment = environment,
            host = host,
            containerName = containerName,
            containerId = containerId,
            containerImage = containerImage,
            traceId = traceId,
            spanId = spanId,
            tags = tags,
            resourceAttributes = resourceAttributes
        )

    fun toPreview(): LogPipelinePreviewEntry =
        LogPipelinePreviewEntry(
            level = level,
            message = message,
            body = body,
            service = service,
            environment = environment,
            host = host,
            tags = tags,
            resourceAttributes = resourceAttributes
        )

    companion object {
        fun fromQueued(entry: QueuedLogEntry): PipelineEntryState =
            PipelineEntryState(
                level = entry.level,
                message = entry.message,
                body = entry.body,
                service = entry.service,
                environment = entry.environment,
                host = entry.host,
                source = entry.source,
                containerName = entry.containerName,
                containerId = entry.containerId,
                containerImage = entry.containerImage,
                traceId = entry.traceId,
                spanId = entry.spanId,
                tags = entry.tags,
                resourceAttributes = entry.resourceAttributes
            )

        fun fromPreview(entry: LogPipelinePreviewEntry): PipelineEntryState =
            PipelineEntryState(
                level = entry.level,
                message = entry.message,
                body = entry.body,
                service = entry.service,
                environment = entry.environment,
                host = entry.host,
                source = "",
                containerName = "",
                containerId = "",
                containerImage = "",
                traceId = "",
                spanId = "",
                tags = entry.tags,
                resourceAttributes = entry.resourceAttributes
            )
    }
}
