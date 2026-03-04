// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.services

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.Recording
import jdk.jfr.StackTrace
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Test
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
