// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.events

import com.moneat.enterprise.FeatureRegistry
import com.moneat.enterprise.incidents.commands.IncidentEntitlement
import com.moneat.enterprise.incidents.models.IncidentDeliveryStatus
import com.moneat.enterprise.incidents.models.IncidentOutboxStatus
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxDeliveries
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxEvents
import com.moneat.monitoring.OperationalMetrics
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

data class NativeIncidentDomainEvent(
    val id: Int,
    val resourceId: String,
    val organizationId: Int,
    val incidentId: Int,
    val eventType: String,
    val aggregateVersion: Int,
    val idempotencyKey: String,
    val payload: Map<String, JsonElement>,
    val createdAt: String,
)

interface NativeIncidentEventConsumer {
    val name: String

    /**
     * Consumers must use [deliveryKey] as their idempotency key. Delivery is at least once.
     */
    suspend fun consume(event: NativeIncidentDomainEvent, deliveryKey: String)
}

data class PendingNativeIncidentDomainEvent(
    val organizationId: Int,
    val incidentId: Int,
    val eventType: String,
    val aggregateVersion: Int,
    val idempotencyKey: String,
    val payload: Map<String, JsonElement>,
)

class IncidentOutboxWriter {
    /** Must be called inside the transaction that commits the aggregate mutation. */
    fun record(event: PendingNativeIncidentDomainEvent): Int {
        val now = Clock.System.now()
        return NativeIncidentOutboxEvents.insertAndGetId {
            it[resourceId] = Uuid.random()
            it[NativeIncidentOutboxEvents.organizationId] = event.organizationId
            it[NativeIncidentOutboxEvents.incidentId] = event.incidentId
            it[NativeIncidentOutboxEvents.eventType] = event.eventType
            it[NativeIncidentOutboxEvents.aggregateVersion] = event.aggregateVersion
            it[NativeIncidentOutboxEvents.idempotencyKey] = event.idempotencyKey
            it[NativeIncidentOutboxEvents.payload] = event.payload
            it[status] = IncidentOutboxStatus.PENDING.wire
            it[attemptCount] = 0
            it[availableAt] = now
            it[leasedAt] = null
            it[leaseOwner] = null
            it[lastError] = null
            it[publishedAt] = null
            it[createdAt] = now
            it[updatedAt] = now
        }.value
    }
}

class IncidentOutboxService(
    consumers: List<NativeIncidentEventConsumer>,
    private val workerId: String = "incident-outbox-${Uuid.random()}",
    private val clock: Clock = Clock.System,
    private val entitlement: IncidentEntitlement = IncidentEntitlement {
        FeatureRegistry.isNativeIncidentResponseEnabled(it)
    },
) {
    private val logger = LoggerFactory.getLogger(IncidentOutboxService::class.java)
    private val consumersByName = consumers.associateBy { it.name }

    init {
        require(consumers.all { it.name.isNotBlank() && it.name.length <= MAX_CONSUMER_NAME_LENGTH }) {
            "Incident outbox consumer names must contain 1 to $MAX_CONSUMER_NAME_LENGTH characters"
        }
        require(consumersByName.size == consumers.size) { "Incident outbox consumer names must be unique" }
    }

    suspend fun processBatch(limit: Int = DEFAULT_BATCH_SIZE): Int {
        require(limit in 1..MAX_BATCH_SIZE) { "Incident outbox batch size must be between 1 and $MAX_BATCH_SIZE" }
        var processed = 0
        repeat(limit) {
            val event = claimNextEvent() ?: return processed
            if (entitlement.isEnabled(event.organizationId)) {
                processEvent(event)
            } else {
                deferEventForRollout(event)
            }
            processed += 1
        }
        return processed
    }

    fun replay(eventResourceId: String): Boolean =
        transaction {
            val event =
                NativeIncidentOutboxEvents
                    .selectAll()
                    .where { NativeIncidentOutboxEvents.resourceId eq Uuid.parse(eventResourceId) }
                    .singleOrNull() ?: return@transaction false
            val eventId = event[NativeIncidentOutboxEvents.id].value
            val now = clock.now()
            NativeIncidentOutboxDeliveries.update({ NativeIncidentOutboxDeliveries.eventId eq eventId }) {
                it[status] = IncidentDeliveryStatus.PENDING.wire
                it[attemptCount] = 0
                it[availableAt] = now
                it[leasedAt] = null
                it[leaseOwner] = null
                it[lastError] = null
                it[updatedAt] = now
            }
            NativeIncidentOutboxEvents.update({ NativeIncidentOutboxEvents.id eq eventId }) {
                it[status] = IncidentOutboxStatus.PENDING.wire
                it[attemptCount] = 0
                it[availableAt] = now
                it[leasedAt] = null
                it[leaseOwner] = null
                it[lastError] = null
                it[updatedAt] = now
            }
            true
        }

    fun deadLetterCount(): Long =
        transaction {
            NativeIncidentOutboxEvents
                .selectAll()
                .where { NativeIncidentOutboxEvents.status eq IncidentOutboxStatus.DEAD_LETTER.wire }
                .count()
        }

    private fun claimNextEvent(): NativeIncidentDomainEvent? =
        transaction {
            val now = clock.now()
            val staleLease = now.minus(LEASE_DURATION)
            val oldestEventId = NativeIncidentOutboxEvents.id.min()
            val headEventIds =
                NativeIncidentOutboxEvents
                    .select(oldestEventId)
                    .where { NativeIncidentOutboxEvents.status neq IncidentOutboxStatus.PUBLISHED.wire }
                    .groupBy(NativeIncidentOutboxEvents.incidentId)
                    .mapNotNull { row -> row[oldestEventId] }
            if (headEventIds.isEmpty()) return@transaction null
            val row =
                NativeIncidentOutboxEvents
                    .selectAll()
                    .where {
                        (NativeIncidentOutboxEvents.id inList headEventIds) and
                            (
                                (
                                    (NativeIncidentOutboxEvents.status eq IncidentOutboxStatus.PENDING.wire) and
                                        (NativeIncidentOutboxEvents.availableAt lessEq now)
                                    ) or
                                    (
                                        (NativeIncidentOutboxEvents.status eq
                                            IncidentOutboxStatus.PROCESSING.wire) and
                                            (NativeIncidentOutboxEvents.leasedAt lessEq staleLease)
                                        )
                                )
                    }
                    .orderBy(
                        NativeIncidentOutboxEvents.availableAt to SortOrder.ASC,
                        NativeIncidentOutboxEvents.id to SortOrder.ASC,
                    ).limit(1)
                    .singleOrNull()
                    ?: return@transaction null

            val eventId = row[NativeIncidentOutboxEvents.id].value
            val claimed =
                NativeIncidentOutboxEvents.update({
                    (NativeIncidentOutboxEvents.id eq eventId) and
                        (
                            (NativeIncidentOutboxEvents.status eq IncidentOutboxStatus.PENDING.wire) or
                                (NativeIncidentOutboxEvents.leasedAt lessEq staleLease)
                            )
                }) {
                    it[status] = IncidentOutboxStatus.PROCESSING.wire
                    it[attemptCount] = row[NativeIncidentOutboxEvents.attemptCount] + 1
                    it[leasedAt] = now
                    it[leaseOwner] = workerId
                    it[updatedAt] = now
                }
            if (claimed == 0) return@transaction null
            row.toDomainEvent()
        }

    private suspend fun processEvent(event: NativeIncidentDomainEvent) {
        if (consumersByName.isEmpty()) {
            rescheduleEvent(event.id, "No incident event consumers are registered")
            return
        }

        ensureDeliveries(event)
        var failed = false
        var deadLettered = false
        consumersByName.values.forEach { consumer ->
            when (deliver(event, consumer)) {
                DeliveryOutcome.DELIVERED -> Unit
                DeliveryOutcome.RETRY -> failed = true
                DeliveryOutcome.DEAD_LETTER -> {
                    failed = true
                    deadLettered = true
                }
            }
        }

        when {
            deadLettered -> markEventDead(event.id, "One or more consumers exhausted retries")
            failed -> rescheduleEvent(event.id, "One or more consumers require retry")
            else -> markEventPublished(event.id)
        }
    }

    private fun deferEventForRollout(event: NativeIncidentDomainEvent) {
        transaction {
            val row =
                NativeIncidentOutboxEvents
                    .selectAll()
                    .where { NativeIncidentOutboxEvents.id eq event.id }
                    .single()
            val now = clock.now()
            NativeIncidentOutboxEvents.update({ NativeIncidentOutboxEvents.id eq event.id }) {
                it[status] = IncidentOutboxStatus.PENDING.wire
                it[attemptCount] = (row[NativeIncidentOutboxEvents.attemptCount] - 1).coerceAtLeast(0)
                it[availableAt] = now.plus(ROLLOUT_DEFER_DURATION)
                it[leasedAt] = null
                it[leaseOwner] = null
                it[lastError] = ROLLOUT_DISABLED_MESSAGE
                it[updatedAt] = now
            }
        }
        OperationalMetrics.recordNativeIncidentRolloutDecision("outbox", "deferred")
        logger.debug(
            "Deferred native incident outbox event {} for organization {} while rollout is disabled",
            event.resourceId,
            event.organizationId,
        )
    }

    private fun ensureDeliveries(event: NativeIncidentDomainEvent) {
        transaction {
            val now = clock.now()
            consumersByName.keys.forEach { consumerName ->
                NativeIncidentOutboxDeliveries.insertIgnore {
                    it[resourceId] = Uuid.random()
                    it[eventId] = event.id
                    it[NativeIncidentOutboxDeliveries.consumerName] = consumerName
                    it[deliveryKey] = "${event.organizationId}:${event.idempotencyKey}:$consumerName"
                    it[status] = IncidentDeliveryStatus.PENDING.wire
                    it[attemptCount] = 0
                    it[availableAt] = now
                    it[leasedAt] = null
                    it[leaseOwner] = null
                    it[lastError] = null
                    it[deliveredAt] = null
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    private suspend fun deliver(
        event: NativeIncidentDomainEvent,
        consumer: NativeIncidentEventConsumer,
    ): DeliveryOutcome {
        val claimed = claimDelivery(event.id, consumer.name) ?: return DeliveryOutcome.DELIVERED
        if (claimed.deadLettered) return DeliveryOutcome.DEAD_LETTER
        return try {
            consumer.consume(event, claimed.deliveryKey)
            markDeliveryDelivered(claimed.id)
            DeliveryOutcome.DELIVERED
        } catch (e: Exception) {
            logger.error(
                "Incident event delivery failed for event={} consumer={} attempt={}",
                event.resourceId,
                consumer.name,
                claimed.attemptCount,
                e,
            )
            if (claimed.attemptCount >= MAX_ATTEMPTS) {
                markDeliveryDead(claimed.id, e.message)
                DeliveryOutcome.DEAD_LETTER
            } else {
                rescheduleDelivery(claimed.id, claimed.attemptCount, e.message)
                DeliveryOutcome.RETRY
            }
        }
    }

    private fun claimDelivery(eventId: Int, consumerName: String): ClaimedDelivery? =
        transaction {
            val row =
                NativeIncidentOutboxDeliveries
                    .selectAll()
                    .where {
                        (NativeIncidentOutboxDeliveries.eventId eq eventId) and
                            (NativeIncidentOutboxDeliveries.consumerName eq consumerName)
                    }.single()
            if (row[NativeIncidentOutboxDeliveries.status] == IncidentDeliveryStatus.DELIVERED.wire) {
                return@transaction null
            }
            if (row[NativeIncidentOutboxDeliveries.status] == IncidentDeliveryStatus.DEAD_LETTER.wire) {
                return@transaction ClaimedDelivery(
                    id = row[NativeIncidentOutboxDeliveries.id].value,
                    deliveryKey = row[NativeIncidentOutboxDeliveries.deliveryKey],
                    attemptCount = MAX_ATTEMPTS,
                    deadLettered = true,
                )
            }
            val now = clock.now()
            val attempt = row[NativeIncidentOutboxDeliveries.attemptCount] + 1
            val deliveryId = row[NativeIncidentOutboxDeliveries.id]
            NativeIncidentOutboxDeliveries.update({ NativeIncidentOutboxDeliveries.id eq deliveryId }) {
                it[status] = IncidentDeliveryStatus.PROCESSING.wire
                it[attemptCount] = attempt
                it[leasedAt] = now
                it[leaseOwner] = workerId
                it[updatedAt] = now
            }
            ClaimedDelivery(
                id = row[NativeIncidentOutboxDeliveries.id].value,
                deliveryKey = row[NativeIncidentOutboxDeliveries.deliveryKey],
                attemptCount = attempt,
                deadLettered = false,
            )
        }

    private fun markDeliveryDelivered(deliveryId: Int) {
        transaction {
            val now = clock.now()
            NativeIncidentOutboxDeliveries.update({ NativeIncidentOutboxDeliveries.id eq deliveryId }) {
                it[status] = IncidentDeliveryStatus.DELIVERED.wire
                it[deliveredAt] = now
                it[leasedAt] = null
                it[leaseOwner] = null
                it[lastError] = null
                it[updatedAt] = now
            }
        }
    }

    private fun rescheduleDelivery(deliveryId: Int, attempt: Int, error: String?) {
        transaction {
            val now = clock.now()
            NativeIncidentOutboxDeliveries.update({ NativeIncidentOutboxDeliveries.id eq deliveryId }) {
                it[status] = IncidentDeliveryStatus.PENDING.wire
                it[availableAt] = now.plus(backoff(attempt))
                it[leasedAt] = null
                it[leaseOwner] = null
                it[lastError] = error?.take(MAX_ERROR_LENGTH)
                it[updatedAt] = now
            }
        }
    }

    private fun markDeliveryDead(deliveryId: Int, error: String?) {
        transaction {
            val now = clock.now()
            NativeIncidentOutboxDeliveries.update({ NativeIncidentOutboxDeliveries.id eq deliveryId }) {
                it[status] = IncidentDeliveryStatus.DEAD_LETTER.wire
                it[leasedAt] = null
                it[leaseOwner] = null
                it[lastError] = error?.take(MAX_ERROR_LENGTH)
                it[updatedAt] = now
            }
        }
    }

    private fun markEventPublished(eventId: Int) {
        transaction {
            val now = clock.now()
            NativeIncidentOutboxEvents.update({ NativeIncidentOutboxEvents.id eq eventId }) {
                it[status] = IncidentOutboxStatus.PUBLISHED.wire
                it[publishedAt] = now
                it[leasedAt] = null
                it[leaseOwner] = null
                it[lastError] = null
                it[updatedAt] = now
            }
        }
    }

    private fun rescheduleEvent(eventId: Int, error: String) {
        transaction {
            val row = NativeIncidentOutboxEvents.selectAll().where { NativeIncidentOutboxEvents.id eq eventId }.single()
            val now = clock.now()
            val attempt = row[NativeIncidentOutboxEvents.attemptCount]
            NativeIncidentOutboxEvents.update({ NativeIncidentOutboxEvents.id eq eventId }) {
                it[status] = IncidentOutboxStatus.PENDING.wire
                it[availableAt] = now.plus(backoff(attempt))
                it[leasedAt] = null
                it[leaseOwner] = null
                it[lastError] = error.take(MAX_ERROR_LENGTH)
                it[updatedAt] = now
            }
        }
    }

    private fun markEventDead(eventId: Int, error: String) {
        transaction {
            val now = clock.now()
            NativeIncidentOutboxEvents.update({ NativeIncidentOutboxEvents.id eq eventId }) {
                it[status] = IncidentOutboxStatus.DEAD_LETTER.wire
                it[leasedAt] = null
                it[leaseOwner] = null
                it[lastError] = error.take(MAX_ERROR_LENGTH)
                it[updatedAt] = now
            }
        }
    }

    private fun ResultRow.toDomainEvent(): NativeIncidentDomainEvent =
        NativeIncidentDomainEvent(
            id = this[NativeIncidentOutboxEvents.id].value,
            resourceId = this[NativeIncidentOutboxEvents.resourceId].toString(),
            organizationId = this[NativeIncidentOutboxEvents.organizationId],
            incidentId = this[NativeIncidentOutboxEvents.incidentId],
            eventType = this[NativeIncidentOutboxEvents.eventType],
            aggregateVersion = this[NativeIncidentOutboxEvents.aggregateVersion],
            idempotencyKey = this[NativeIncidentOutboxEvents.idempotencyKey],
            payload = this[NativeIncidentOutboxEvents.payload],
            createdAt = this[NativeIncidentOutboxEvents.createdAt].toString(),
        )

    private fun backoff(attempt: Int) =
        min(MAX_BACKOFF_SECONDS, 1 shl min(attempt, MAX_BACKOFF_EXPONENT)).seconds

    private data class ClaimedDelivery(
        val id: Int,
        val deliveryKey: String,
        val attemptCount: Int,
        val deadLettered: Boolean,
    )

    private enum class DeliveryOutcome {
        DELIVERED,
        RETRY,
        DEAD_LETTER,
    }

    companion object {
        private const val DEFAULT_BATCH_SIZE = 50
        private const val MAX_BATCH_SIZE = 500
        private const val MAX_ATTEMPTS = 8
        private const val MAX_CONSUMER_NAME_LENGTH = 120
        private const val MAX_ERROR_LENGTH = 4_000
        private const val MAX_BACKOFF_SECONDS = 300
        private const val MAX_BACKOFF_EXPONENT = 8
        private const val ROLLOUT_DISABLED_MESSAGE = "Native incident rollout is disabled; delivery is deferred"
        private val LEASE_DURATION = 5.minutes
        private val ROLLOUT_DEFER_DURATION = 30.seconds
    }
}
