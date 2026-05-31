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

package com.moneat.security.signals

import com.moneat.config.ClickHouseClient
import com.moneat.config.ClickHouseQueryException
import com.moneat.config.isClickHouseError
import com.moneat.utils.ClickHouseQueryUtils
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Filters accepted by [SignalService.list]. All are optional and AND together. */
data class SignalFilters(
    val status: String? = null,
    val severity: String? = null,
    val source: String? = null,
    val from: String? = null,
    val to: String? = null,
)

/** Outcome of a triage attempt so the route can map it to the right HTTP status. */
sealed interface TriageResult {
    data class Ok(val signal: SignalResponse) : TriageResult
    data object NotFound : TriageResult
    data class Invalid(val message: String) : TriageResult
}

/**
 * Read/triage surface for security signals. Every method is org-scoped: a caller can only ever read
 * or mutate signals their organization owns. Sample evidence is pulled from ClickHouse on demand and
 * its errors are sanitized via [ClickHouseQueryException] so no query text or schema leaks.
 */
class SignalService {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun list(orgId: Int, filters: SignalFilters, limit: Int, offset: Int): SignalListResponse =
        transaction {
            val predicate = buildPredicate(orgId, filters)
            val total = SecuritySignals.selectAll().where(predicate).count()
            val rows = SecuritySignals
                .selectAll()
                .where(predicate)
                .orderBy(SecuritySignals.lastSeen to SortOrder.DESC, SecuritySignals.id to SortOrder.DESC)
                .limit(limit).offset(offset.toLong())
                .map { it.toSignalResponse() }
            SignalListResponse(signals = rows, totalCount = total)
        }

    /** Returns the signal with its evidence refs, audit trail, and a bounded sample of CH events. */
    suspend fun get(orgId: Int, signalId: Int): SignalDetailResponse? {
        val detail = transaction {
            val row = SecuritySignals
                .selectAll()
                .where { (SecuritySignals.id eq signalId) and (SecuritySignals.organizationId eq orgId) }
                .firstOrNull() ?: return@transaction null
            val signal = row.toSignalResponse()
            val evidence = SecuritySignalEvidence
                .selectAll()
                .where { SecuritySignalEvidence.signalId eq signalId }
                .orderBy(SecuritySignalEvidence.id to SortOrder.ASC)
                .map {
                    SignalEvidenceResponse(
                        id = it[SecuritySignalEvidence.id].value,
                        evidenceType = it[SecuritySignalEvidence.evidenceType],
                        reference = it[SecuritySignalEvidence.reference],
                        createdAt = it[SecuritySignalEvidence.createdAt].toString(),
                    )
                }
            val audit = SecuritySignalAudit
                .selectAll()
                .where { SecuritySignalAudit.signalId eq signalId }
                .orderBy(SecuritySignalAudit.id to SortOrder.DESC)
                .map {
                    SignalAuditResponse(
                        id = it[SecuritySignalAudit.id].value,
                        actorUserId = it[SecuritySignalAudit.actorUserId],
                        action = it[SecuritySignalAudit.action],
                        fromStatus = it[SecuritySignalAudit.fromStatus],
                        toStatus = it[SecuritySignalAudit.toStatus],
                        reason = it[SecuritySignalAudit.reason],
                        note = it[SecuritySignalAudit.note],
                        createdAt = it[SecuritySignalAudit.createdAt].toString(),
                    )
                }
            Triple(signal, evidence, audit)
        } ?: return null

        val (signal, evidence, audit) = detail
        val sampleEvents = fetchSampleEvents(orgId, signal)
        return SignalDetailResponse(signal = signal, evidence = evidence, audit = audit, sampleEvents = sampleEvents)
    }

    /**
     * Applies a triage action under the state machine `open → under_review → archived{reason}` with
     * a permitted `archived → open` reopen. Persists the new state, optional assignee/note, and one
     * audit row per change with the acting user for attribution.
     */
    fun triage(orgId: Int, signalId: Int, actorUserId: Int?, request: TriageRequest): TriageResult =
        transaction {
            val row = SecuritySignals
                .selectAll()
                .where { (SecuritySignals.id eq signalId) and (SecuritySignals.organizationId eq orgId) }
                .forUpdate()
                .firstOrNull() ?: return@transaction TriageResult.NotFound

            val current = SignalStatus.fromWire(row[SecuritySignals.status]) ?: SignalStatus.OPEN
            val transition = resolveTransition(current, request)
            if (transition is TransitionResult.Invalid) {
                return@transaction TriageResult.Invalid(transition.message)
            }
            val target = (transition as TransitionResult.Valid)

            val now = Clock.System.now()
            SecuritySignals.update({ SecuritySignals.id eq signalId }) {
                if (target.status != null) {
                    it[status] = target.status.wire
                    it[archiveReason] = target.archiveReason?.wire
                }
                if (request.clearAssignee) {
                    it[assigneeUserId] = null
                } else if (request.assigneeUserId != null) {
                    it[assigneeUserId] = request.assigneeUserId
                }
                it[updatedAt] = now
            }

            val audit = AuditContext(signalId, orgId, actorUserId, now)
            if (target.status != null && target.status != current) {
                audit.write(
                    SignalAuditAction.STATUS_CHANGE,
                    from = current,
                    to = target.status,
                    archiveReason = target.archiveReason,
                )
            }
            if (request.clearAssignee || request.assigneeUserId != null) {
                audit.write(SignalAuditAction.ASSIGN)
            }
            if (!request.note.isNullOrBlank()) {
                audit.write(SignalAuditAction.NOTE, auditNote = request.note)
            }

            val updated = SecuritySignals
                .selectAll()
                .where { SecuritySignals.id eq signalId }
                .first()
                .toSignalResponse()
            TriageResult.Ok(updated)
        }

    private fun buildPredicate(
        orgId: Int,
        filters: SignalFilters,
    ): Op<Boolean> {
        var predicate: Op<Boolean> = SecuritySignals.organizationId eq orgId
        SignalStatus.fromWire(filters.status)?.let { predicate = predicate and (SecuritySignals.status eq it.wire) }
        filters.severity?.let { predicate = predicate and (SecuritySignals.severity eq it) }
        filters.source?.let { predicate = predicate and (SecuritySignals.signalSource eq it) }
        parseInstant(filters.from)?.let { predicate = predicate and (SecuritySignals.lastSeen greaterEq it) }
        parseInstant(filters.to)?.let { predicate = predicate and (SecuritySignals.lastSeen lessEq it) }
        return predicate
    }

    /** Captures the fields common to every audit row of one triage so each write stays terse. */
    private inner class AuditContext(
        private val auditSignalId: Int,
        private val auditOrgId: Int,
        private val auditActorUserId: Int?,
        private val auditNow: kotlin.time.Instant,
    ) {
        fun write(
            auditAction: SignalAuditAction,
            from: SignalStatus? = null,
            to: SignalStatus? = null,
            archiveReason: ArchiveReason? = null,
            auditNote: String? = null,
        ) {
            SecuritySignalAudit.insert {
                it[signalId] = auditSignalId
                it[organizationId] = auditOrgId
                it[actorUserId] = auditActorUserId
                it[action] = auditAction.wire
                it[fromStatus] = from?.wire
                it[toStatus] = to?.wire
                it[reason] = archiveReason?.wire
                it[note] = auditNote
                it[createdAt] = auditNow
            }
        }
    }

    private fun ResultRow.toSignalResponse(): SignalResponse =
        SignalResponse(
            id = this[SecuritySignals.id].value,
            source = this[SecuritySignals.signalSource],
            ruleId = this[SecuritySignals.ruleId],
            ruleName = this[SecuritySignals.ruleName],
            severity = this[SecuritySignals.severity],
            status = this[SecuritySignals.status],
            archiveReason = this[SecuritySignals.archiveReason],
            dedupKey = this[SecuritySignals.dedupKey],
            entities = decodeStringMap(this[SecuritySignals.entities]),
            sampleCount = this[SecuritySignals.sampleCount],
            assigneeUserId = this[SecuritySignals.assigneeUserId],
            tags = decodeStringList(this[SecuritySignals.tags]),
            firstSeen = this[SecuritySignals.firstSeen].toString(),
            lastSeen = this[SecuritySignals.lastSeen].toString(),
            createdAt = this[SecuritySignals.createdAt].toString(),
            updatedAt = this[SecuritySignals.updatedAt].toString(),
        )

    private fun decodeStringMap(raw: String): Map<String, String> =
        runCatching {
            json.parseToJsonElement(raw).jsonObject.mapValues { (_, v) -> v.jsonPrimitiveContent() }
        }.getOrDefault(emptyMap())

    private fun decodeStringList(raw: String): List<String> =
        runCatching {
            json.parseToJsonElement(raw).let { el ->
                (el as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitiveContent() } ?: emptyList()
            }
        }.getOrDefault(emptyList())

    private fun JsonElement.jsonPrimitiveContent(): String =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content ?: toString()

    private suspend fun fetchSampleEvents(orgId: Int, signal: SignalResponse): List<JsonElement> =
        when (SignalSource.entries.firstOrNull { it.wire == signal.source }) {
            SignalSource.AGENT_RUNTIME -> fetchRuntimeSamples(orgId, signal)
            SignalSource.AGENT_COMPLIANCE -> fetchComplianceSamples(orgId, signal)
            null -> emptyList()
        }

    private suspend fun fetchRuntimeSamples(orgId: Int, signal: SignalResponse): List<JsonElement> {
        val db = ClickHouseClient.getDatabase()
        val conditions = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(orgId.toLong()),
            "rule_id = '${escapeSql(signal.ruleId)}'",
        )
        signal.entities["host"]?.takeIf { it.isNotBlank() }?.let {
            conditions.add("host = '${escapeSql(it)}'")
        }
        signal.entities["process"]?.takeIf { it.isNotBlank() }?.let {
            conditions.add("process_name = '${escapeSql(it)}'")
        }
        val where = conditions.joinToString(" AND ")
        return executeSamples(
            """SELECT event_id, rule_id, rule_name, severity, event_type, process_name,
                file_path, host, env,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
            FROM `$db`.security_events WHERE $where
            ORDER BY timestamp DESC LIMIT $SAMPLE_EVENT_LIMIT FORMAT JSONEachRow""",
        ) { obj ->
            buildJsonObject {
                put("eventId", obj.s("event_id"))
                put("ruleId", obj.s("rule_id"))
                put("ruleName", obj.s("rule_name"))
                put("severity", obj.s("severity"))
                put("eventType", obj.s("event_type"))
                put("processName", obj.s("process_name"))
                put("filePath", obj.s("file_path"))
                put("host", obj.s("host"))
                put("env", obj.s("env"))
                put("timestamp", obj.s("ts"))
            }
        }
    }

    private suspend fun fetchComplianceSamples(orgId: Int, signal: SignalResponse): List<JsonElement> {
        val db = ClickHouseClient.getDatabase()
        val conditions = mutableListOf(
            ClickHouseQueryUtils.orgIdClause(orgId.toLong()),
            "rule_id = '${escapeSql(signal.ruleId)}'",
        )
        signal.entities["resource"]?.takeIf { it.isNotBlank() }?.let {
            conditions.add("resource_id = '${escapeSql(it)}'")
        }
        val where = conditions.joinToString(" AND ")
        return executeSamples(
            """SELECT finding_id, framework, rule_id, rule_name, status, resource_type,
                resource_id, resource_name,
                formatDateTime(evaluated_at, '%Y-%m-%dT%H:%i:%S.000Z', 'UTC') as ts
            FROM `$db`.compliance_findings WHERE $where
            ORDER BY evaluated_at DESC LIMIT $SAMPLE_EVENT_LIMIT FORMAT JSONEachRow""",
        ) { obj ->
            buildJsonObject {
                put("findingId", obj.s("finding_id"))
                put("framework", obj.s("framework"))
                put("ruleId", obj.s("rule_id"))
                put("ruleName", obj.s("rule_name"))
                put("status", obj.s("status"))
                put("resourceType", obj.s("resource_type"))
                put("resourceId", obj.s("resource_id"))
                put("resourceName", obj.s("resource_name"))
                put("evaluatedAt", obj.s("ts"))
            }
        }
    }

    private suspend fun executeSamples(
        sql: String,
        mapper: (JsonObject) -> JsonObject,
    ): List<JsonElement> {
        val resp = ClickHouseClient.execute(sql)
        val body = resp.bodyAsText()
        if (resp.isClickHouseError(body)) {
            throw sampleQueryError(sql, body)
        }
        return body.trim().lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() }
            .map { mapper(it) }
    }

    /**
     * Logs the query and ClickHouse body server-side and returns a [ClickHouseQueryException]; the
     * status-pages plugin maps it to a generic client response so no SQL/schema text ever leaks.
     */
    private fun sampleQueryError(sql: String, body: String): ClickHouseQueryException {
        logger.error {
            "ClickHouse error in signal sample fetch. " +
                "SQL: ${sql.take(LOG_SQL_MAX_LEN)} Body: ${body.take(LOG_BODY_MAX_LEN)}"
        }
        return ClickHouseQueryException(
            isTimeout = false,
            internalDetail = "Signal sample query failed: ${body.take(LOG_BODY_MAX_LEN)}",
        )
    }

    private fun parseInstant(value: String?): kotlin.time.Instant? =
        value?.let { runCatching { kotlin.time.Instant.parse(it) }.getOrNull() }

    private fun JsonObject.s(key: String): String {
        val el = this[key] ?: return ""
        return (el as? kotlinx.serialization.json.JsonPrimitive)?.content ?: el.toString()
    }

    companion object {
        private const val SAMPLE_EVENT_LIMIT = 25
        private const val LOG_SQL_MAX_LEN = 200
        private const val LOG_BODY_MAX_LEN = 300
    }
}
