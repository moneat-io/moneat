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

package com.moneat.analytics

import com.moneat.analytics.models.EnrichedAnalyticsEvent
import com.moneat.analytics.services.AnalyticsIngestionWorker
import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import com.moneat.testsupport.withClickHouseMockServer
import com.moneat.utils.ClickHouseSqlUtils
import io.lettuce.core.XAddArgs
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsIngestionWorkerTest {
    // ──── Mocks & Setup ────

    private val mockRedis = mockk<RedisCommands<String, String>>(relaxed = true)

    @BeforeTest
    fun setup() {
        mockkObject(RedisConfig)
        every { RedisConfig.sync() } returns mockRedis
        every { mockRedis.xadd(any<String>(), any<XAddArgs>(), any<Map<String, String>>()) } returns "1-0"
    }

    @AfterTest
    fun teardown() {
        unmockkObject(RedisConfig)
        ClickHouseClient.close()
    }

    // ──── Companion Metadata ────

    @Test
    fun `companion exposes queue and dlq keys`() {
        assertTrue(AnalyticsIngestionWorker.QUEUE_KEY.isNotBlank())
        assertTrue(AnalyticsIngestionWorker.DLQ_KEY.isNotBlank())
        assertEquals("moneat:analytics:queue", AnalyticsIngestionWorker.QUEUE_KEY)
        assertEquals("moneat:analytics:dlq", AnalyticsIngestionWorker.DLQ_KEY)
    }

    @Test
    fun `companion exposes realtime key prefix`() {
        assertTrue(AnalyticsIngestionWorker.REALTIME_KEY_PREFIX.startsWith("moneat:analytics:realtime:"))
    }

    @Test
    fun `escapeCH delegates to ClickHouseSqlUtils`() {
        val raw = "a'b\\c\n"
        assertEquals(ClickHouseSqlUtils.escapeSql(raw), AnalyticsIngestionWorker.escapeCH(raw))
    }

    // ──── Message Processing ────

    @Test
    fun `processMessage with invalid JSON does not throw`() {
        val worker = AnalyticsIngestionWorker(workerCount = 0)
        runBlocking {
            worker.processMessage(1, "{not valid json for EnrichedAnalyticsEvent")
        }
    }

    @Test
    fun `processMessage with empty string does not throw`() {
        val worker = AnalyticsIngestionWorker(workerCount = 0)
        runBlocking {
            worker.processMessage(2, "")
        }
    }

    @Test
    fun `processMessage inserts product analytics user and source columns`() = runBlocking {
        val queries = mutableListOf<String>()
        withClickHouseMockServer({ exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "", contentType = "text/plain")
        }) { _ ->
            val worker = AnalyticsIngestionWorker(workerCount = 0)
            val message = Json.encodeToString(
                EnrichedAnalyticsEvent(
                    projectId = 42L,
                    sessionId = "sess-1",
                    userId = "user-1",
                    eventName = "recording.started",
                    source = "server",
                    hostname = "",
                    pathname = "",
                    referrer = "",
                    referrerSource = "",
                    utmSource = "",
                    utmMedium = "",
                    utmCampaign = "",
                    utmTerm = "",
                    utmContent = "",
                    countryCode = "",
                    subdivision = "",
                    city = "",
                    browser = "",
                    browserVersion = "",
                    os = "",
                    osVersion = "",
                    deviceType = "",
                    screenWidth = 0,
                    props = mapOf("platform" to "ios"),
                    timestamp = 1_716_825_600_000,
                )
            )

            worker.processMessage(1, message)

            assertTrue(queries.any { it.contains("session_id, user_id, event_name, source") })
            assertTrue(queries.any { it.contains("'user-1'") && it.contains("'server'") })
        }
    }

    // ──── Lifecycle ────

    @Test
    fun `stop without start does not throw`() {
        val worker = AnalyticsIngestionWorker(workerCount = 0)
        worker.stop()
    }

    @Test
    fun `start and stop lifecycle with zero workers does not throw`() {
        val worker = AnalyticsIngestionWorker(workerCount = 0)
        worker.start()
        worker.stop()
    }
}
