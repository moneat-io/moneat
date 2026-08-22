// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.models

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.postgresql.util.PGobject
import kotlin.uuid.Uuid

// Custom JSONB column type for raw JSON strings.

private class StringJsonbColumnType : ColumnType<String>() {
    private fun isH2(): Boolean = TransactionManager
        .currentOrNull()
        ?.db
        ?.url
        ?.contains("h2", ignoreCase = true) == true

    override fun sqlType() = if (isH2()) "TEXT" else "JSONB"

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: "{}"
        is String -> value
        else -> value.toString()
    }

    override fun notNullValueToDB(value: String): Any {
        if (isH2()) return value
        return PGobject().apply {
            type = "jsonb"
            this.value = value
        }
    }
}

private fun Table.stringJsonb(name: String): Column<String> =
    registerColumn(name, StringJsonbColumnType())

// Exposed table for context snapshots.

object AiContextSnapshots : Table("ai_context_snapshots") {
    val id = integer("id").autoIncrement()
    val resource_id = uuid("resource_id").clientDefault { Uuid.random() }
    val conversation_id = integer("conversation_id")
    val org_id = integer("org_id")
    val user_id = integer("user_id")
    val context_data = stringJsonb("context_data")
    val sources_summary = stringJsonb("sources_summary")
    val estimated_tokens = integer("estimated_tokens")
    val status = varchar("status", 20).default("pending") // pending, confirmed, expired
    val created_at = timestamp("created_at")
    val expires_at = timestamp("expires_at")
    override val primaryKey = PrimaryKey(id)
}

object AiRuns : Table("ai_runs") {
    val id = long("id").autoIncrement()
    val resource_id = uuid("resource_id").clientDefault { Uuid.random() }
    val organization_id = integer("organization_id")
    val user_id = integer("user_id")
    val conversation_id = integer("conversation_id")
    val project_id = long("project_id").nullable()
    val idempotency_key = varchar("idempotency_key", 128)
    val request_fingerprint = char("request_fingerprint", 64)
    val status = varchar("status", 32)
    val current_round = integer("current_round").default(0)
    val provider = varchar("provider", 64).nullable()
    val model = varchar("model", 255).nullable()
    val input_tokens = integer("input_tokens").default(0)
    val output_tokens = integer("output_tokens").default(0)
    val cost_usd = decimal("cost_usd", 18, 8).default(java.math.BigDecimal.ZERO)
    val cost_metadata = stringJsonb("cost_metadata").defaultExpression(stringLiteral("{}"))
    val output_content = text("output_content").nullable()
    val error_code = varchar("error_code", 64).nullable()
    val error_message = text("error_message").nullable()
    val cancellation_requested_at = timestamp("cancellation_requested_at").nullable()
    val cancellation_requested_by = integer("cancellation_requested_by").nullable()
    val started_at = timestamp("started_at").nullable()
    val completed_at = timestamp("completed_at").nullable()
    val created_at = timestamp("created_at")
    val updated_at = timestamp("updated_at")
    val version = long("version").default(0)
    override val primaryKey = PrimaryKey(id)
}

object AiToolCalls : Table("ai_tool_calls") {
    val id = long("id").autoIncrement()
    val resource_id = uuid("resource_id").clientDefault { Uuid.random() }
    val organization_id = integer("organization_id")
    val run_id = long("run_id")
    val round = integer("round")
    val provider_call_id = varchar("provider_call_id", 255)
    val tool_name = varchar("tool_name", 255)
    val arguments = stringJsonb("arguments").nullable()
    val arguments_valid = bool("arguments_valid").default(true)
    val read_only = bool("read_only")
    val status = varchar("status", 32)
    val effect_idempotency_key = varchar("effect_idempotency_key", 255)
    val result = stringJsonb("result").nullable()
    val result_summary = text("result_summary").nullable()
    val is_error = bool("is_error").nullable()
    val result_audit_event_id = uuid("result_audit_event_id").nullable()
    val started_at = timestamp("started_at").nullable()
    val completed_at = timestamp("completed_at").nullable()
    val created_at = timestamp("created_at")
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object AiRunEvidence : Table("ai_run_evidence") {
    val id = long("id").autoIncrement()
    val resource_id = uuid("resource_id").clientDefault { Uuid.random() }
    val organization_id = integer("organization_id")
    val run_id = long("run_id")
    val evidence_type = varchar("evidence_type", 64)
    val source_name = varchar("source", 128)
    val source_resource_id = varchar("source_resource_id", 255).nullable()
    val content = stringJsonb("content")
    val created_at = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AiApprovals : Table("ai_approvals") {
    val id = long("id").autoIncrement()
    val resource_id = uuid("resource_id").clientDefault { Uuid.random() }
    val organization_id = integer("organization_id")
    val run_id = long("run_id")
    val tool_call_id = long("tool_call_id")
    val requested_by = integer("requested_by")
    val decided_by = integer("decided_by").nullable()
    val incident_resource_id = uuid("incident_resource_id").nullable()
    val incident_version = long("incident_version").nullable()
    val proposed_command = stringJsonb("proposed_command")
    val proposal_sha256 = char("proposal_sha256", 64)
    val status = varchar("status", 32)
    val decision_reason = text("decision_reason").nullable()
    val expires_at = timestamp("expires_at")
    val decided_at = timestamp("decided_at").nullable()
    val result_audit_event_id = uuid("result_audit_event_id").nullable()
    val response = stringJsonb("response").nullable()
    val created_at = timestamp("created_at")
    val updated_at = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

// SSE event types.

@Serializable
data class SseSearchProgress(
    val phase: String = "searching",
    val source: String,
    val status: String,
    val count: Int? = null,
)

@Serializable
data class SseContextReady(
    val phase: String = "context_ready",
    val snapshotId: String,
    val totalTokens: Int,
    val sources: Map<String, Int>,
)

@Serializable
data class SseResponseChunk(
    val phase: String = "response",
    val content: String,
    val done: Boolean = false,
)

@Serializable
data class SseError(
    val phase: String = "error",
    val error: String,
)

// API request DTOs.

@Serializable
data class AiChatStreamRequest(
    val conversationId: String? = null,
    val message: String,
    val currentPage: String? = null,
    val timeRange: String? = null, // e.g. "1h", "6h", "24h", "7d"
)

@Serializable
data class AiConfirmRequest(
    val snapshotId: String,
)

// Aggregated context types.

@Serializable
data class AggregatedContext(
    val logs: List<LogEntry> = emptyList(),
    val spans: List<SpanEntry> = emptyList(),
    val events: List<EventEntry> = emptyList(),
    val metrics: List<MetricEntry> = emptyList(),
    val containers: List<ContainerEntry> = emptyList(),
    val summary: ContextSummary = ContextSummary(),
)

@Serializable
data class LogEntry(
    val timestamp: String,
    val level: String,
    val message: String,
    val service: String? = null,
)

@Serializable
data class SpanEntry(
    val traceId: String,
    val spanId: String,
    val operation: String,
    val duration: Long,
    val status: String,
    val service: String? = null,
)

@Serializable
data class EventEntry(
    val timestamp: String,
    val title: String,
    val level: String,
    val fingerprint: String? = null,
    val count: Int = 1,
)

@Serializable
data class MetricEntry(
    val timestamp: String,
    val cpuPercent: Float,
    val memUsedBytes: Long,
    val memTotalBytes: Long,
    val load1: Float,
)

@Serializable
data class ContainerEntry(
    val timestamp: String,
    val containerName: String,
    val status: String,
    val cpuPercent: Float,
    val memUsedBytes: Long,
)

@Serializable
data class ContextSummary(
    val logCount: Int = 0,
    val spanCount: Int = 0,
    val eventCount: Int = 0,
    val metricCount: Int = 0,
    val containerCount: Int = 0,
    val timeRangeStart: String? = null,
    val timeRangeEnd: String? = null,
)
