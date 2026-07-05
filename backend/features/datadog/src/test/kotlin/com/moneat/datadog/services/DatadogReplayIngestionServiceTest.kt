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

package com.moneat.datadog.services

import com.moneat.datadog.models.DdReplayIdRef
import com.moneat.datadog.models.DdReplaySegmentEvent
import com.moneat.events.repositories.EventRepository
import com.moneat.events.repositories.models.ReplayEventInsertData
import com.moneat.events.repositories.models.ReplayRecordingInsertData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.test.assertFailsWith
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatadogReplayIngestionServiceTest {

    @Test
    fun `ingestReplaySegment stores canonical replay event and recording`() = runBlocking {
        val repository = mockk<EventRepository>()
        val replaySlot = slot<ReplayEventInsertData>()
        val recordingSlot = slot<ReplayRecordingInsertData>()
        coEvery { repository.insertReplayEvent(capture(replaySlot)) } returns true
        coEvery { repository.insertReplayRecording(capture(recordingSlot)) } returns Unit

        val result = DatadogReplayIngestionService.ingestReplaySegment(
            request = DatadogReplayIngestRequest(
                organizationId = 7,
                projectId = 11,
                event = replayEvent(recordsCount = 1),
                segmentBytes = replaySegmentJson().toByteArray(),
                declaredEncoding = null,
                tags = mapOf("env" to "prod", "version" to "1.2.3", "sdk_version" to "6.33.0"),
            ),
            eventRepository = repository,
        )

        assertEquals("11111111-2222-3333-4444-555555555555", result.replayId)
        assertEquals(2, result.recordCount)

        val replay = replaySlot.captured
        assertEquals(7, replay.organizationId)
        assertEquals(11, replay.projectId)
        assertEquals(3, replay.segmentId)
        assertEquals(1700000000000L, replay.timestampMs)
        assertEquals(listOf("https://example.com/cart"), replay.urls)
        assertEquals("prod", replay.environment)
        assertEquals("1.2.3", replay.release)
        assertEquals("@datadog/browser-rum", replay.sdkName)
        assertEquals("6.33.0", replay.sdkVersion)
        assertEquals(2, replay.activity)

        val tags = Json.parseToJsonElement(replay.tags).jsonObject
        assertEquals("datadog", tags["source_type"]?.jsonPrimitive?.content)
        assertEquals("Datadog RUM SDK", tags["source_name"]?.jsonPrimitive?.content)
        assertEquals("application-id", tags["dd_application_id"]?.jsonPrimitive?.content)
        assertEquals("view-id", tags["dd_view_id"]?.jsonPrimitive?.content)

        val recording = recordingSlot.captured
        assertEquals(replay.replayId, recording.replayId)
        assertEquals(replay.segmentId, recording.segmentId)
        assertTrue(recording.recordingData.startsWith("["))
        assertContains(recording.recordingData, "https://example.com/cart")

        coVerify(exactly = 1) { repository.insertReplayEvent(any()) }
        coVerify(exactly = 1) { repository.insertReplayRecording(any()) }
    }

    @Test
    fun `decodeReplaySegment inflates Datadog deflate payload and stores records array`() {
        val compressed = deflate(replaySegmentJson().toByteArray())

        val decoded = DatadogReplayIngestionService.decodeReplaySegment(compressed, "deflate")

        assertEquals(2, decoded.records.size)
        assertTrue(decoded.recordingData.startsWith("["))
        assertContains(decoded.recordingData, "https://example.com/cart")
    }

    @Test
    fun `decodeReplaySegment inflates browser SDK streaming deflate payload`() {
        val compressed = datadogStreamingDeflate(replaySegmentJson().toByteArray())

        val decoded = DatadogReplayIngestionService.decodeReplaySegment(compressed, "deflate")

        assertEquals(2, decoded.records.size)
        assertContains(decoded.recordingData, "https://example.com/cart")
    }

    @Test
    fun `normalizeReplayId converts hex session ids to UUIDs`() {
        val normalized = DatadogReplayIngestionService.normalizeReplayId("11111111222233334444555555555555")

        assertEquals("11111111-2222-3333-4444-555555555555", normalized)
    }

    @Test
    fun `ingestReplaySegment requires project-scoped api key`() = runBlocking {
        val repository = mockk<EventRepository>()
        coEvery { repository.insertReplayEvent(any()) } returns true
        coEvery { repository.insertReplayRecording(any()) } returns Unit

        val error = assertFailsWith<IllegalArgumentException> {
            DatadogReplayIngestionService.ingestReplaySegment(
                request = DatadogReplayIngestRequest(
                    organizationId = 7,
                    projectId = 0L,
                    event = replayEvent(recordsCount = 1),
                    segmentBytes = replaySegmentJson().toByteArray(),
                    declaredEncoding = null,
                ),
                eventRepository = repository,
            )
        }

        assertEquals("Project-scoped Datadog API key required for replay ingestion", error.message)
    }

    @Test
    fun `ingestReplaySegment requires replay session id`() = runBlocking {
        val repository = mockk<EventRepository>()
        coEvery { repository.insertReplayEvent(any()) } returns true
        coEvery { repository.insertReplayRecording(any()) } returns Unit

        val error = assertFailsWith<IllegalArgumentException> {
            DatadogReplayIngestionService.ingestReplaySegment(
                request = DatadogReplayIngestRequest(
                    organizationId = 7,
                    projectId = 11,
                    event = replayEvent(recordsCount = 1, sessionId = ""),
                    segmentBytes = replaySegmentJson().toByteArray(),
                    declaredEncoding = null,
                ),
                eventRepository = repository,
            )
        }

        assertEquals("Datadog replay event missing session.id", error.message)
    }

    @Test
    fun `ingestReplaySegment rejects segments with no records`() = runBlocking {
        val repository = mockk<EventRepository>()
        coEvery { repository.insertReplayEvent(any()) } returns true
        coEvery { repository.insertReplayRecording(any()) } returns Unit

        val error = assertFailsWith<IllegalArgumentException> {
            DatadogReplayIngestionService.ingestReplaySegment(
                request = DatadogReplayIngestRequest(
                    organizationId = 7,
                    projectId = 11,
                    event = replayEvent(recordsCount = 0),
                    segmentBytes = replayEmptySegmentJson().toByteArray(),
                    declaredEncoding = null,
                    tags = mapOf("env" to "prod"),
                ),
                eventRepository = repository,
            )
        }

        assertEquals("Datadog replay segment contains no records", error.message)
    }

    @Test
    fun `ingestReplaySegment defaults missing source to browser`() = runBlocking {
        val repository = mockk<EventRepository>()
        val replaySlot = slot<ReplayEventInsertData>()
        coEvery { repository.insertReplayEvent(capture(replaySlot)) } returns true
        coEvery { repository.insertReplayRecording(any()) } returns Unit

        DatadogReplayIngestionService.ingestReplaySegment(
            request = DatadogReplayIngestRequest(
                organizationId = 7,
                projectId = 11,
                event = replayEvent(recordsCount = 1, source = ""),
                segmentBytes = replaySegmentJson().toByteArray(),
                declaredEncoding = null,
                tags = mapOf("env" to "prod"),
            ),
            eventRepository = repository,
        )

        assertEquals("browser", replaySlot.captured.platform)
    }

    @Test
    fun `ingestReplaySegment ignores blank source tags`() = runBlocking {
        val repository = mockk<EventRepository>()
        val replaySlot = slot<ReplayEventInsertData>()
        coEvery { repository.insertReplayEvent(capture(replaySlot)) } returns true
        coEvery { repository.insertReplayRecording(any()) } returns Unit

        DatadogReplayIngestionService.ingestReplaySegment(
            request = DatadogReplayIngestRequest(
                organizationId = 7,
                projectId = 11,
                event = replayEvent(recordsCount = 2),
                segmentBytes = replaySegmentJson().toByteArray(),
                declaredEncoding = null,
                tags = mapOf("custom" to "value", "" to "skip"),
            ),
            eventRepository = repository,
        )

        val tags = Json.parseToJsonElement(replaySlot.captured.tags).jsonObject
        assertEquals("value", tags["custom"]?.jsonPrimitive?.content)
        assertNotNull(tags["dd_application_id"])
        assertEquals("datadog", tags["source_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ingestReplaySegment throws when inserting replay fails`() = runBlocking {
        val repository = mockk<EventRepository>()
        coEvery { repository.insertReplayEvent(any()) } returns false
        coEvery { repository.insertReplayRecording(any()) } returns Unit

        val error = assertFailsWith<IllegalStateException> {
            DatadogReplayIngestionService.ingestReplaySegment(
                request = DatadogReplayIngestRequest(
                    organizationId = 7,
                    projectId = 11,
                    event = replayEvent(recordsCount = 1),
                    segmentBytes = replaySegmentJson().toByteArray(),
                    declaredEncoding = null,
                ),
                eventRepository = repository,
            )
        }

        assertEquals("Failed to insert replay event", error.message)
    }

    private fun replayEvent(
        recordsCount: Int,
        source: String = "browser",
        sessionId: String = "11111111-2222-3333-4444-555555555555",
    ) = DdReplaySegmentEvent(
        source = source,
        creationReason = "segment_duration_limit",
        start = 1700000000000,
        end = 1700000005000,
        recordsCount = recordsCount,
        indexInView = 3,
        hasFullSnapshot = true,
        rawSegmentSize = 512,
        compressedSegmentSize = 128,
        application = DdReplayIdRef("application-id"),
        session = DdReplayIdRef(sessionId),
        view = DdReplayIdRef("view-id"),
    )

    @Test
    fun `decodeReplaySegment supports json array payloads`() {
        val encoded = Json.encodeToString(
            JsonArray.serializer(),
            JsonArray(
                List(1) {
                    Json.parseToJsonElement("""{"type":4,"data":{"href":"https://array.example.com"}}""")
                }
            )
        )

        val decoded = DatadogReplayIngestionService.decodeReplaySegment(
            encoded.toByteArray(),
            null,
        )

        assertEquals(1, decoded.records.size)
        assertContains(decoded.recordingData, "https://array.example.com")
    }

    @Test
    fun `decodeReplaySegment rejects object without records`() {
        val error = assertFailsWith<IllegalArgumentException> {
            DatadogReplayIngestionService.decodeReplaySegment("""{"type":4}""".toByteArray(), null)
        }

        assertEquals("Invalid Datadog replay segment payload", error.message)
    }

    @Test
    fun `decodeReplaySegment rejects invalid payload shape`() {
        val error = assertFailsWith<IllegalArgumentException> {
            DatadogReplayIngestionService.decodeReplaySegment("not-json".toByteArray(), null)
        }

        assertEquals("Invalid Datadog replay segment payload", error.message)
    }

    @Test
    fun `decodeReplaySegment falls back to raw data when encoding is invalid`() {
        val decoded = DatadogReplayIngestionService.decodeReplaySegment(replaySegmentJson().toByteArray(), "deflate")

        assertEquals(2, decoded.records.size)
        assertContains(decoded.recordingData, "https://example.com/cart")
    }

    @Test
    fun `normalizeReplayId falls back to deterministic hash`() {
        val normalized = DatadogReplayIngestionService.normalizeReplayId("custom-session")
        assertEquals(36, normalized.length)
        assertNotNull(UUID.fromString(normalized))
    }

    @Test
    fun `normalizeReplayId rejects empty session id`() {
        val error = assertFailsWith<IllegalArgumentException> {
            DatadogReplayIngestionService.normalizeReplayId("   ")
        }

        assertEquals("Replay ID cannot be empty", error.message)
    }

    private fun replayEmptySegmentJson(): String =
        """
        {
          "records": [],
          "records_count": 0
        }
        """.trimIndent()

    private fun replaySegmentJson(): String =
        """
        {
          "records": [
            {"type":4,"timestamp":1700000000000,"data":{"href":"https://example.com/cart"}},
            {"type":6,"timestamp":1700000000100,"data":{"has_focus":true}}
          ],
          "start":1700000000000,
          "end":1700000005000,
          "records_count":2
        }
        """.trimIndent()

    private fun deflate(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }

    private fun datadogStreamingDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val bytesWritten = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
            if (bytesWritten == 0) break
            output.write(buffer, 0, bytesWritten)
        }
        val adler = deflater.adler
        output.write(byteArrayOf(3, 0))
        output.write(
            byteArrayOf(
                ((adler ushr 24) and 255).toByte(),
                ((adler ushr 16) and 255).toByte(),
                ((adler ushr 8) and 255).toByte(),
                (adler and 255).toByte(),
            )
        )
        return output.toByteArray()
    }
}
