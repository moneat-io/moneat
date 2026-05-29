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

    @Test
    fun `parseToFrames reports allocation lock and block samples`() {
        val jfr = buildJfrWithExtendedSamples()
        val result = DatadogJfrFlamegraphService.parseToFrames(jfr)
        val typeKeys = result["sampleTypes"]!!.jsonArray
            .map { it.jsonObject["key"]!!.jsonPrimitive.content }

        assertTrue(typeKeys.containsAll(listOf("cpu", "alloc", "lock", "block")))

        val alloc = DatadogJfrFlamegraphService.parseToFrames(
            jfr,
            sampleType = "alloc",
        )
        assertEquals("alloc", alloc["selectedSampleType"]!!.jsonPrimitive.content)
        assertEquals("bytes", alloc["unit"]!!.jsonPrimitive.content)
        assertTrue(alloc["totalSamples"]!!.jsonPrimitive.long >= ALLOCATION_TOTAL_BYTES)

        val lock = DatadogJfrFlamegraphService.parseToFrames(
            jfr,
            sampleType = "lock",
        )
        assertEquals("lock", lock["selectedSampleType"]!!.jsonPrimitive.content)
        assertEquals("ns", lock["unit"]!!.jsonPrimitive.content)
        assertTrue(lock["totalSamples"]!!.jsonPrimitive.long > 0L)

        val block = DatadogJfrFlamegraphService.parseToFrames(
            jfr,
            sampleType = "block",
        )
        assertEquals("block", block["selectedSampleType"]!!.jsonPrimitive.content)
        assertEquals("ns", block["unit"]!!.jsonPrimitive.content)
        assertTrue(block["totalSamples"]!!.jsonPrimitive.long > 0L)
    }

    @Test
    fun `parseToFrames falls back from unknown selectors`() {
        val result = DatadogJfrFlamegraphService.parseToFrames(
            buildJfrWithExtendedSamples(),
            sampleType = "unknown",
            thread = "all",
        )

        assertTrue(result["frames"]!!.jsonArray.isNotEmpty())
        assertTrue(
            result["sampleTypes"]!!.jsonArray.any {
                it.jsonObject["key"]!!.jsonPrimitive.content ==
                    result["selectedSampleType"]!!.jsonPrimitive.content
            },
        )
    }

    @Test
    fun `parseToFrames filters one thread from multi-thread recording`() {
        val jfr = buildJfrWithExtendedSamples()
        val all = DatadogJfrFlamegraphService.parseToFrames(jfr)
        val workerThread = all["threads"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            .first { it == WORKER_THREAD_NAME }

        val filtered = DatadogJfrFlamegraphService.parseToFrames(
            jfr,
            sampleType = "cpu",
            thread = workerThread,
        )

        assertEquals(workerThread, filtered["selectedThread"]!!.jsonPrimitive.content)
        assertTrue(filtered["frames"]!!.jsonArray.isNotEmpty())
    }

    @Name("datadog.TestSample")
    @Label("Datadog Test Sample")
    @StackTrace(true)
    class DatadogTestSampleEvent : Event()

    @Name("datadog.ExecutionSample")
    @Label("Datadog Execution Sample")
    @StackTrace(true)
    class DatadogExecutionSampleEvent : Event()

    @Name("datadog.MethodSample")
    @Label("Datadog Method Sample")
    @StackTrace(true)
    class DatadogMethodSampleEvent : Event()

    @Name("example.ObjectAllocationSample")
    @Label("Allocation Sample")
    @StackTrace(true)
    class AllocationWeightSampleEvent : Event() {
        @field:Name("weight")
        var weight: Long = ALLOCATION_WEIGHT_BYTES
    }

    @Name("example.AllocationSizeSample")
    @Label("Allocation Size Sample")
    @StackTrace(true)
    class AllocationSizeSampleEvent : Event() {
        @field:Name("allocationSize")
        var allocationSize: Long = ALLOCATION_SIZE_BYTES
    }

    @Name("example.MonitorWait")
    @Label("Monitor Wait Sample")
    @StackTrace(true)
    class MonitorWaitSampleEvent : Event()

    @Name("example.ThreadPark")
    @Label("Thread Park Sample")
    @StackTrace(true)
    class ThreadParkSampleEvent : Event()

    @Name("datadog.SampledThread")
    @Label("Sampled Thread Sample")
    @StackTrace(true)
    class SampledThreadSampleEvent : Event() {
        @field:Name("sampledThread")
        var sampledThread: Thread = Thread.currentThread()
    }

    @Name("example.NotProfile")
    @Label("Ignored Event")
    @StackTrace(true)
    class IgnoredEvent : Event()

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

    private fun buildJfrWithExtendedSamples(): ByteArray {
        val recording = Recording()
        recording.enableSample(DatadogExecutionSampleEvent::class.java)
        recording.enableSample(DatadogMethodSampleEvent::class.java)
        recording.enableSample(AllocationWeightSampleEvent::class.java)
        recording.enableSample(AllocationSizeSampleEvent::class.java)
        recording.enableSample(MonitorWaitSampleEvent::class.java)
        recording.enableSample(ThreadParkSampleEvent::class.java)
        recording.enableSample(SampledThreadSampleEvent::class.java)
        recording.enableSample(IgnoredEvent::class.java)
        recording.start()
        emitExtendedSamples()
        emitWorkerSample()
        recording.stop()

        val tmp = Files.createTempFile("moneat-jfr-extended-test-", ".jfr")
        return try {
            recording.dump(tmp)
            Files.readAllBytes(tmp)
        } finally {
            recording.close()
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun Recording.enableSample(eventType: Class<out Event>) {
        enable(eventType).withoutThreshold().withStackTrace()
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

    private fun emitExtendedSamples() {
        DatadogExecutionSampleEvent().commit()
        DatadogMethodSampleEvent().commit()
        AllocationWeightSampleEvent().commit()
        AllocationSizeSampleEvent().commit()
        commitTimed(MonitorWaitSampleEvent())
        commitTimed(ThreadParkSampleEvent())
        SampledThreadSampleEvent().commit()
        IgnoredEvent().commit()
    }

    private fun commitTimed(event: Event) {
        event.begin()
        timedSamplePath()
        event.end()
        event.commit()
    }

    private fun timedSamplePath() {
        DatadogMethodSampleEvent().shouldCommit()
    }

    private fun emitWorkerSample() {
        val worker = Thread(
            { workerSamplePath() },
            WORKER_THREAD_NAME,
        )
        worker.start()
        worker.join()
    }

    private fun workerSamplePath() {
        DatadogExecutionSampleEvent().commit()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            gzip.write(bytes)
        }
        return out.toByteArray()
    }

    private companion object {
        private const val ALLOCATION_WEIGHT_BYTES = 256L
        private const val ALLOCATION_SIZE_BYTES = 512L
        private const val ALLOCATION_TOTAL_BYTES = ALLOCATION_WEIGHT_BYTES + ALLOCATION_SIZE_BYTES
        private const val WORKER_THREAD_NAME = "flamegraph-worker"
    }
}
