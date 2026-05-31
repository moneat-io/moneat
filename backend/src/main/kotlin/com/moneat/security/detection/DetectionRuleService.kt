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

package com.moneat.security.detection

import com.moneat.config.ClickHouseClient
import com.moneat.security.signals.SignalSeverity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private val ruleJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private const val PREVIEW_SAMPLE_LIMIT = 25

/**
 * CRUD + preview for detection rules. Every mutation runs the rule through [RuleQueryCompiler] before
 * persisting, so a rule that cannot be compiled into a safe, org-scoped query can never be saved or
 * enabled (it would otherwise be a no-op the scheduler skips, but failing fast surfaces the DSL error
 * to the author). All reads/writes are org-scoped by [organizationId]; the compiler — not the row —
 * supplies the org the query targets.
 */
class DetectionRuleService(
    private val compiler: RuleQueryCompiler = RuleQueryCompiler({ ClickHouseClient.getDatabase() }),
    private val runner: DetectionQueryRunner = DetectionQueryRunner(),
    private val baseline: DetectionBaselineStore = PostgresDetectionBaselineStore(),
) {

    fun list(organizationId: Int): DetectionRuleListResponse = transaction {
        val rows = DetectionRules
            .selectAll()
            .where { DetectionRules.organizationId eq organizationId }
            .orderBy(DetectionRules.updatedAt, SortOrder.DESC)
            .map { it.toResponse() }
        DetectionRuleListResponse(rules = rows, totalCount = rows.size.toLong())
    }

    fun get(organizationId: Int, ruleId: Int): DetectionRuleResponse? = transaction {
        DetectionRules
            .selectAll()
            .where { (DetectionRules.id eq ruleId) and (DetectionRules.organizationId eq organizationId) }
            .firstOrNull()
            ?.toResponse()
    }

    /** @throws DetectionCompileException (a DSL-level [IllegalArgumentException]) if the rule is invalid. */
    fun create(organizationId: Int, request: CreateDetectionRuleRequest): DetectionRuleResponse {
        val type = parseType(request.type)
        val severity = SignalSeverity.fromWire(request.severity)
        // Validate by compiling against the owning org — rejects bad filters/columns/windows up front.
        compiler.compile(organizationId, request.toCompilerInput(type))
        val now = Clock.System.now()
        val id = transaction {
            DetectionRules.insertAndGetId {
                it[DetectionRules.organizationId] = organizationId
                it[name] = request.name
                it[description] = request.description
                it[ruleSource] = request.source
                it[filter] = request.filter
                it[groupBy] = ruleJson.encodeToString(request.groupBy)
                it[windowSeconds] = request.windowSeconds
                it[DetectionRules.type] = type.wire
                it[thresholdCount] = request.thresholdCount
                it[DetectionRules.severity] = severity.wire
                it[signalTitle] = request.signalTitle
                it[signalMessage] = request.signalMessage
                it[suppressions] = ruleJson.encodeToString(request.suppressions)
                it[enabled] = request.enabled
                it[tags] = ruleJson.encodeToString(request.tags)
                it[createdAt] = now
                it[updatedAt] = now
            }.value
        }
        return requireNotNull(get(organizationId, id)) { "Detection rule $id vanished after insert" }
    }

    /** @throws DetectionCompileException if the resulting rule would not compile. */
    fun update(organizationId: Int, ruleId: Int, request: UpdateDetectionRuleRequest): DetectionRuleResponse? {
        val current = get(organizationId, ruleId) ?: return null
        val merged = current.mergedWith(request)
        val type = parseType(merged.type)
        val severity = SignalSeverity.fromWire(merged.severity)
        compiler.compile(organizationId, merged.toCompilerInput(type))
        val now = Clock.System.now()
        transaction {
            DetectionRules.update({
                (DetectionRules.id eq ruleId) and (DetectionRules.organizationId eq organizationId)
            }) {
                it[name] = merged.name
                it[description] = merged.description
                it[ruleSource] = merged.source
                it[filter] = merged.filter
                it[groupBy] = ruleJson.encodeToString(merged.groupBy)
                it[windowSeconds] = merged.windowSeconds
                it[DetectionRules.type] = type.wire
                it[thresholdCount] = merged.thresholdCount
                it[DetectionRules.severity] = severity.wire
                it[signalTitle] = merged.signalTitle
                it[signalMessage] = merged.signalMessage
                it[suppressions] = ruleJson.encodeToString(merged.suppressions)
                it[enabled] = merged.enabled
                it[tags] = ruleJson.encodeToString(merged.tags)
                it[updatedAt] = now
            }
        }
        return get(organizationId, ruleId)
    }

    fun delete(organizationId: Int, ruleId: Int): Boolean = transaction {
        DetectionRules.deleteWhere {
            (DetectionRules.id eq ruleId) and (DetectionRules.organizationId eq organizationId)
        } > 0
    }

    /** All enabled rules across orgs, as records for the scheduler. The compiler still injects each
     * rule's owning org, so loading them together never crosses tenant boundaries. Ordered by id so the
     * scheduler's per-org cap defers a *stable* remainder it can rotate across ticks (without a
     * deterministic order, rules past the cap could be starved indefinitely). */
    fun loadEnabledRules(): List<DetectionRuleRecord> = transaction {
        DetectionRules
            .selectAll()
            .where { DetectionRules.enabled eq true }
            .orderBy(DetectionRules.id, SortOrder.ASC)
            .map { it.toRecord() }
    }

    /**
     * Run a persisted rule over recent logs with the **same** compiler (org injection + caps) and
     * return the would-be match count/sample WITHOUT writing any signals. New-value previews read the
     * baseline but never mutate it.
     */
    suspend fun preview(organizationId: Int, ruleId: Int): DetectionPreviewResponse? {
        val record = transaction {
            DetectionRules
                .selectAll()
                .where { (DetectionRules.id eq ruleId) and (DetectionRules.organizationId eq organizationId) }
                .firstOrNull()
                ?.toRecord()
        } ?: return null
        return runPreview(organizationId, record, persistedRuleId = ruleId)
    }

    private suspend fun runPreview(
        organizationId: Int,
        record: DetectionRuleRecord,
        persistedRuleId: Int?,
    ): DetectionPreviewResponse {
        val compiled = compiler.compile(organizationId, record.compilerInput())
        val rows = runner.run(compiled)
        val matches = when (record.type) {
            DetectionRuleType.THRESHOLD -> rows
            DetectionRuleType.NEW_VALUE -> filterToNewValues(persistedRuleId, record.groupBy, rows)
        }
        val samples = matches.take(PREVIEW_SAMPLE_LIMIT).map { row ->
            DetectionMatchSample(groupValues = row.groupValues, count = row.count)
        }
        return DetectionPreviewResponse(
            matchCount = matches.size,
            samples = samples,
            windowSeconds = compiled.windowSeconds,
        )
    }

    private fun filterToNewValues(
        ruleId: Int?,
        groupBy: List<String>,
        rows: List<DetectionGroupRow>,
    ): List<DetectionGroupRow> {
        val known = ruleId?.let { baseline.knownKeys(it) } ?: emptySet()
        return rows.filter { row ->
            val key = groupBy.joinToString("|") { col -> "$col=${row.groupValues[col] ?: ""}" }
            key !in known
        }
    }

    private fun parseType(type: String): DetectionRuleType =
        DetectionRuleType.fromWire(type)
            ?: throw DetectionCompileException("Unsupported rule type '${type.take(TYPE_PREVIEW_LEN)}'")

    private fun ResultRow.toResponse(): DetectionRuleResponse = DetectionRuleResponse(
        id = this[DetectionRules.id].value,
        name = this[DetectionRules.name],
        description = this[DetectionRules.description],
        source = this[DetectionRules.ruleSource],
        filter = this[DetectionRules.filter],
        groupBy = decodeStringList(this[DetectionRules.groupBy]),
        windowSeconds = this[DetectionRules.windowSeconds],
        type = this[DetectionRules.type],
        thresholdCount = this[DetectionRules.thresholdCount],
        severity = this[DetectionRules.severity],
        signalTitle = this[DetectionRules.signalTitle],
        signalMessage = this[DetectionRules.signalMessage],
        suppressions = decodeStringList(this[DetectionRules.suppressions]),
        enabled = this[DetectionRules.enabled],
        tags = decodeStringList(this[DetectionRules.tags]),
        createdAt = this[DetectionRules.createdAt].toString(),
        updatedAt = this[DetectionRules.updatedAt].toString(),
    )

    private fun ResultRow.toRecord(): DetectionRuleRecord = DetectionRuleRecord(
        id = this[DetectionRules.id].value,
        organizationId = this[DetectionRules.organizationId],
        name = this[DetectionRules.name],
        source = this[DetectionRules.ruleSource],
        filter = this[DetectionRules.filter],
        groupBy = decodeStringList(this[DetectionRules.groupBy]),
        windowSeconds = this[DetectionRules.windowSeconds],
        type = DetectionRuleType.fromWire(this[DetectionRules.type]) ?: DetectionRuleType.THRESHOLD,
        thresholdCount = this[DetectionRules.thresholdCount],
        severity = SignalSeverity.fromWire(this[DetectionRules.severity]),
        signalTitle = this[DetectionRules.signalTitle],
        signalMessage = this[DetectionRules.signalMessage],
        createdAt = this[DetectionRules.createdAt],
    )

    private fun decodeStringList(raw: String): List<String> =
        runCatching { ruleJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    companion object {
        private const val TYPE_PREVIEW_LEN = 32
    }
}

private fun CreateDetectionRuleRequest.toCompilerInput(type: DetectionRuleType) =
    RuleQueryCompiler.RuleInput(
        source = source,
        filter = filter,
        groupBy = groupBy,
        windowSeconds = windowSeconds,
        type = type,
        thresholdCount = thresholdCount,
    )

private fun DetectionRuleResponse.mergedWith(request: UpdateDetectionRuleRequest) = CreateDetectionRuleRequest(
    name = request.name ?: name,
    description = request.description ?: description,
    source = request.source ?: source,
    filter = request.filter ?: filter,
    groupBy = request.groupBy ?: groupBy,
    windowSeconds = request.windowSeconds ?: windowSeconds,
    type = request.type ?: type,
    thresholdCount = if (request.thresholdCount != null) request.thresholdCount else thresholdCount,
    severity = request.severity ?: severity,
    signalTitle = request.signalTitle ?: signalTitle,
    signalMessage = request.signalMessage ?: signalMessage,
    suppressions = request.suppressions ?: suppressions,
    enabled = request.enabled ?: enabled,
    tags = request.tags ?: tags,
)
