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

import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueClient
import com.moneat.shared.models.SlackInboundDeliveries
import com.moneat.shared.models.SlackInboundDeliveryStatus
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.eq

class SlackInboundGatewayTest {
    companion object {
        private const val SECRET = "gateway-test-secret"
        private var database: Database? = null
    }

    @BeforeTest
    fun setUp() {
        if (database == null) {
            database = Database.connect(
                url = "jdbc:h2:mem:moneat_slack_inbound;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = database
        TestDatabaseHelper.resetSchema(SlackInboundDeliveries)
        mockkObject(IngestionQueueClient)
        every { IngestionQueueClient.enqueue(any(), any(), any()) } returns "1-0"
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(IngestionQueueClient)
    }

    @Test
    fun `accepts a fresh signed request with constant time comparison`() {
        val body = "command=%2Fmoneat&team_id=T1&user_id=U1"
        val timestamp = "1700000000"
        val signature = signature(SECRET, timestamp, body)
        assertTrue(SlackInboundGateway.verifySlackSignature(SECRET, timestamp, signature, body))
    }

    @Test
    fun `rejects stale, malformed, and tampered signatures`() {
        val body = "payload={}"
        val timestamp = "1700000000"
        val signature = signature(SECRET, timestamp, body)
        assertFalse(
            SlackInboundGateway.verifySlackSignature(
                SECRET,
                timestamp,
                signature,
                body,
                timestamp.toLong() + 301,
            ),
        )
        assertFalse(SlackInboundGateway.verifySlackSignature(SECRET, timestamp, "v1=invalid", body))
        assertFalse(SlackInboundGateway.verifySlackSignature(SECRET, timestamp, signature, "$body&tampered=true"))
        assertFalse(SlackInboundGateway.verifySlackSignature(headersOf(), body, timestamp.toLong() + 1))
    }

    @Test
    fun `rejects oversized, unsigned, and malformed requests before persistence`() {
        val gateway = SlackInboundGateway(signingSecret = SECRET)
        assertFailsWith<SlackInboundRequestException> {
            gateway.accept(headersOf(), "x".repeat(1_000_001), SlackInboundRequestType.COMMAND)
        }.also { assertEquals(SlackInboundRequestRejection.INVALID_BODY, it.reason) }
        assertFailsWith<SlackInboundRequestException> {
            gateway.accept(headersOf(), "{}", SlackInboundRequestType.COMMAND)
        }.also { assertEquals(SlackInboundRequestRejection.INVALID_SIGNATURE, it.reason) }
        val malformed = "{not-json"
        assertFailsWith<SlackInboundRequestException> {
            gateway.accept(signedHeaders(SECRET, malformed), malformed, SlackInboundRequestType.COMMAND)
        }.also { assertEquals(SlackInboundRequestRejection.INVALID_BODY, it.reason) }
    }

    @Test
    fun `returns a signed URL verification challenge without queueing`() {
        val body = """{"type":"url_verification","challenge":"challenge-1","team_id":"T1"}"""
        val acceptance = SlackInboundGateway(signingSecret = SECRET).accept(
            signedHeaders(SECRET, body),
            body,
            SlackInboundRequestType.EVENT,
        )

        assertEquals("challenge-1", acceptance.challenge)
        assertEquals(null, acceptance.deliveryId)
        verify(exactly = 0) { IngestionQueueClient.enqueue(any(), any(), any()) }
    }

    @Test
    fun `persists complete form context and deduplicates Slack retries`() {
        val body = formBody()
        val gateway = SlackInboundGateway(signingSecret = SECRET)

        val accepted = gateway.accept(signedHeaders(SECRET, body), body, SlackInboundRequestType.INTERACTION)
        val duplicate = gateway.accept(signedHeaders(SECRET, body), body, SlackInboundRequestType.INTERACTION)
        val deliveryId = accepted.deliveryId

        assertNotNull(deliveryId)
        assertFalse(accepted.duplicate)
        assertTrue(duplicate.duplicate)
        val row = transaction {
            SlackInboundDeliveries.selectAll().single()
        }
        assertEquals("QUEUED", row[SlackInboundDeliveries.status])
        assertEquals("T1", row[SlackInboundDeliveries.teamId])
        assertEquals("E1", row[SlackInboundDeliveries.enterpriseId])
        assertEquals("C1", row[SlackInboundDeliveries.channelId])
        assertEquals("U1", row[SlackInboundDeliveries.userId])
        assertEquals("1710000000.000001", row[SlackInboundDeliveries.messageTs])
        assertEquals("1710000000.000000", row[SlackInboundDeliveries.threadTs])
        assertEquals("V1", row[SlackInboundDeliveries.viewId])
        verify(exactly = 1) {
            IngestionQueueClient.enqueue(IngestionPipeline.SLACK_INBOUND, "slack-inbound", deliveryId)
        }
    }

    @Test
    fun `requeues retry state and marks durable delivery processed`() {
        val body = formBody()
        val gateway = SlackInboundGateway(signingSecret = SECRET)
        val acceptance = gateway.accept(signedHeaders(SECRET, body), body, SlackInboundRequestType.COMMAND)
        val deliveryId = requireNotNull(acceptance.deliveryId)
        transaction {
            SlackInboundDeliveries.update({ SlackInboundDeliveries.resourceId eq kotlin.uuid.Uuid.parse(deliveryId) }) {
                it[status] = SlackInboundDeliveryStatus.RETRY.wire
            }
        }

        val retried = gateway.accept(signedHeaders(SECRET, body), body, SlackInboundRequestType.COMMAND)
        gateway.process(deliveryId)

        assertFalse(retried.duplicate)
        val row = transaction { SlackInboundDeliveries.selectAll().single() }
        assertEquals(SlackInboundDeliveryStatus.PROCESSED.wire, row[SlackInboundDeliveries.status])
        assertEquals(1, row[SlackInboundDeliveries.attemptCount])
        verify(exactly = 2) { IngestionQueueClient.enqueue(any(), any(), any()) }
        gateway.process(deliveryId)
    }

    @Test
    fun `records queue failure for retry`() {
        every { IngestionQueueClient.enqueue(any(), any(), any()) } throws IllegalStateException("redis down")
        val body = formBody()
        val gateway = SlackInboundGateway(signingSecret = SECRET)

        assertFailsWith<SlackInboundRequestException> {
            gateway.accept(signedHeaders(SECRET, body), body, SlackInboundRequestType.COMMAND)
        }.also { assertEquals(SlackInboundRequestRejection.QUEUE_UNAVAILABLE, it.reason) }
        val row = transaction { SlackInboundDeliveries.selectAll().single() }
        assertEquals(SlackInboundDeliveryStatus.RETRY.wire, row[SlackInboundDeliveries.status])
        assertEquals("redis down", row[SlackInboundDeliveries.lastError])
    }

    @Test
    fun `retains nested event and interaction context from JSON payload`() {
        val payload = """
            {"type":"block_actions","event_id":"Ev1","team":{"id":"T2"},
             "enterprise":{"id":"E2"},"channel":{"id":"C2"},"user":{"id":"U2"},
             "container":{"message_ts":"2.1","thread_ts":"2.0"},
             "view":{"id":"V2"},"trigger_id":"TR2"}
        """.trimIndent()
        val body = "payload=" + URLEncoder.encode(payload, Charsets.UTF_8)
        val gateway = SlackInboundGateway(signingSecret = SECRET)

        val acceptance = gateway.accept(signedHeaders(SECRET, body), body, SlackInboundRequestType.INTERACTION)
        assertNotNull(acceptance.deliveryId)
        val row = transaction { SlackInboundDeliveries.selectAll().single() }
        assertEquals("T2", row[SlackInboundDeliveries.teamId])
        assertEquals("E2", row[SlackInboundDeliveries.enterpriseId])
        assertEquals("C2", row[SlackInboundDeliveries.channelId])
        assertEquals("U2", row[SlackInboundDeliveries.userId])
        assertEquals("2.1", row[SlackInboundDeliveries.messageTs])
        assertEquals("2.0", row[SlackInboundDeliveries.threadTs])
        assertEquals("V2", row[SlackInboundDeliveries.viewId])
    }

    private fun formBody(): String =
        "team_id=T1&enterprise_id=E1&channel_id=C1&user_id=U1" +
            "&message_ts=1710000000.000001&thread_ts=1710000000.000000&view_id=V1&trigger_id=TR1"

    private fun signedHeaders(secret: String, body: String): io.ktor.http.Headers {
        val timestamp = (System.currentTimeMillis() / 1_000L).toString()
        return headersOf(
            "X-Slack-Request-Timestamp" to listOf(timestamp),
            "X-Slack-Signature" to listOf(signature(secret, timestamp, body)),
        )
    }

    private fun signature(secret: String, timestamp: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val digest = mac.doFinal("v0:$timestamp:$body".toByteArray())
        return "v0=" + digest.joinToString("") { "%02x".format(it) }
    }
}
