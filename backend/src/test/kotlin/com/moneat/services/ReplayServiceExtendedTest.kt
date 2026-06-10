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

import com.moneat.billing.services.PricingTierService
import com.moneat.events.services.DashboardQueryHelper
import com.moneat.events.services.ReplayService
import com.moneat.shared.services.ProjectIdResolver
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.OrgProjectTestFixtures
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import com.moneat.testsupport.withClickHouseMockServer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayServiceExtendedTest {

    companion object {
        private const val TEXT_PLAIN = "text/plain"
        private const val REPLAY_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        private var db: Database? = null
    }

    private val retentionPolicyService = mockk<RetentionPolicyService>()
    private val pricingTierService = mockk<PricingTierService>()
    private lateinit var queryHelper: DashboardQueryHelper
    private lateinit var service: ReplayService

    @BeforeTest
    fun setup() {
        db = OrgProjectTestFixtures.connectResetAndSeedDefaultOrgProject(db, "moneat_replay_service_ext")

        coEvery { retentionPolicyService.getRetentionDaysForProject(any()) } returns 30
        queryHelper = DashboardQueryHelper(retentionPolicyService, pricingTierService)
        service = ReplayService(queryHelper)
    }

    private fun defaultProjectResourceId(): String =
        ProjectIdResolver().resourceIdFor(1) ?: ""

    @Test
    fun `getReplayRecording decodes JSON segment events from recording_data`() = runBlocking {
        val jsonLine = """[{"type":2,"data":{"tag":"x"}}]"""
        val segmentRow =
            buildJsonObject {
                put("recording_data", JsonPrimitive(jsonLine))
            }.toString()
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("replay_segments") ->
                    exchange.respond(200, segmentRow, TEXT_PLAIN)
                query.contains("replay_events") && !query.contains("GROUP BY") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplayRecording(REPLAY_UUID)
            assertNotNull(result)
            assertEquals(1, result.events.size)
            val first = result.events.first() as JsonObject
            assertEquals(2, first["type"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `getReplayRecording returns mobile placeholder when msgpack yields no events`() = runBlocking {
        val invalidMsgpackB64 = Base64.getEncoder().encodeToString(byteArrayOf(0xff.toByte()))
        val segmentRow =
            buildJsonObject {
                put("recording_data", JsonPrimitive(invalidMsgpackB64))
            }.toString()
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("replay_segments") ->
                    exchange.respond(200, segmentRow, TEXT_PLAIN)
                query.contains("replay_events") && !query.contains("GROUP BY") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplayRecording(REPLAY_UUID)
            assertNotNull(result)
            assertEquals(1, result.events.size)
            val marker = result.events.first() as JsonObject
            assertEquals("mobile_replay_not_supported", marker["type"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `getReplayRecording decodes base64-wrapped JSON segment events`() = runBlocking {
        val jsonLine = """[{"type":3,"data":{}}]"""
        val b64 = Base64.getEncoder().encodeToString(jsonLine.toByteArray(Charsets.UTF_8))
        val segmentRow =
            buildJsonObject {
                put("recording_data", JsonPrimitive(b64))
            }.toString()
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("replay_segments") ->
                    exchange.respond(200, segmentRow, TEXT_PLAIN)
                query.contains("replay_events") && !query.contains("GROUP BY") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplayRecording(REPLAY_UUID)
            assertNotNull(result)
            assertEquals(1, result.events.size)
            val first = result.events.first() as JsonObject
            assertEquals(3, first["type"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `getReplayRecording returns null when project id cannot be resolved`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            exchange.respond(200, "", TEXT_PLAIN)
        }) {
            assertNull(service.getReplayRecording(REPLAY_UUID))
        }
    }

    @Test
    fun `getReplayRecording returns empty events when replay_segments response is empty`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("replay_events") && !query.contains("GROUP BY") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                query.contains("replay_segments") ->
                    exchange.respond(200, "", TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplayRecording(REPLAY_UUID)
            assertTrue(result == null || result.events.isEmpty())
        }
    }

    @Test
    fun `getReplayRecording returns null when replay_segments request fails`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("replay_events") && !query.contains("GROUP BY") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                query.contains("replay_segments") ->
                    exchange.respond(500, "Internal Server Error", TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            assertNull(service.getReplayRecording(REPLAY_UUID))
        }
    }

    @Test
    fun `getReplayRecording returns null for invalid uuid`() = runBlocking {
        assertNull(service.getReplayRecording("not-a-uuid"))
    }

    @Test
    fun `getReplaysForIssue returns replays linked to issue`() = runBlocking {
        val replayRow =
            """
            {"replay_id":"$REPLAY_UUID","project_id":1,"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:05:00.000Z","duration_ms":"300000","urls":["https://app.example.com"],"error_count":3,"user_id":"u-1","user_email":"a@b.com","user_username":"u","browser_name":"Chrome","browser_version":"120","os_name":"macOS","os_version":"14","activity":5}
            """.trimIndent()
        val issueId = "ISSUE-ABC-123"
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            when {
                query.contains("issues") && query.contains("FINAL") ->
                    exchange.respond(200, """{"project_id":1}""", TEXT_PLAIN)
                query.contains("replay_events") && query.contains("ARRAY JOIN") ->
                    exchange.respond(200, replayRow, TEXT_PLAIN)
                else ->
                    exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            val result = service.getReplaysForIssue(issueId, limit = 5)
            assertEquals(1, result.size)
            assertEquals(REPLAY_UUID, result[0].replayId)
            assertEquals(defaultProjectResourceId(), result[0].projectId)
            assertEquals(3, result[0].errorCount)
            assertEquals("Chrome", result[0].browserName)
        }
    }

    @Test
    fun `getReplaysForIssue returns empty when issue has no project`() = runBlocking {
        withClickHouseMockServer({ exchange ->
            val query = exchange.requestBodyText()
            if (query.contains("issues") && query.contains("FINAL")) {
                exchange.respond(200, "", TEXT_PLAIN)
            } else {
                exchange.respond(200, "", TEXT_PLAIN)
            }
        }) {
            assertTrue(service.getReplaysForIssue("unknown-issue").isEmpty())
        }
    }
}
