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

package com.moneat.events.services

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val FEEDBACK_UUID = "4f01ede1-5802-4ff1-81b7-f2fe9add31e5"

class FeedbackServiceTest {
    private lateinit var queryHelper: DashboardQueryHelper
    private lateinit var service: FeedbackService

    @BeforeTest
    fun setup() {
        queryHelper = mockk(relaxed = true)
        every { queryHelper.clickhouseDb } returns "test_db"
        every { queryHelper.normalizeUuid(any()) } returns FEEDBACK_UUID
        coEvery { queryHelper.getProjectRetentionDays(any()) } returns 30
        every {
            queryHelper.timestampRetentionClause(any(), any(), any())
        } returns "timestamp >= now() - INTERVAL 30 DAY"
        coEvery { queryHelper.getOrganizationRetentionDays(any()) } returns 30
        service = FeedbackService(queryHelper)
    }

    @Test
    fun `getProjectIdForFeedback accepts negative demo project ids`() = runBlocking {
        coEvery { queryHelper.executeProjectIdQuery(any(), any(), any()) } returns -1L
        assertEquals(-1L, service.getProjectIdForFeedback(FEEDBACK_UUID))
    }

    @Test
    fun `getProjectIdForFeedback rejects zero and null project ids`() = runBlocking {
        coEvery { queryHelper.executeProjectIdQuery(any(), any(), any()) } returns 0L
        assertNull(service.getProjectIdForFeedback(FEEDBACK_UUID))

        coEvery { queryHelper.executeProjectIdQuery(any(), any(), any()) } returns null
        assertNull(service.getProjectIdForFeedback(FEEDBACK_UUID))
    }

    @Test
    fun `getFeedbackDetail resolves a demo feedback row and maps the iso timestamp`() = runBlocking {
        coEvery { queryHelper.executeProjectIdQuery(any(), any(), any()) } returns -1L
        val row = buildJsonObject {
            put("feedback_id", FEEDBACK_UUID)
            put("message", "Checkout keeps spinning")
            put("contact_email", "user0@example.com")
            put("name", "Jordan Lee")
            put("url", "https://shop.acme.com/checkout")
            put("status", "unresolved")
            put("timestamp_iso", "2026-05-29T17:39:22.000Z")
            put("environment", "production")
            put("release", "1.3.0")
            put("platform", "android")
            put("sdk_name", "sentry.java.android")
            put("sdk_version", "7.14.0")
            put("source_type", "sentry")
            put("source_name", "Sentry-compatible SDK")
            put("source_event_name", "feedback")
            put("trace_id", "")
            put("span_id", "")
            put(
                "resource_attributes",
                buildJsonObject {
                    put("service.name", "checkout-api")
                }
            )
        }
        coEvery { queryHelper.executeJsonEachRowQuery(any(), any()) } returns listOf(row)

        val detail = service.getFeedbackDetail(FEEDBACK_UUID)
        assertEquals(FEEDBACK_UUID, detail?.feedbackId)
        assertEquals("2026-05-29T17:39:22.000Z", detail?.timestamp)
        assertEquals("unresolved", detail?.status)
        assertEquals("sentry", detail?.sourceType)
        assertEquals("Sentry-compatible SDK", detail?.sourceName)
        assertEquals("feedback", detail?.sourceEventName)
        assertEquals("", detail?.traceId)
        assertEquals("", detail?.spanId)
        assertEquals(mapOf("service.name" to "checkout-api"), detail?.resourceAttributes)
    }

    @Test
    fun `getFeedbackForServices scopes query to selected services`() = runBlocking {
        val querySlot = slot<String>()
        coEvery { queryHelper.executeJsonEachRowQuery(capture(querySlot), "Feedback list") } returns emptyList()

        val result =
            service.getFeedbackForServices(
                organizationId = 1,
                serviceIds = listOf(1L, 2L),
                status = "resolved"
            )

        assertEquals(emptyList(), result)
        assertEquals(true, querySlot.captured.contains("project_id IN (1, 2)"))
        assertEquals(true, querySlot.captured.contains("status = 'resolved'"))
    }

    @Test
    fun `getFeedback scopes project query through service scope`() = runBlocking {
        val querySlot = slot<String>()
        coEvery { queryHelper.executeJsonEachRowQuery(capture(querySlot), "Feedback list") } returns emptyList()

        val result = service.getFeedback(projectId = 3L, status = "archived")

        assertEquals(emptyList(), result)
        assertEquals(true, querySlot.captured.contains("project_id = 3"))
        assertEquals(true, querySlot.captured.contains("status = 'archived'"))
    }

    @Test
    fun `getFeedback maps list source metadata with Sentry defaults`() = runBlocking {
        val row = buildJsonObject {
            put("feedback_id", FEEDBACK_UUID)
            put("message", "Checkout keeps spinning")
            put("contact_email", "user0@example.com")
            put("name", "Jordan Lee")
            put("url", "https://shop.acme.com/checkout")
            put("status", "unresolved")
            put("created_at", "2026-05-29T17:39:22.000Z")
            put("environment", "production")
            put("release", "1.3.0")
            put("platform", "android")
            put("associated_event_id", "event-1")
            put("replay_id", "replay-1")
            put("trace_id", "00000000000000000000000000000001")
            put("span_id", "0000000000000001")
            put(
                "resource_attributes",
                buildJsonObject {
                    put("service.name", "checkout-api")
                }
            )
        }
        coEvery { queryHelper.executeJsonEachRowQuery(any(), "Feedback list") } returns listOf(row)

        val result = service.getFeedback(projectId = 3L)

        assertEquals(1, result.size)
        assertEquals("event-1", result.single().associatedEventId)
        assertEquals("replay-1", result.single().replayId)
        assertEquals("sentry", result.single().sourceType)
        assertEquals("Sentry-compatible SDK", result.single().sourceName)
        assertEquals("feedback", result.single().sourceEventName)
        assertEquals("00000000000000000000000000000001", result.single().traceId)
        assertEquals("0000000000000001", result.single().spanId)
        assertEquals(mapOf("service.name" to "checkout-api"), result.single().resourceAttributes)
    }
}
