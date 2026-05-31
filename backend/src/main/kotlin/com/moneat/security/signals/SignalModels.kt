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

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.jsonb
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * Security signals — the deduplicated, scored, triageable finding model that is the single
 * triage surface. State (status/assignee/notes) lives here in Postgres; the matched evidence
 * (CWS events, compliance findings) stays in ClickHouse and is referenced by a query descriptor,
 * never copied row-for-row.
 */
object SecuritySignals : IntIdTable("security_signals") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val signalSource = varchar("source", 32)
    val ruleId = varchar("rule_id", 255)
    val ruleName = varchar("rule_name", 255)
    val severity = varchar("severity", 16)
    val status = varchar("status", 16).default(SignalStatus.OPEN.wire)
    val archiveReason = varchar("archive_reason", 16).nullable()
    val dedupKey = text("dedup_key")
    val entities = jsonb("entities").default("{}")
    val sampleCount = integer("sample_count").default(1)
    val assigneeUserId = integer("assignee_user_id").nullable()
    val tags = jsonb("tags").default("[]")
    val firstSeen = timestamp("first_seen")
    val lastSeen = timestamp("last_seen")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

/** ClickHouse evidence references for a signal — a descriptor that can re-query the source table. */
object SecuritySignalEvidence : IntIdTable("security_signal_evidence") {
    val signalId = integer("signal_id").references(SecuritySignals.id, onDelete = ReferenceOption.CASCADE)
    val evidenceType = varchar("evidence_type", 32)
    val reference = text("reference")
    val createdAt = timestamp("created_at")
}

/** Audit trail for triage transitions — every status change / assignment / note is attributable. */
object SecuritySignalAudit : IntIdTable("security_signal_audit") {
    val signalId = integer("signal_id").references(SecuritySignals.id, onDelete = ReferenceOption.CASCADE)
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val actorUserId = integer("actor_user_id").nullable()
    val action = varchar("action", 32)
    val fromStatus = varchar("from_status", 16).nullable()
    val toStatus = varchar("to_status", 16).nullable()
    val reason = varchar("reason", 16).nullable()
    val note = text("note").nullable()
    val createdAt = timestamp("created_at")
}

/** Lifecycle status of a signal. Wire values are the strings persisted in Postgres and returned in the API. */
enum class SignalStatus(val wire: String) {
    OPEN("open"),
    UNDER_REVIEW("under_review"),
    ARCHIVED("archived");

    companion object {
        fun fromWire(value: String?): SignalStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** Reason recorded when a signal is archived; required only for the archive transition. */
enum class ArchiveReason(val wire: String) {
    TRUE_POSITIVE("true_positive"),
    FALSE_POSITIVE("false_positive"),
    BENIGN("benign");

    companion object {
        fun fromWire(value: String?): ArchiveReason? = entries.firstOrNull { it.wire == value }
    }
}

/** Severity ordered low→high so escalation can compare and pick the maximum. */
enum class SignalSeverity(val wire: String, val rank: Int) {
    INFO("info", 0),
    LOW("low", 1),
    MEDIUM("medium", 2),
    HIGH("high", 3),
    CRITICAL("critical", 4);

    companion object {
        fun fromWire(value: String?): SignalSeverity =
            entries.firstOrNull { it.wire == value } ?: INFO
    }
}

/** Producer that emitted a signal. */
enum class SignalSource(val wire: String) {
    AGENT_RUNTIME("agent_runtime"),
    AGENT_COMPLIANCE("agent_compliance"),
}

/** Audit action kinds. */
enum class SignalAuditAction(val wire: String) {
    STATUS_CHANGE("status_change"),
    ASSIGN("assign"),
    NOTE("note"),
}

/**
 * A request to upsert a signal from a producer. Identity for open-signal dedup is
 * `(organizationId, ruleId, dedupKey)`. [evidenceReference] is a descriptor — source table plus
 * dedup fields plus the time window — sufficient to re-query ClickHouse, never the rows themselves.
 */
data class SignalSpec(
    val source: SignalSource,
    val ruleId: String,
    val ruleName: String,
    val severity: SignalSeverity,
    val dedupKey: String,
    val entities: Map<String, String>,
    val evidenceType: String,
    val evidenceReference: String,
    val occurredAtMs: Long,
)

/** Result of [SignalWriter.upsert]; drives whether the workflow trigger fires (Created/Escalated only). */
sealed interface SignalOutcome {
    val signalId: Int
    val organizationId: Int
    val source: SignalSource
    val ruleId: String
    val ruleName: String
    val severity: SignalSeverity
    val dedupKey: String
    val entities: Map<String, String>

    data class Created(
        override val signalId: Int,
        override val organizationId: Int,
        override val source: SignalSource,
        override val ruleId: String,
        override val ruleName: String,
        override val severity: SignalSeverity,
        override val dedupKey: String,
        override val entities: Map<String, String>,
    ) : SignalOutcome

    data class Escalated(
        override val signalId: Int,
        override val organizationId: Int,
        override val source: SignalSource,
        override val ruleId: String,
        override val ruleName: String,
        override val severity: SignalSeverity,
        override val dedupKey: String,
        override val entities: Map<String, String>,
    ) : SignalOutcome

    data class Updated(
        override val signalId: Int,
        override val organizationId: Int,
        override val source: SignalSource,
        override val ruleId: String,
        override val ruleName: String,
        override val severity: SignalSeverity,
        override val dedupKey: String,
        override val entities: Map<String, String>,
    ) : SignalOutcome
}

@Serializable
data class SignalEvidenceResponse(
    val id: Int,
    @SerialName("evidence_type") val evidenceType: String,
    val reference: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class SignalAuditResponse(
    val id: Int,
    @SerialName("actor_user_id") val actorUserId: Int? = null,
    val action: String,
    @SerialName("from_status") val fromStatus: String? = null,
    @SerialName("to_status") val toStatus: String? = null,
    val reason: String? = null,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class SignalResponse(
    val id: Int,
    val source: String,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("rule_name") val ruleName: String,
    val severity: String,
    val status: String,
    @SerialName("archive_reason") val archiveReason: String? = null,
    @SerialName("dedup_key") val dedupKey: String,
    val entities: Map<String, String> = emptyMap(),
    @SerialName("sample_count") val sampleCount: Int,
    @SerialName("assignee_user_id") val assigneeUserId: Int? = null,
    val tags: List<String> = emptyList(),
    @SerialName("first_seen") val firstSeen: String,
    @SerialName("last_seen") val lastSeen: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class SignalListResponse(
    val signals: List<SignalResponse>,
    @SerialName("total_count") val totalCount: Long,
)

@Serializable
data class SignalDetailResponse(
    val signal: SignalResponse,
    val evidence: List<SignalEvidenceResponse>,
    val audit: List<SignalAuditResponse>,
    @SerialName("sample_events") val sampleEvents: List<JsonElement>,
)

@Serializable
data class TriageRequest(
    val status: String? = null,
    val reason: String? = null,
    @SerialName("assignee_user_id") val assigneeUserId: Int? = null,
    @SerialName("clear_assignee") val clearAssignee: Boolean = false,
    val note: String? = null,
)

/** A validated triage transition, or the reason it was rejected. */
sealed interface TransitionResult {
    /**
     * [status] is null when the request changes only assignee/note (no status move); when non-null
     * it is the new status, with [archiveReason] set iff the target is [SignalStatus.ARCHIVED].
     */
    data class Valid(val status: SignalStatus?, val archiveReason: ArchiveReason?) : TransitionResult
    data class Invalid(val message: String) : TransitionResult
}
