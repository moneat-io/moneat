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

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SlackOutboundDeliveryStatus
import com.moneat.shared.models.SlackOutboundDeliveries
import com.moneat.shared.models.SlackOutboundOperation
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SlackOutboundDeliveryServiceTest {
    companion object {
        private var database: Database? = null
    }

    private val clock = MutableClock(Instant.parse("2026-08-25T00:00:00Z"))

    @BeforeTest
    fun setUp() {
        if (database == null) {
            database = Database.connect(
                url = "jdbc:h2:mem:moneat_slack_outbound;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = database
        TestDatabaseHelper.resetSchema(Organizations, SlackOutboundDeliveries)
        transaction {
            Organizations.insert {
                it[name] = "Outbound test"
                it[slug] = "outbound-test"
            }
        }
    }

    @Test
    fun `idempotent enqueue updates desired version without creating a duplicate`() {
        val service = SlackOutboundDeliveryService(clock = clock)
        val first = service.enqueue(request(version = 1))
        val second = service.enqueue(request(version = 2))

        assertEquals(first, second)
        val row = transaction { SlackOutboundDeliveries.selectAll().single() }
        assertEquals(2, row[SlackOutboundDeliveries.desiredVersion])
        assertEquals(SlackOutboundDeliveryStatus.PENDING.wire, row[SlackOutboundDeliveries.status])
    }

    @Test
    fun `delivery is recorded once and replay does not call sender twice`() = runBlocking {
        val service = SlackOutboundDeliveryService(clock = clock)
        val resourceId = service.enqueue(request())
        var sends = 0
        val sender = SlackOutboundSender {
            sends += 1
            SlackOutboundSendResult.Delivered(providerMessageId = "M1", providerMessageTs = "1.1")
        }

        assertTrue(service.process(resourceId, sender))
        assertFalse(service.process(resourceId, sender))
        assertEquals(1, sends)
        val row = transaction { SlackOutboundDeliveries.selectAll().single() }
        assertEquals(SlackOutboundDeliveryStatus.DELIVERED.wire, row[SlackOutboundDeliveries.status])
        assertEquals("M1", row[SlackOutboundDeliveries.providerMessageId])
        assertEquals(1, row[SlackOutboundDeliveries.deliveredVersion])
        assertNotNull(service.metrics().lastSuccessfulDelivery)
    }

    @Test
    fun `bounded retries eventually move an unavailable delivery to dead letter`() = runBlocking {
        val service = SlackOutboundDeliveryService(clock = clock, maxAttempts = 2)
        val resourceId = service.enqueue(request())
        val sender = SlackOutboundSender {
            SlackOutboundSendResult.Retry("Slack unavailable", retryAt = clock.now(), rateLimited = true)
        }

        assertTrue(service.process(resourceId, sender))
        assertTrue(service.process(resourceId, sender))
        assertTrue(service.process(resourceId, sender))
        val row = transaction { SlackOutboundDeliveries.selectAll().single() }
        assertEquals(SlackOutboundDeliveryStatus.DEAD_LETTER.wire, row[SlackOutboundDeliveries.status])
        assertEquals(1L, service.metrics().deadLetterCount)
        assertTrue(service.metrics().rateLimitedCount >= 1)
    }

    @Test
    fun `superseded sender result preserves durable supersession state`() = runBlocking {
        val service = SlackOutboundDeliveryService(clock = clock)
        val resourceId = service.enqueue(request())

        assertTrue(service.process(resourceId, SlackOutboundSender { SlackOutboundSendResult.Superseded }))
        val row = transaction { SlackOutboundDeliveries.selectAll().single() }
        assertEquals(SlackOutboundDeliveryStatus.SUPERSEDED.wire, row[SlackOutboundDeliveries.status])
        assertNotNull(row[SlackOutboundDeliveries.supersededAt])
    }

    @Test
    fun `reconciliation reschedules missing and edited messages`() {
        val service = SlackOutboundDeliveryService(clock = clock)
        val resourceId = service.enqueue(request())

        assertEquals(
            SlackOutboundDeliveryStatus.PENDING,
            service.reconcile(
                SlackOutboundReconciliationRequest(
                    resourceId = resourceId,
                    observation = SlackOutboundObservation.MISSING,
                    providerMessageId = "M1",
                ),
            ),
        )
        var row = transaction { SlackOutboundDeliveries.selectAll().single() }
        assertEquals(SlackOutboundDeliveryStatus.PENDING.wire, row[SlackOutboundDeliveries.status])
        assertTrue(row[SlackOutboundDeliveries.lastError]!!.contains("missing"))

        assertEquals(
            SlackOutboundDeliveryStatus.DELIVERED,
            service.reconcile(
                SlackOutboundReconciliationRequest(
                    resourceId = resourceId,
                    observation = SlackOutboundObservation.PRESENT,
                    observedVersion = 1,
                    providerMessageId = "M1",
                    providerMessageTs = "1.1",
                ),
            ),
        )
        row = transaction { SlackOutboundDeliveries.selectAll().single() }
        assertEquals(SlackOutboundDeliveryStatus.DELIVERED.wire, row[SlackOutboundDeliveries.status])
        assertEquals("M1", row[SlackOutboundDeliveries.providerMessageId])
    }

    @Test
    fun `reconciliation handles stale observations, deletions, supersession, and unknown resources`() {
        val service = SlackOutboundDeliveryService(clock = clock)
        val resourceId = service.enqueue(request(version = 2))

        assertEquals(
            SlackOutboundDeliveryStatus.PENDING,
            service.reconcile(
                SlackOutboundReconciliationRequest(
                    resourceId = resourceId,
                    observation = SlackOutboundObservation.PRESENT,
                    observedVersion = 1,
                ),
            ),
        )
        assertEquals(
            SlackOutboundDeliveryStatus.PENDING,
            service.reconcile(
                SlackOutboundReconciliationRequest(
                    resourceId = resourceId,
                    observation = SlackOutboundObservation.EDITED,
                ),
            ),
        )
        assertEquals(
            SlackOutboundDeliveryStatus.PENDING,
            service.reconcile(
                SlackOutboundReconciliationRequest(
                    resourceId = resourceId,
                    observation = SlackOutboundObservation.DELETED,
                ),
            ),
        )
        assertEquals(
            SlackOutboundDeliveryStatus.SUPERSEDED,
            service.reconcile(
                SlackOutboundReconciliationRequest(
                    resourceId = resourceId,
                    observation = SlackOutboundObservation.SUPERSEDED,
                ),
            ),
        )
        assertEquals(
            null,
            service.reconcile(
                SlackOutboundReconciliationRequest(
                    resourceId = "00000000-0000-0000-0000-000000000000",
                    observation = SlackOutboundObservation.PRESENT,
                ),
            ),
        )
    }

    @Test
    fun `failed sender result is retried and records failure metrics`() = runBlocking {
        val service = SlackOutboundDeliveryService(clock = clock, maxAttempts = 2)
        val resourceId = service.enqueue(request())

        assertTrue(
            service.process(
                resourceId,
                SlackOutboundSender { SlackOutboundSendResult.Failed("provider rejected message") },
            ),
        )
        val row = transaction { SlackOutboundDeliveries.selectAll().single() }
        assertEquals(SlackOutboundDeliveryStatus.RETRY.wire, row[SlackOutboundDeliveries.status])
        assertTrue(service.metrics().failureCount >= 1)
    }

    @Test
    fun `unsupported outbound operation fails before workspace access`() = runBlocking {
        val result = SlackService().sendOutboundDelivery(
            SlackOutboundDelivery(
                resourceId = "delivery",
                organizationId = 1,
                teamId = "T-outbound",
                channelId = "C-outbound",
                operation = SlackOutboundOperation.CHANNEL_CREATE,
                idempotencyKey = "channel:create",
                payload = "{}",
                desiredVersion = 1,
                attemptCount = 1,
            ),
        )

        assertIs<SlackOutboundSendResult.Failed>(result)
    }

    private fun request(version: Int = 1): SlackOutboundEnqueueRequest =
        SlackOutboundEnqueueRequest(
            organizationId = 1,
            teamId = "T-outbound",
            channelId = "C-outbound",
            operation = SlackOutboundOperation.MESSAGE,
            idempotencyKey = "incident:1:message",
            payload = "{\"text\":\"hello-$version\"}",
            desiredVersion = version,
        )

    private class MutableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
