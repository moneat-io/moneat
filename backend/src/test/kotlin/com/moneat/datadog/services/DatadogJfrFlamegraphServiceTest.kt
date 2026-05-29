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

import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.Recording
import jdk.jfr.StackTrace
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatadogJfrFlamegraphServiceTest {

    @Test
    fun `parseToFrames parses raw jfr`() {
        val jfr = buildJfrWithDatadogSample()
        val result = DatadogJfrFlamegraphService.parseToFrames(jfr)
        val frames = result["frames"]!!.jsonArray
        assertTrue(frames.isNotEmpty())
    }

    @Test
    fun `parseToFrames parses gzipped jfr`() {
        val jfr = buildJfrWithDatadogSample()
        val gz = gzip(jfr)
        val result = DatadogJfrFlamegraphService.parseToFrames(gz)
        val frames = result["frames"]!!.jsonArray
        assertTrue(frames.isNotEmpty())
    }

    @Test
    fun `parseToFrames returns empty for invalid payload`() {
        val result = DatadogJfrFlamegraphService.parseToFrames("not-a-jfr".toByteArray())
        assertTrue(result["frames"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `parseToFrames reports sample types and threads`() {
        val jfr = buildJfrWithDatadogSample()
        val result = DatadogJfrFlamegraphService.parseToFrames(jfr)

        val sampleTypes = result["sampleTypes"]!!.jsonArray
        assertTrue(
            sampleTypes.any { it.jsonObject["key"]!!.jsonPrimitive.content == "cpu" },
        )
        assertTrue(result["threads"]!!.jsonArray.isNotEmpty())
        assertTrue(result["totalSamples"]!!.jsonPrimitive.long > 0)
        assertEquals("cpu", result["selectedSampleType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parseToFrames filters by thread`() {
        val jfr = buildJfrWithDatadogSample()
        val all = DatadogJfrFlamegraphService.parseToFrames(jfr)
        val threadId = all["threads"]!!.jsonArray
            .first().jsonObject["id"]!!.jsonPrimitive.content

        val filtered = DatadogJfrFlamegraphService.parseToFrames(jfr, thread = threadId)
        assertTrue(filtered["frames"]!!.jsonArray.isNotEmpty())
        assertEquals(threadId, filtered["selectedThread"]!!.jsonPrimitive.content)
    }

    @Name("datadog.TestSample")
    @Label("Datadog Test Sample")
    @StackTrace(true)
    class DatadogTestSampleEvent : Event()

    private fun buildJfrWithDatadogSample(): ByteArray {
        val recording = Recording()
        recording.enable(DatadogTestSampleEvent::class.java)
            .withoutThreshold()
            .withStackTrace()
        recording.start()
        emitSampleEvent()
        recording.stop()

        val tmp = Files.createTempFile("moneat-jfr-test-", ".jfr")
        return try {
            recording.dump(tmp)
            Files.readAllBytes(tmp)
        } finally {
            recording.close()
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun emitSampleEvent() {
        samplePathOuter()
    }

    private fun samplePathOuter() {
        samplePathInner()
    }

    private fun samplePathInner() {
        DatadogTestSampleEvent().commit()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            gzip.write(bytes)
        }
        return out.toByteArray()
    }
}
