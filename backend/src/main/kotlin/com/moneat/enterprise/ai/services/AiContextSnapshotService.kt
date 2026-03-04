// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.ai.services

import com.moneat.enterprise.ai.models.AggregatedContext
import com.moneat.enterprise.ai.models.AiContextSnapshots
import com.moneat.enterprise.ai.models.ContextSummary
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * Manages AI context snapshots in PostgreSQL.
 * Snapshots hold aggregated observability data between the search phase
 * and the LLM confirmation phase, with automatic TTL cleanup.
 */
class AiContextSnapshotService {

    private var cleanupJob: Job? = null

    fun createSnapshot(
        conversationId: Int,
        orgId: Int,
        userId: Int,
        context: AggregatedContext,
        estimatedTokens: Int,
    ): Int {
        val now = Clock.System.now()
        val expiresAt = now.plus(1.hours)
        val contextJson = json.encodeToString(AggregatedContext.serializer(), context)
        val summaryJson = json.encodeToString(ContextSummary.serializer(), context.summary)

        return transaction {
            AiContextSnapshots.insert {
                it[AiContextSnapshots.conversation_id] = conversationId
                it[AiContextSnapshots.org_id] = orgId
                it[AiContextSnapshots.user_id] = userId
                it[AiContextSnapshots.context_data] = contextJson
                it[AiContextSnapshots.sources_summary] = summaryJson
                it[AiContextSnapshots.estimated_tokens] = estimatedTokens
                it[AiContextSnapshots.status] = "pending"
                it[AiContextSnapshots.created_at] = now
                it[AiContextSnapshots.expires_at] = expiresAt
            } get AiContextSnapshots.id
        }
    }

    fun loadSnapshot(snapshotId: Int, userId: Int): AggregatedContext? {
        return transaction {
            val row = AiContextSnapshots
                .selectAll()
                .where {
                    (AiContextSnapshots.id eq snapshotId) and
                        (AiContextSnapshots.user_id eq userId) and
                        (AiContextSnapshots.status eq "pending")
                }
                .firstOrNull() ?: return@transaction null

            json.decodeFromString(AggregatedContext.serializer(), row[AiContextSnapshots.context_data])
        }
    }

    fun confirmSnapshot(snapshotId: Int, userId: Int): Boolean {
        return transaction {
            AiContextSnapshots.update({
                (AiContextSnapshots.id eq snapshotId) and
                    (AiContextSnapshots.user_id eq userId) and
                    (AiContextSnapshots.status eq "pending")
            }) {
                it[status] = "confirmed"
            } > 0
        }
    }

    fun getSnapshotConversationId(snapshotId: Int, userId: Int): Int? {
        return transaction {
            AiContextSnapshots
                .selectAll()
                .where {
                    (AiContextSnapshots.id eq snapshotId) and
                        (AiContextSnapshots.user_id eq userId)
                }
                .firstOrNull()
                ?.get(AiContextSnapshots.conversation_id)
        }
    }

    fun startCleanupJob() {
        cleanupJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isActive) {
                try {
                    cleanupExpiredSnapshots()
                } catch (e: Exception) {
                    logger.warn(e) { "Snapshot cleanup error" }
                }
                delay(10.minutes.inWholeMilliseconds)
            }
        }
        logger.info { "AI context snapshot cleanup job started" }
    }

    fun stopCleanupJob() {
        cleanupJob?.cancel()
        cleanupJob = null
        logger.info { "AI context snapshot cleanup job stopped" }
    }

    private fun cleanupExpiredSnapshots() {
        val now = Clock.System.now()
        val deleted = transaction {
            AiContextSnapshots.deleteWhere {
                AiContextSnapshots.expires_at less now
            }
        }
        if (deleted > 0) {
            logger.info { "Cleaned up $deleted expired AI context snapshots" }
        }
    }
}
