// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.events

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.commands.DeclareIncidentCommand
import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandPolicy
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.commands.UpdateIncidentCommand
import com.moneat.enterprise.incidents.models.IncidentDeliveryStatus
import com.moneat.enterprise.incidents.models.IncidentOutboxStatus
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxDeliveries
import com.moneat.enterprise.incidents.models.NativeIncidentOutboxEvents
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class IncidentOutboxServiceTest {
    private lateinit var member: SeededMember
    private lateinit var commandService: IncidentCommandService
    private lateinit var clock: MutableClock

    @BeforeEach
    fun setUp() {
        IncidentTestDatabase.reset()
        member = IncidentTestDatabase.seedMember()
        commandService = IncidentCommandService(policy = IncidentCommandPolicy.allowForTests())
        clock = MutableClock(Clock.System.now().plus(1.seconds))
    }

    @AfterEach
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `retries failed delivery without duplicating a completed consumer effect`() = runBlocking {
        declare("outbox-retry")
        val consumer = RecordingConsumer(failuresRemaining = 1)
        val service = IncidentOutboxService(listOf(consumer), workerId = "test-worker", clock = clock)

        assertEquals(1, service.processBatch())
        assertEquals(1, consumer.attemptedKeys.size)
        transaction {
            assertEquals(
                IncidentOutboxStatus.PENDING.wire,
                NativeIncidentOutboxEvents.selectAll().single()[NativeIncidentOutboxEvents.status],
            )
        }

        clock.advance(10.seconds)
        assertEquals(1, service.processBatch())
        assertEquals(2, consumer.attemptedKeys.size)
        assertEquals(1, consumer.completedKeys.size)
        assertEquals(0, service.processBatch())
        assertEquals(2, consumer.attemptedKeys.size)
        transaction {
            assertEquals(
                IncidentDeliveryStatus.DELIVERED.wire,
                NativeIncidentOutboxDeliveries.selectAll().single()[NativeIncidentOutboxDeliveries.status],
            )
            assertEquals(
                IncidentOutboxStatus.PUBLISHED.wire,
                NativeIncidentOutboxEvents.selectAll().single()[NativeIncidentOutboxEvents.status],
            )
        }
    }

    @Test
    fun `recovers a stale processing lease after restart`() = runBlocking {
        declare("outbox-crash")
        transaction {
            val eventId = NativeIncidentOutboxEvents.selectAll().single()[NativeIncidentOutboxEvents.id]
            NativeIncidentOutboxEvents.update({ NativeIncidentOutboxEvents.id eq eventId }) {
                it[status] = IncidentOutboxStatus.PROCESSING.wire
                it[leasedAt] = clock.now().minus(10.minutes)
                it[leaseOwner] = "crashed-worker"
            }
        }
        val consumer = RecordingConsumer()
        val restarted = IncidentOutboxService(listOf(consumer), workerId = "restarted-worker", clock = clock)

        assertEquals(1, restarted.processBatch())
        assertEquals(listOf("${member.organizationId}:outbox-crash:recording"), consumer.completedKeys)
    }

    @Test
    fun `preserves aggregate order while an earlier version is retrying`() = runBlocking {
        val incident = declare("ordered-declare")
        commandService.execute(
            UpdateIncidentCommand(
                commandKey = "ordered-update",
                actor = actor(),
                incidentId = incident.incidentId,
                expectedVersion = 1,
                summary = "Investigation underway",
            ),
        )
        val consumer = RecordingConsumer(failuresRemaining = 1)
        val service = IncidentOutboxService(listOf(consumer), workerId = "ordering-worker", clock = clock)

        assertEquals(1, service.processBatch(limit = 2))
        assertEquals(listOf("INCIDENT_DECLARE"), consumer.attemptedEventTypes)

        clock.advance(10.seconds)
        assertEquals(2, service.processBatch(limit = 2))
        assertEquals(
            listOf("INCIDENT_DECLARE", "INCIDENT_DECLARE", "INCIDENT_UPDATE"),
            consumer.attemptedEventTypes,
        )
        assertEquals(listOf("INCIDENT_DECLARE", "INCIDENT_UPDATE"), consumer.completedEventTypes)
    }

    @Test
    fun `dead letter blocks later aggregate events until replay resets attempts`() = runBlocking {
        val incident = declare("dead-letter-declare")
        commandService.execute(
            UpdateIncidentCommand(
                commandKey = "dead-letter-update",
                actor = actor(),
                incidentId = incident.incidentId,
                expectedVersion = 1,
                summary = "Waiting behind the first event",
            ),
        )
        val consumer = RecordingConsumer(failuresRemaining = 8)
        val service = IncidentOutboxService(listOf(consumer), workerId = "dead-letter-worker", clock = clock)

        repeat(8) {
            assertEquals(1, service.processBatch(limit = 2))
            clock.advance(10.minutes)
        }
        val firstResourceId = transaction {
            val events =
                NativeIncidentOutboxEvents
                    .selectAll()
                    .orderBy(NativeIncidentOutboxEvents.aggregateVersion to SortOrder.ASC)
                    .toList()
            assertEquals(IncidentOutboxStatus.DEAD_LETTER.wire, events[0][NativeIncidentOutboxEvents.status])
            assertEquals(IncidentOutboxStatus.PENDING.wire, events[1][NativeIncidentOutboxEvents.status])
            events[0][NativeIncidentOutboxEvents.resourceId].toString()
        }
        assertEquals(0, service.processBatch(limit = 2))

        assertEquals(true, service.replay(firstResourceId))
        transaction {
            val firstEvent =
                NativeIncidentOutboxEvents
                    .selectAll()
                    .orderBy(NativeIncidentOutboxEvents.aggregateVersion to SortOrder.ASC)
                    .limit(1)
                    .single()
            assertEquals(0, firstEvent[NativeIncidentOutboxEvents.attemptCount])
            val delivery = NativeIncidentOutboxDeliveries.selectAll().single()
            assertEquals(0, delivery[NativeIncidentOutboxDeliveries.attemptCount])
        }
        assertEquals(2, service.processBatch(limit = 2))
        assertEquals(listOf("INCIDENT_DECLARE", "INCIDENT_UPDATE"), consumer.completedEventTypes)
    }

    private fun declare(key: String) =
        commandService.execute(
            DeclareIncidentCommand(
                commandKey = key,
                actor = actor(),
                title = "Database unavailable",
                description = null,
                severity = "SEV-1",
            ),
        )

    private fun actor() = IncidentCommandActor(member.organizationId, member.userId, "REST")

    private class RecordingConsumer(
        private var failuresRemaining: Int = 0,
    ) : NativeIncidentEventConsumer {
        override val name = "recording"
        val attemptedKeys = mutableListOf<String>()
        val completedKeys = mutableListOf<String>()
        val attemptedEventTypes = mutableListOf<String>()
        val completedEventTypes = mutableListOf<String>()

        override suspend fun consume(event: NativeIncidentDomainEvent, deliveryKey: String) {
            attemptedKeys += deliveryKey
            attemptedEventTypes += event.eventType
            if (deliveryKey !in completedKeys) {
                completedKeys += deliveryKey
                completedEventTypes += event.eventType
            }
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                error("consumer crashed after applying its visible effect")
            }
        }
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock {
        override fun now(): Instant = current

        fun advance(duration: kotlin.time.Duration) {
            current = current.plus(duration)
        }
    }
}
