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

package com.moneat.notifications.services

import com.moneat.config.EnvConfig
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.shared.models.SlackOutboundDeliveryStatus
import com.moneat.shared.models.SlackOutboundDeliveries
import com.moneat.shared.models.SlackOutboundOperation
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import java.util.Locale

data class SlackOutboundEnqueueRequest(
    val organizationId: Int,
    val teamId: String?,
    val channelId: String?,
    val operation: SlackOutboundOperation,
    val idempotencyKey: String,
    val payload: String,
    val desiredVersion: Int = 1,
)

data class SlackOutboundDelivery(
    val resourceId: String,
    val organizationId: Int,
    val teamId: String?,
    val channelId: String?,
    val operation: SlackOutboundOperation,
    val idempotencyKey: String,
    val payload: String,
    val desiredVersion: Int,
    val attemptCount: Int,
)

sealed interface SlackOutboundSendResult {
    data class Delivered(
        val providerMessageId: String? = null,
        val providerMessageTs: String? = null,
    ) : SlackOutboundSendResult

    data class Retry(
        val reason: String,
        val retryAt: Instant? = null,
        val rateLimited: Boolean = false,
    ) : SlackOutboundSendResult

    data class Failed(val reason: String) : SlackOutboundSendResult

    data object Superseded : SlackOutboundSendResult
}

fun interface SlackOutboundSender {
    suspend fun send(delivery: SlackOutboundDelivery): SlackOutboundSendResult
}

data class SlackOutboundMetrics(
    val oldestPendingAge: Duration?,
    val failureCount: Long,
    val rateLimitedCount: Long,
    val deadLetterCount: Long,
    val lastSuccessfulDelivery: Instant?,
)

enum class SlackOutboundObservation {
    PRESENT,
    MISSING,
    EDITED,
    DELETED,
    SUPERSEDED,
}

data class SlackOutboundReconciliationRequest(
    val resourceId: String,
    val observation: SlackOutboundObservation,
    val observedVersion: Int? = null,
    val providerMessageId: String? = null,
    val providerMessageTs: String? = null,
)

class SlackOutboundDeliveryService(
    private val clock: Clock = Clock.System,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseRetryDelay: Duration = DEFAULT_RETRY_DELAY,
) {
    init {
        require(maxAttempts > 0) { "Slack outbound max attempts must be positive" }
        require(baseRetryDelay.isPositive()) { "Slack outbound retry delay must be positive" }
    }

    fun enqueue(request: SlackOutboundEnqueueRequest): String = transaction {
        val now = clock.now()
        SlackOutboundDeliveries.insertIgnore {
            it[resourceId] = Uuid.random()
            it[organizationId] = request.organizationId
            it[teamId] = request.teamId
            it[channelId] = request.channelId
            it[operation] = request.operation.wire
            it[idempotencyKey] = request.idempotencyKey
            it[payload] = request.payload
            it[desiredVersion] = request.desiredVersion
            it[status] = SlackOutboundDeliveryStatus.PENDING.wire
            it[attemptCount] = 0
            it[availableAt] = now
            it[createdAt] = now
            it[updatedAt] = now
        }
        val existing = SlackOutboundDeliveries
            .selectAll()
            .where {
                (SlackOutboundDeliveries.organizationId eq request.organizationId) and
                    (SlackOutboundDeliveries.idempotencyKey eq request.idempotencyKey)
            }
            .single()
        if (existing[SlackOutboundDeliveries.desiredVersion] < request.desiredVersion) {
            SlackOutboundDeliveries.update({ SlackOutboundDeliveries.id eq existing[SlackOutboundDeliveries.id] }) {
                it[teamId] = request.teamId
                it[channelId] = request.channelId
                it[operation] = request.operation.wire
                it[payload] = request.payload
                it[desiredVersion] = request.desiredVersion
                it[status] = SlackOutboundDeliveryStatus.PENDING.wire
                it[availableAt] = now
                it[supersededAt] = null
                it[lastError] = null
                it[updatedAt] = now
            }
        }
        existing[SlackOutboundDeliveries.resourceId].toString()
    }

    /** Applies an observation from Slack without allowing an older version to overwrite newer desired state. */
    fun reconcile(request: SlackOutboundReconciliationRequest): SlackOutboundDeliveryStatus? = transaction {
        val now = clock.now()
        val resourceId = Uuid.parse(request.resourceId)
        val row = SlackOutboundDeliveries
            .selectAll()
            .where { SlackOutboundDeliveries.resourceId eq resourceId }
            .singleOrNull() ?: return@transaction null
        val status = reconciliationStatus(row, request)
        SlackOutboundDeliveries.update({ SlackOutboundDeliveries.id eq row[SlackOutboundDeliveries.id] }) {
            it[SlackOutboundDeliveries.status] = status.wire
            it[SlackOutboundDeliveries.providerMessageId] = request.providerMessageId
            it[SlackOutboundDeliveries.providerMessageTs] = request.providerMessageTs
            it[SlackOutboundDeliveries.availableAt] = now
            it[SlackOutboundDeliveries.lastError] = if (status == SlackOutboundDeliveryStatus.PENDING) {
                "Slack message observed as ${request.observation.name.lowercase(Locale.US)}"
            } else {
                null
            }
            it[SlackOutboundDeliveries.supersededAt] =
                if (status == SlackOutboundDeliveryStatus.SUPERSEDED) now else null
            it[SlackOutboundDeliveries.deliveredVersion] = if (status == SlackOutboundDeliveryStatus.DELIVERED) {
                request.observedVersion ?: row[SlackOutboundDeliveries.desiredVersion]
            } else {
                row[SlackOutboundDeliveries.deliveredVersion]
            }
            it[SlackOutboundDeliveries.deliveredAt] = if (status == SlackOutboundDeliveryStatus.DELIVERED) now else null
            it[SlackOutboundDeliveries.leasedAt] = null
            it[SlackOutboundDeliveries.leaseOwner] = null
            it[SlackOutboundDeliveries.updatedAt] = now
        }
        status
    }

    private fun reconciliationStatus(
        row: org.jetbrains.exposed.v1.core.ResultRow,
        request: SlackOutboundReconciliationRequest,
    ): SlackOutboundDeliveryStatus =
        when (request.observation) {
            SlackOutboundObservation.SUPERSEDED -> SlackOutboundDeliveryStatus.SUPERSEDED
            SlackOutboundObservation.PRESENT -> presentObservationStatus(row, request.observedVersion)
            SlackOutboundObservation.MISSING,
            SlackOutboundObservation.EDITED,
            SlackOutboundObservation.DELETED,
            -> missingObservationStatus(row)
        }

    private fun presentObservationStatus(
        row: org.jetbrains.exposed.v1.core.ResultRow,
        observedVersion: Int?,
    ): SlackOutboundDeliveryStatus =
        if (observedVersion == null || observedVersion >= row[SlackOutboundDeliveries.desiredVersion]) {
            SlackOutboundDeliveryStatus.DELIVERED
        } else {
            SlackOutboundDeliveryStatus.PENDING
        }

    private fun missingObservationStatus(
        row: org.jetbrains.exposed.v1.core.ResultRow,
    ): SlackOutboundDeliveryStatus =
        if (row[SlackOutboundDeliveries.status] == SlackOutboundDeliveryStatus.DEAD_LETTER.wire ||
            row[SlackOutboundDeliveries.attemptCount] >= maxAttempts
        ) {
            SlackOutboundDeliveryStatus.DEAD_LETTER
        } else {
            SlackOutboundDeliveryStatus.PENDING
        }

    fun enqueueAndWake(
        request: SlackOutboundEnqueueRequest,
        queueKey: String = EnvConfig.get("SLACK_OUTBOUND_QUEUE_KEY") ?: DEFAULT_QUEUE_KEY,
    ): String {
        val resourceId = enqueue(request)
        IngestionQueueClient.enqueue(IngestionPipeline.SLACK_OUTBOUND, queueKey, resourceId)
        return resourceId
    }

    suspend fun process(resourceId: String, sender: SlackOutboundSender): Boolean {
        val delivery = claim(resourceId) ?: return false
        return when (val result = sender.send(delivery)) {
            is SlackOutboundSendResult.Delivered -> {
                markDelivered(delivery, result)
                true
            }
            is SlackOutboundSendResult.Retry -> {
                markRetry(delivery, result)
                true
            }
            is SlackOutboundSendResult.Failed -> {
                markRetry(delivery, SlackOutboundSendResult.Retry(result.reason))
                true
            }
            SlackOutboundSendResult.Superseded -> {
                markSuperseded(delivery)
                true
            }
        }
    }

    fun metrics(): SlackOutboundMetrics = transaction {
        val now = clock.now()
        val pending = SlackOutboundDeliveries
            .selectAll()
            .where {
                (SlackOutboundDeliveries.status eq SlackOutboundDeliveryStatus.PENDING.wire) or
                    (SlackOutboundDeliveries.status eq SlackOutboundDeliveryStatus.RETRY.wire)
            }
            .minByOrNull { it[SlackOutboundDeliveries.availableAt] }
        val lastDelivered = SlackOutboundDeliveries
            .selectAll()
            .where { SlackOutboundDeliveries.status eq SlackOutboundDeliveryStatus.DELIVERED.wire }
            .maxByOrNull { it[SlackOutboundDeliveries.deliveredAt] ?: Instant.DISTANT_PAST }
        SlackOutboundMetrics(
            oldestPendingAge = pending?.let { now - it[SlackOutboundDeliveries.createdAt] },
            failureCount = SlackOutboundDeliveries
                .selectAll()
                .where { SlackOutboundDeliveries.lastError.isNotNull() }
                .count(),
            rateLimitedCount = SlackOutboundDeliveries
                .selectAll()
                .where { SlackOutboundDeliveries.rateLimitResetAt.isNotNull() }
                .count(),
            deadLetterCount = SlackOutboundDeliveries
                .selectAll()
                .where { SlackOutboundDeliveries.status eq SlackOutboundDeliveryStatus.DEAD_LETTER.wire }
                .count(),
            lastSuccessfulDelivery = lastDelivered?.get(SlackOutboundDeliveries.deliveredAt),
        )
    }

    private fun claim(resourceId: String): SlackOutboundDelivery? = transaction {
        val parsedId = Uuid.parse(resourceId)
        val now = clock.now()
        val row = SlackOutboundDeliveries
            .selectAll()
            .where {
                (SlackOutboundDeliveries.resourceId eq parsedId) and
                    (
                        (SlackOutboundDeliveries.status eq SlackOutboundDeliveryStatus.PENDING.wire) or
                            (
                                (SlackOutboundDeliveries.status eq SlackOutboundDeliveryStatus.RETRY.wire) and
                                    (SlackOutboundDeliveries.availableAt lessEq now)
                                ) or
                            (
                                (SlackOutboundDeliveries.status eq SlackOutboundDeliveryStatus.PROCESSING.wire) and
                                    (SlackOutboundDeliveries.leasedAt lessEq now - LEASE_TIMEOUT)
                                )
                        )
            }
            .singleOrNull() ?: return@transaction null
        val updated = SlackOutboundDeliveries.update({
            SlackOutboundDeliveries.id eq row[SlackOutboundDeliveries.id]
        }) {
            it[status] = SlackOutboundDeliveryStatus.PROCESSING.wire
            it[attemptCount] = row[SlackOutboundDeliveries.attemptCount] + 1
            it[leasedAt] = now
            it[leaseOwner] = WORKER_ID
            it[updatedAt] = now
        }
        if (updated == 0) return@transaction null
        row.toDelivery()
    }

    private fun markDelivered(delivery: SlackOutboundDelivery, result: SlackOutboundSendResult.Delivered) {
        transaction {
            val now = clock.now()
            SlackOutboundDeliveries.update({
                (SlackOutboundDeliveries.resourceId eq Uuid.parse(delivery.resourceId)) and
                    (SlackOutboundDeliveries.status eq SlackOutboundDeliveryStatus.PROCESSING.wire) and
                    (SlackOutboundDeliveries.desiredVersion eq delivery.desiredVersion)
            }) {
                it[status] = SlackOutboundDeliveryStatus.DELIVERED.wire
                it[deliveredVersion] = delivery.desiredVersion
                it[providerMessageId] = result.providerMessageId
                it[providerMessageTs] = result.providerMessageTs
                it[deliveredAt] = now
                it[leasedAt] = null
                it[leaseOwner] = null
                it[updatedAt] = now
            }
        }
    }

    private fun markRetry(delivery: SlackOutboundDelivery, result: SlackOutboundSendResult.Retry) {
        transaction {
            val now = clock.now()
            val currentAttemptCount = SlackOutboundDeliveries
                .selectAll()
                .where { SlackOutboundDeliveries.resourceId eq Uuid.parse(delivery.resourceId) }
                .single()[SlackOutboundDeliveries.attemptCount]
            val deadLetter = currentAttemptCount >= maxAttempts
            SlackOutboundDeliveries.update({
                (SlackOutboundDeliveries.resourceId eq Uuid.parse(delivery.resourceId)) and
                    (SlackOutboundDeliveries.status eq SlackOutboundDeliveryStatus.PROCESSING.wire) and
                    (SlackOutboundDeliveries.desiredVersion eq delivery.desiredVersion)
            }) {
                it[status] = if (deadLetter) {
                    SlackOutboundDeliveryStatus.DEAD_LETTER.wire
                } else {
                    SlackOutboundDeliveryStatus.RETRY.wire
                }
                it[availableAt] = result.retryAt ?: now + retryDelay(delivery.attemptCount)
                it[rateLimitResetAt] = if (result.rateLimited) result.retryAt else null
                it[lastError] = result.reason
                it[leasedAt] = null
                it[leaseOwner] = null
                it[updatedAt] = now
            }
        }
    }

    private fun markSuperseded(delivery: SlackOutboundDelivery) {
        transaction {
            val now = clock.now()
            SlackOutboundDeliveries.update({
                (SlackOutboundDeliveries.resourceId eq Uuid.parse(delivery.resourceId)) and
                    (SlackOutboundDeliveries.status eq SlackOutboundDeliveryStatus.PROCESSING.wire) and
                    (SlackOutboundDeliveries.desiredVersion eq delivery.desiredVersion)
            }) {
                it[status] = SlackOutboundDeliveryStatus.SUPERSEDED.wire
                it[supersededAt] = now
                it[leasedAt] = null
                it[leaseOwner] = null
                it[updatedAt] = now
            }
        }
    }

    private fun retryDelay(attempt: Int): Duration =
        baseRetryDelay * (1 shl (attempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT))

    private fun org.jetbrains.exposed.v1.core.ResultRow.toDelivery(): SlackOutboundDelivery =
        SlackOutboundDelivery(
            resourceId = this[SlackOutboundDeliveries.resourceId].toString(),
            organizationId = this[SlackOutboundDeliveries.organizationId],
            teamId = this[SlackOutboundDeliveries.teamId],
            channelId = this[SlackOutboundDeliveries.channelId],
            operation = SlackOutboundOperation.entries.first { it.wire == this[SlackOutboundDeliveries.operation] },
            idempotencyKey = this[SlackOutboundDeliveries.idempotencyKey],
            payload = this[SlackOutboundDeliveries.payload],
            desiredVersion = this[SlackOutboundDeliveries.desiredVersion],
            attemptCount = this[SlackOutboundDeliveries.attemptCount] + 1,
        )

    companion object {
        private const val DEFAULT_MAX_ATTEMPTS = 5
        private const val MAX_BACKOFF_SHIFT = 5
        private val DEFAULT_RETRY_DELAY = 5.seconds
        private val LEASE_TIMEOUT = 2.minutes
        private const val DEFAULT_QUEUE_KEY = "slack-outbound"
        private const val WORKER_ID = "slack-outbound"
    }
}
