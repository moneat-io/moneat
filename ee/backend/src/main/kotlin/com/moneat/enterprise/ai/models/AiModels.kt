// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.ai.models

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import org.postgresql.util.PGobject
import kotlin.uuid.Uuid

// ── Custom JSONB column type for raw JSON strings ───────────────────────

private class StringJsonbColumnType : ColumnType<String>() {
    override fun sqlType() = "JSONB"

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: "{}"
        is String -> value
        else -> value.toString()
    }

    override fun notNullValueToDB(value: String): Any = PGobject().apply {
        type = "jsonb"
        this.value = value
    }
}

private fun Table.stringJsonb(name: String): Column<String> =
    registerColumn(name, StringJsonbColumnType())

// ── Exposed Table for context snapshots ─────────────────────────────────

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

// ── SSE Event Types ─────────────────────────────────────────────────────

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

// ── API Request DTOs ────────────────────────────────────────────────────

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

// ── Aggregated Context Types ────────────────────────────────────────────

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
