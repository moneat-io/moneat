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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
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

    private fun replayEvent(recordsCount: Int) = DdReplaySegmentEvent(
        source = "browser",
        creationReason = "segment_duration_limit",
        start = 1700000000000,
        end = 1700000005000,
        recordsCount = recordsCount,
        indexInView = 3,
        hasFullSnapshot = true,
        rawSegmentSize = 512,
        compressedSegmentSize = 128,
        application = DdReplayIdRef("application-id"),
        session = DdReplayIdRef("11111111-2222-3333-4444-555555555555"),
        view = DdReplayIdRef("view-id"),
    )

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
