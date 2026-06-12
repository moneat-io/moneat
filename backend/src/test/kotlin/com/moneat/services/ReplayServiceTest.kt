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

package com.moneat.services

import com.moneat.analytics.services.GeoIpService
import com.moneat.billing.services.PricingTierService
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.events.services.ReplayService
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.OrgProjectTestFixtures
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import com.moneat.testsupport.withClickHouseMockServer
import com.sun.net.httpserver.HttpExchange
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.msgpack.core.MessagePack
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayServiceTest {

    companion object {
        private const val TEXT_PLAIN = "text/plain"
        private const val REPLAY_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        private val GEO_TEST_IP = listOf(203, 0, 113, 10).joinToString(".")
        private var db: Database? = null
    }

    private val retentionPolicyService = mockk<RetentionPolicyService>()
    private val pricingTierService = mockk<PricingTierService>()
    private val geoIpService = mockk<GeoIpService>()
    private lateinit var queryHelper: DashboardQueryHelper
    private lateinit var service: ReplayService

    private data class ReplayTimelineRows(
        val detailRow: String,
        val errorRow: String,
        val txRow: String,
        val spanRow: String,
        val breadcrumbRow: String
    )

    private data class TimelineQueryResponse(
        val matches: (String) -> Boolean,
        val body: String
    )

    @BeforeTest
    fun setup() {
        db = OrgProjectTestFixtures.connectResetAndSeedDefaultOrgProject(db, "moneat_replay_service")

        coEvery { retentionPolicyService.getRetentionDaysForProject(any()) } returns 30
        coEvery { retentionPolicyService.getRetentionDaysForOrganization(any()) } returns 30
        every { geoIpService.resolve(any()) } returns GeoIpService.GeoResult()
        queryHelper = DashboardQueryHelper(retentionPolicyService, pricingTierService)
        service = ReplayService(queryHelper, geoIpService = geoIpService)
    }

    private fun respondReplayTimelineQuery(exchange: HttpExchange, rows: ReplayTimelineRows) {
        val query = exchange.requestBodyText()
        val body =
            replayTimelineResponses(rows)
                .firstOrNull { it.matches(query) }
                ?.body
                .orEmpty()
        exchange.respond(200, body, TEXT_PLAIN)
    }

    private fun replayTimelineResponses(rows: ReplayTimelineRows): List<TimelineQueryResponse> =
        listOf(
            TimelineQueryResponse({ query -> query.contains("countDistinct(replay_id)") }, """{"count":0}"""),
            TimelineQueryResponse({ query -> query.contains("SELECT contexts, breadcrumbs") }, ""),
            TimelineQueryResponse(
                { query -> query.contains("toString(event_id) as event_id") && query.contains("breadcrumbs") },
                rows.breadcrumbRow
            ),
            TimelineQueryResponse({ query -> query.contains("SELECT toInt64(project_id)") }, """{"project_id":1}"""),
            TimelineQueryResponse({ query -> query.contains("GROUP BY e.replay_id") }, rows.detailRow),
            TimelineQueryResponse(
                { query -> query.contains("event_type = 'error'") && query.contains("IN (") },
                rows.errorRow
            ),
            TimelineQueryResponse(
                { query -> query.contains("event_type = 'transaction'") && query.contains("trace_id") },
                rows.txRow
            ),
            TimelineQueryResponse(
                { query -> query.contains("apm_spans") && query.contains("trace_id_hex IN") },
                rows.spanRow
            ),
            TimelineQueryResponse({ query -> query.contains("event_type = 'error'") }, ""),
            TimelineQueryResponse({ query -> query.contains("event_type = 'transaction'") }, ""),
            TimelineQueryResponse({ query -> query.contains("apm_spans") }, "")
        )

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }

    private fun recordingDataRow(recordingData: String): String =
        """{"recording_data":${jsonString(recordingData)}}"""

    private fun defaultProjectResourceId(): String =
        ProjectIdResolver().resourceIdFor(1) ?: ""

    private fun encodedMobileReplaySegment(): String {
        val replayEventPayload = """{"segment_id":7}""".toByteArray()
        val recordingPayload =
            """{"segment_id":7}[{"type":5,"timestamp":1000,"data":{"tag":"breadcrumb","payload":{"message":"tap"}}}]"""
                .toByteArray()
        val videoPayload = byteArrayOf(0, 0, 0, 24) + "ftyp".toByteArray() + byteArrayOf(0, 0, 0, 0)
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(3)
        packer.packString("replay_event")
        packer.packBinaryHeader(replayEventPayload.size)
        packer.writePayload(replayEventPayload)
        packer.packString("replay_recording")
        packer.packBinaryHeader(recordingPayload.size)
        packer.writePayload(recordingPayload)
        packer.packString("replay_video")
        packer.packBinaryHeader(videoPayload.size)
        packer.writePayload(videoPayload)
        val bytes = packer.toByteArray()
        packer.close()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun encodedMobileMetadataOnlySegment(): String {
        val replayEventPayload = """{"segment_id":8}""".toByteArray()
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packString("replay_event")
        packer.packBinaryHeader(replayEventPayload.size)
        packer.writePayload(replayEventPayload)
        val bytes = packer.toByteArray()
        packer.close()
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun `getProjectIdForReplay returns project id for valid replay`() = runBlocking {
        val replayId = "01234567-89ab-cdef-0123-456789abcdef"
        withClickHouseMockServer({ exchange ->
            exchange.respond(200, """{"project_id":42}""", TEXT_PLAIN)
        }) {
            val result = service.getProjectIdForReplay(replayId)
            assertEquals(42L, result)
        }
    }

    @Test
    fun `getProjectIdForReplay returns null for invalid uuid`() = runBlocking {
        val result = service.getProjectIdForReplay("not-a-uuid")
        assertNull(result)
    }

    @Test
    fun `getProjectIdForReplay normalizes compact uuid`() = runBlocking {
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        withClickHouseMockServer({ exchange ->
            queries += exchange.requestBodyText()
            exchange.respond(200, """{"project_id":10}""", TEXT_PLAIN)
        }) {
            val result = service.getProjectIdForReplay("0123456789abcdef0123456789abcdef")
            assertEquals(10L, result)
            assertTrue(queries.any { it.contains("01234567-89ab-cdef-0123-456789abcdef") })
        }
    }

    @Test
    fun `getReplays returns paginated replay list`() = runBlocking {
        val replayRow = """
{"replay_id":"$REPLAY_UUID","project_id":1,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:05:00.000Z","started_ms":"1767225600000","finished_ms":"1767225900000","duration_ms":"300000","urls":["https://app.example.com"],"error_count":2,"user_id":"u-1","user_email":"test@test.com","user_username":"tester","browser_name":"Chrome","browser_version":"120","os_name":"macOS","os_version":"14","activity":8}
        """.trimIndent()
        val contextRow = """
{"project_id":1,"ts_ms":"1767225660000","user_id":"u-1","contexts":"{}","breadcrumbs":"[{\"timestamp\":\"2026-01-01T00:01:00.000Z\",\"category\":\"ui.click\",\"message\":\"rage click x3\"}]"}
        """.trimIndent()
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            if (query.contains("toUnixTimestamp64Milli(timestamp) as ts_ms")) {
                exchange.respond(200, contextRow, TEXT_PLAIN)
            } else {
                exchange.respond(200, replayRow, TEXT_PLAIN)
            }
        }) {
            val result = service.getReplays(projectId = 1, page = 1, limit = 25, period = "7d")
            assertEquals(1, result.size)
            assertEquals(REPLAY_UUID, result[0].replayId)
            assertEquals(defaultProjectResourceId(), result[0].projectId)
            assertEquals(300000.0, result[0].durationMs)
            assertEquals(2, result[0].errorCount)
            assertEquals("Chrome", result[0].browserName)
            assertEquals("macOS", result[0].osName)
            assertEquals(8, result[0].activity)
            assertEquals("https://app.example.com", result[0].entryUrl)
            assertTrue("error" in result[0].signals)
            assertTrue("rage_click" in result[0].signals)
        }
    }

    @Test
    fun `getReplaysForServices scopes list and fallback error count by row service id`() = runBlocking {
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        val replayRow = """
{"replay_id":"$REPLAY_UUID","project_id":2,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:05:00.000Z","started_ms":"1767225600000","finished_ms":"1767225900000","duration_ms":"300000","urls":["https://app.example.com"],"error_count":0,"user_id":"u-1","user_email":"test@test.com","user_username":"tester","browser_name":"Chrome","browser_version":"120","os_name":"macOS","os_version":"14","activity":8}
        """.trimIndent()
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            queries += query
            when {
                query.contains("countDistinct(event_id)") ->
                    exchange.respond(200, """{"count":3}""", TEXT_PLAIN)

                else ->
                    exchange.respond(200, replayRow, TEXT_PLAIN)
            }
        }) {
            val result =
                service.getReplaysForServices(
                    organizationId = 1,
                    serviceIds = listOf(1L, 2L),
                    environment = "production"
                )

            assertEquals(1, result.size)
            assertEquals("", result.first().projectId)
            assertEquals(3, result.first().errorCount)
            assertTrue("error" in result.first().signals)
            assertTrue(queries.any { it.contains("project_id IN (1, 2)") })
            assertTrue(queries.any { it.contains("project_id = 2") })
            assertTrue(queries.any { it.contains("environment = 'production'") })
        }
    }

    @Test
    fun `getReplays uses recording window and native mobile metadata`() = runBlocking {
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        val replayRow = """
{"replay_id":"$REPLAY_UUID","project_id":1,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:02:00.000Z","started_ms":"1767225600000","finished_ms":"1767225720000","duration_ms":"0","recording_segment_count":2,"urls":[],"error_count":0,"user_id":"u-1","user_email":"","user_username":"","browser_name":"","browser_version":"","os_name":"","os_version":"","platform":"android","sdk_name":"sentry.java.android","device_name":"","device_family":"","activity":0}
        """.trimIndent()

        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            queries += query
            when {
                query.contains("countDistinct(event_id)") -> {
                    assertTrue(query.contains("timestamp <= fromUnixTimestamp64Milli(1767225720000)"))
                    exchange.respond(200, """{"count":1}""", TEXT_PLAIN)
                }

                query.contains("toUnixTimestamp64Milli(timestamp) as ts_ms") ->
                    exchange.respond(200, "", TEXT_PLAIN)

                else ->
                    exchange.respond(200, replayRow, TEXT_PLAIN)
            }
        }) {
            val result = service.getReplays(projectId = 1, page = 1, limit = 25, period = "7d")

            assertEquals(1, result.size)
            val replay = result.first()
            assertEquals(10000.0, replay.durationMs)
            assertEquals(1, replay.errorCount)
            assertEquals("Android", replay.osName)
            assertTrue("error" in replay.signals)
            assertTrue("dead_click" !in replay.signals)
            assertTrue(queries.any { it.contains("replay_segments") })
            assertTrue(queries.any { it.contains("max(greatest(e.timestamp") })
        }
    }

    @Test
    fun `getReplays returns empty list on error`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.respond(500, "Internal Server Error", TEXT_PLAIN)
        }) {
            val result = service.getReplays(projectId = 1)
            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `getReplay returns replay detail`() = runBlocking {
        val replayId = REPLAY_UUID
        every { geoIpService.resolve(GEO_TEST_IP) } returns GeoIpService.GeoResult(
            countryCode = "IT",
            city = "Milan"
        )
        val detailRow = """
{"replay_id":"$replayId","project_id":1,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:05:00.000Z","started_ms":"1767225600000","finished_ms":"1767225900000","duration_ms":"300000","urls":["https://app.example.com"],"error_ids":["err-1"],"trace_ids":["trace-1"],"segment_count":5,"environment":"prod","release":"1.0.0","platform":"javascript","user_id":"u-1","user_email":"test@test.com","user_username":"tester","user_ip_address":"$GEO_TEST_IP","browser_name":"Chrome","browser_version":"120","os_name":"macOS","os_version":"14","activity":8,"tags":"{}"}
        """.trimIndent()
        val contextRow = """
{"contexts":"{\"device\":{\"screen_width_pixels\":1512,\"screen_height_pixels\":856}}","breadcrumbs":"[{\"timestamp\":\"2026-01-01T00:00:10.000Z\",\"category\":\"network.event\",\"data\":{\"network_type\":\"4g\"}},{\"timestamp\":\"2026-01-01T00:00:11.000Z\",\"category\":\"ui.click\",\"message\":\"rage click x3\"}]"}
        """.trimIndent()
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("countDistinct(replay_id)") ->
                    exchange.respond(200, """{"count":14}""", TEXT_PLAIN)
                query.contains("SELECT contexts, breadcrumbs") ->
                    exchange.respond(200, contextRow, TEXT_PLAIN)
                query.contains("SELECT toInt64(project_id)") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                query.contains("GROUP BY e.replay_id") ->
                    exchange.respond(200, detailRow, TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplay(replayId)
            assertNotNull(result)
            assertEquals(replayId, result.replayId)
            assertEquals(defaultProjectResourceId(), result.projectId)
            assertEquals(300000.0, result.durationMs)
            assertEquals(5, result.segmentCount)
            assertEquals("prod", result.environment)
            assertEquals("javascript", result.platform)
            assertEquals("Chrome", result.browserName)
            assertEquals("macOS", result.osName)
            assertEquals("https://app.example.com", result.entryUrl)
            assertEquals(GEO_TEST_IP, result.ipAddress)
            assertEquals("Milan, IT", result.geo)
            assertEquals("1512 x 856", result.viewport)
            assertEquals("4g", result.connection)
            assertEquals(14, result.userSessionCount)
            assertTrue("error" in result.signals)
            assertTrue("rage_click" in result.signals)
        }
    }

    @Test
    fun `getReplay falls back to window errors and subdivision geo`() = runBlocking {
        val replayId = REPLAY_UUID
        every { geoIpService.resolve(GEO_TEST_IP) } returns GeoIpService.GeoResult(
            countryCode = "US",
            subdivision = "CA"
        )
        val detailRow = """
{"replay_id":"$replayId","project_id":1,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:05:00.000Z","started_ms":"1767225600000","finished_ms":"1767225900000","duration_ms":"300000","urls":["","https://app.example.com/fallback"],"error_ids":[],"trace_ids":[],"segment_count":1,"environment":"","release":"","platform":"javascript","user_id":"u-1","user_email":"","user_username":"","user_ip_address":"$GEO_TEST_IP","browser_name":"","browser_version":"","os_name":"","os_version":"","activity":0,"tags":"not-json"}
        """.trimIndent()
        val errorIdRows = """
{"event_id":"11111111-2222-3333-4444-555555555555"}
{"event_id":"22222222-3333-4444-5555-666666666666"}
        """.trimIndent()
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("SELECT toInt64(project_id)") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                query.contains("GROUP BY e.replay_id") ->
                    exchange.respond(200, detailRow, TEXT_PLAIN)
                query.contains("SELECT toString(event_id) as event_id") ->
                    exchange.respond(200, errorIdRows, TEXT_PLAIN)
                query.contains("countDistinct(event_id)") ->
                    exchange.respond(200, """{"count":2}""", TEXT_PLAIN)
                query.contains("SELECT contexts, breadcrumbs") ->
                    exchange.respond(200, "", TEXT_PLAIN)
                query.contains("countDistinct(replay_id)") ->
                    exchange.respond(200, """{"count":0}""", TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplay(replayId)
            assertNotNull(result)
            assertEquals("https://app.example.com/fallback", result.entryUrl)
            assertEquals(2, result.errorCount)
            assertEquals(2, result.errorIds.size)
            assertEquals(GEO_TEST_IP, result.ipAddress)
            assertEquals("CA, US", result.geo)
            assertTrue("error" in result.signals)
            assertTrue("dead_click" in result.signals)
            assertTrue(result.tags.isEmpty())
            assertEquals(0, result.userSessionCount)
        }
    }

    @Test
    fun `getReplay formats country only geo when locality is unavailable`() = runBlocking {
        val replayId = REPLAY_UUID
        every { geoIpService.resolve(GEO_TEST_IP) } returns GeoIpService.GeoResult(countryCode = "FR")
        val detailRow = """
{"replay_id":"$replayId","project_id":1,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:05:00.000Z","started_ms":"1767225600000","finished_ms":"1767225900000","duration_ms":"300000","urls":["https://app.example.com"],"error_ids":[],"trace_ids":[],"segment_count":1,"environment":"","release":"","platform":"javascript","user_id":"u-1","user_email":"","user_username":"","user_ip_address":"$GEO_TEST_IP","browser_name":"","browser_version":"","os_name":"","os_version":"","activity":8,"tags":"{}"}
        """.trimIndent()

        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("SELECT toInt64(project_id)") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                query.contains("GROUP BY e.replay_id") ->
                    exchange.respond(200, detailRow, TEXT_PLAIN)
                query.contains("countDistinct(event_id)") ->
                    exchange.respond(200, """{"count":0}""", TEXT_PLAIN)
                query.contains("SELECT contexts, breadcrumbs") ->
                    exchange.respond(200, "", TEXT_PLAIN)
                query.contains("countDistinct(replay_id)") ->
                    exchange.respond(200, """{"count":0}""", TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplay(replayId)
            assertNotNull(result)
            assertEquals("FR", result.geo)
        }
    }

    @Test
    fun `getReplay returns null for invalid uuid`() = runBlocking {
        assertNull(service.getReplay("bad-id"))
    }

    @Test
    fun `getReplayTimeline returns timeline with events`() = runBlocking {
        val replayId = REPLAY_UUID
        val detailRow = """
{"replay_id":"$replayId","project_id":1,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:05:00.000Z","started_ms":"1767225600000","finished_ms":"1767225900000","duration_ms":"300000","urls":["https://app.example.com"],"error_ids":["11111111-2222-3333-4444-555555555555"],"trace_ids":["trace-aaa"],"segment_count":5,"environment":"prod","release":"1.0.0","platform":"javascript","user_id":"u-1","user_email":"test@test.com","user_username":"tester","browser_name":"Chrome","browser_version":"120","os_name":"macOS","os_version":"14","activity":8,"tags":"{}"}
        """.trimIndent()

        val errorRow = """
{"event_id":"11111111-2222-3333-4444-555555555555","timestamp":"2026-01-01T00:01:00.000Z","ts_ms":"1767225660000","message":"NullPointerException","level":"error","issue_id":"issue-1","exception_type":"NullPointerException","exception_value":"null ref"}
        """.trimIndent()
        val txRow = """
{"event_id":"22222222-3333-4444-5555-666666666666","timestamp":"2026-01-01T00:02:00.000Z","ts_ms":"1767225720000","transaction_name":"GET /api","duration_ms":"150.0","transaction_op":"http.server","contexts":"{}","http_status_code":"503"}
        """.trimIndent()
        val spanRow = """
{"span_id":"span-1","trace_id":"trace-aaa","transaction_id":"22222222-3333-4444-5555-666666666666","description":"GET /health","op":"http.client","start_ts_ms":"1767225720500","duration_ms":"50","http_status_code":"200"}
        """.trimIndent()
        val breadcrumbs = """
[
  {"timestamp":"2026-01-01T00:00:05.000Z","category":"device.battery","data":{"action":"SCREEN_OFF"}},
  {"timestamp":1767225630.0,"category":"ui.lifecycle","data":{"screen":"Checkout","state":"active"}},
  {"timestamp":"2026-01-01T00:00:32.000Z","category":"navigation.screen","data":{"from":"Cart","to":"Checkout"}},
  {"timestamp":"2026-01-01T00:00:33.000Z","category":"network.event","data":{"method":"POST","url":"/api/pay","status_code":"502"}},
  {"timestamp":"2026-01-01T00:00:34.000Z","type":"custom-action","data":{"type":"tap"}},
  {"timestamp":"2026-01-01T00:00:35.000Z","category":"Logcat","message":"Client request failed: 401 Unauthorized"},
  {"timestamp":"2026-01-01T00:00:36.000Z","category":"ui.click","message":"rage click x3"}
]
        """.trimIndent()
        val breadcrumbRow = """
{"event_id":"33333333-4444-5555-6666-777777777777","breadcrumbs":${jsonString(breadcrumbs)}}
        """.trimIndent()
        val rows = ReplayTimelineRows(
            detailRow = detailRow,
            errorRow = errorRow,
            txRow = txRow,
            spanRow = spanRow,
            breadcrumbRow = breadcrumbRow
        )

        withClickHouseMockServer({ exchange -> respondReplayTimelineQuery(exchange, rows) }) {
            val result = service.getReplayTimeline(replayId)
            assertTrue(result.replayStartMs > 0)
            assertTrue(result.items.isNotEmpty())
            val errorItem = result.items.find { it.type == "error" }
            assertNotNull(errorItem)
            assertEquals("NullPointerException", errorItem.title)
            val txItem = result.items.find { it.type == "transaction" }
            assertNotNull(txItem)
            assertEquals("GET /api", txItem.title)
            assertEquals(503, txItem.statusCode)
            val spanItem = result.items.find { it.traceId == "trace-aaa" && it.title == "GET /health" }
            assertNotNull(spanItem)
            assertEquals("GET /health", spanItem.title)
            assertEquals(200, spanItem.statusCode)
            val logcatItem = result.items.find { it.category == "Logcat" }
            assertNotNull(logcatItem)
            assertEquals(401, logcatItem.statusCode)
            val lifecycleItem = result.items.find { it.category == "ui.lifecycle" }
            assertNotNull(lifecycleItem)
            assertEquals("Ui Lifecycle", lifecycleItem.title)
            assertEquals("Checkout: active", lifecycleItem.description)
            val navigationItem = result.items.find { it.category == "navigation.screen" }
            assertNotNull(navigationItem)
            assertEquals("Cart -> Checkout", navigationItem.description)
            val networkItem = result.items.find { it.category == "network.event" }
            assertNotNull(networkItem)
            assertEquals("POST /api/pay 502", networkItem.description)
            assertEquals(502, networkItem.statusCode)
            val customItem = result.items.find { it.category == "custom-action" }
            assertNotNull(customItem)
            assertEquals("Custom Action", customItem.title)
            assertEquals("tap", customItem.description)
            assertNull(result.items.find { it.category == "device.battery" })
            val rageItem = result.items.find { it.rage == true }
            assertNotNull(rageItem)
            Unit
        }
    }

    @Test
    fun `getReplayRecording decodes json payload variants`() = runBlocking {
        val rawJsonEvents = """[{"type":2,"timestamp":1000}]"""
        val encodedJsonEvents =
            Base64.getEncoder().encodeToString("""[{"type":3,"timestamp":2000}]""".toByteArray())
        val recordingRows = listOf(
            recordingDataRow(rawJsonEvents),
            recordingDataRow(encodedJsonEvents)
        ).joinToString("\n")

        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("SELECT toInt64(project_id)") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                query.contains("SELECT recording_data") ->
                    exchange.respond(200, recordingRows, TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplayRecording(REPLAY_UUID)
            assertNotNull(result)
            assertEquals(2, result.events.size)
            assertEquals(2, result.events[0].jsonObject["type"]?.jsonPrimitive?.int)
            assertEquals(3, result.events[1].jsonObject["type"]?.jsonPrimitive?.int)
        }
    }

    @Test
    fun `getReplayRecording decodes mobile msgpack recording and video payloads`() = runBlocking {
        val recordingRows = recordingDataRow(encodedMobileReplaySegment())

        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("SELECT toInt64(project_id)") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                query.contains("SELECT recording_data") ->
                    exchange.respond(200, recordingRows, TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplayRecording(REPLAY_UUID)
            assertNotNull(result)
            assertEquals(2, result.events.size)
            val replayEvent = result.events.first { it.jsonObject["type"]?.jsonPrimitive?.int == 5 }
            assertEquals(7, replayEvent.jsonObject["segment_id"]?.jsonPrimitive?.int)
            val videoEvent = result.events.first {
                it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "mobile_replay_video"
            }
            assertEquals(7, videoEvent.jsonObject["segment_id"]?.jsonPrimitive?.int)
            assertEquals("video/mp4", videoEvent.jsonObject["mime_type"]?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun `getReplayRecording returns placeholder for unsupported mobile payloads`() = runBlocking {
        val recordingRows = recordingDataRow(encodedMobileMetadataOnlySegment())

        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("SELECT toInt64(project_id)") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                query.contains("SELECT recording_data") ->
                    exchange.respond(200, recordingRows, TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplayRecording(REPLAY_UUID)
            assertNotNull(result)
            assertEquals(1, result.events.size)
            val placeholder = result.events.first().jsonObject
            assertEquals("mobile_replay_not_supported", placeholder["type"]?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun `getReplaysForIssue returns replay list items with entry url and signals`() = runBlocking {
        val replayRow = """
{"replay_id":"$REPLAY_UUID","project_id":1,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:05:00.000Z","duration_ms":"300000","urls":["","https://app.example.com/issue"],"error_count":1,"user_id":"u-1","user_email":"test@test.com","user_username":"tester","browser_name":"Chrome","browser_version":"120","os_name":"macOS","os_version":"14","activity":0}
        """.trimIndent()

        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("issues FINAL") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                query.contains("replay_events r") ->
                    exchange.respond(200, replayRow, TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplaysForIssue("issue-1")
            assertEquals(1, result.size)
            assertEquals("https://app.example.com/issue", result.first().entryUrl)
            assertTrue("error" in result.first().signals)
            assertTrue("dead_click" in result.first().signals)
        }
    }
}
