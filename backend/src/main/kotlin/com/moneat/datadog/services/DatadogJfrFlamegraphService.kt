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

import com.moneat.datadog.decompression.DecompressionService
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedFrame
import jdk.jfr.consumer.RecordedStackTrace
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

object DatadogJfrFlamegraphService {

    private const val JFR_MAGIC_LAST_BYTE_INDEX = 3
    private const val MAX_THREADS = 50

    private data class MutableFrame(
        val name: String,
        var value: Long = 0L,
        val children: MutableMap<String, MutableFrame> = mutableMapOf(),
    )

    /** A logical profile dimension (CPU, allocations, lock contention, …). */
    private data class SampleKind(val key: String, val label: String, val unit: String)

    private data class CollectedSample(
        val frames: List<String>,
        val typeKey: String,
        val thread: String,
        val weight: Long,
    )

    private val CPU = SampleKind("cpu", "CPU", "samples")
    private val ALLOC = SampleKind("alloc", "Allocations", "bytes")
    private val LOCK = SampleKind("lock", "Lock contention", "ns")
    private val BLOCK = SampleKind("block", "Blocking", "ns")

    /**
     * Parse a JFR recording into a flamegraph tree for the requested sample type
     * and thread, alongside the full set of available types and threads so the
     * UI can offer selectors. Passing null falls back to the dominant type and
     * all threads.
     */
    fun parseToFrames(
        data: ByteArray,
        sampleType: String? = null,
        thread: String? = null,
    ): JsonObject {
        return runCatching {
            val payload = DecompressionService.decompress(data, null)
            if (!hasJfrMagic(payload)) {
                emptyFlamegraph()
            } else {
                buildResponse(collectSamples(payload), sampleType, thread)
            }
        }.onFailure { e ->
            logger.warn(e) { "Failed to parse Datadog JFR payload into flamegraph frames" }
        }.getOrElse {
            emptyFlamegraph()
        }
    }

    private fun collectSamples(payload: ByteArray): List<CollectedSample> {
        val collected = ArrayList<CollectedSample>()
        val tmp = Files.createTempFile("moneat-profile-", ".jfr")
        try {
            Files.write(tmp, payload)
            readSamples(tmp, collected)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
        return collected
    }

    private fun readSamples(tmp: Path, into: MutableList<CollectedSample>) {
        RecordingFile(tmp).use { recording ->
            while (recording.hasMoreEvents()) {
                collectFromEvent(recording.readEvent())?.let { into += it }
            }
        }
    }

    private fun collectFromEvent(event: RecordedEvent): CollectedSample? {
        val kind = sampleKindFor(event.eventType.name) ?: return null
        val names = resolveFrames(event.stackTrace)
        if (names.isEmpty()) return null
        val weight = eventWeight(event, kind)
        if (weight <= 0L) return null
        return CollectedSample(names, kind.key, threadName(event), weight)
    }

    private fun buildResponse(
        collected: List<CollectedSample>,
        requestedType: String?,
        requestedThread: String?,
    ): JsonObject {
        if (collected.isEmpty()) return emptyFlamegraph()

        val typeTotals = LinkedHashMap<String, Long>()
        val threadTotals = HashMap<String, Long>()
        for (sample in collected) {
            typeTotals.merge(sample.typeKey, sample.weight, Long::plus)
            threadTotals.merge(sample.thread, sample.weight, Long::plus)
        }

        val selectedType = requestedType?.takeIf(typeTotals::containsKey)
            ?: typeTotals.maxByOrNull { it.value }?.key
            ?: return emptyFlamegraph()
        val selectedThread = requestedThread
            ?.takeIf { it.isNotBlank() && it != "all" && threadTotals.containsKey(it) }

        val root = MutableFrame("(root)")
        for (sample in collected) {
            if (sample.typeKey != selectedType) continue
            if (selectedThread != null && sample.thread != selectedThread) continue
            var current = root
            for (name in sample.frames) {
                val child = current.children.getOrPut(name) { MutableFrame(name) }
                child.value += sample.weight
                current = child
            }
        }

        val unit = kindOf(selectedType)?.unit ?: "samples"
        val totalSamples = root.children.values.sumOf { it.value }
        return buildJsonObject {
            put("frames", framesJson(root))
            put("sampleTypes", sampleTypesJson(typeTotals))
            put("threads", threadsJson(threadTotals))
            put("selectedSampleType", selectedType)
            if (selectedThread != null) put("selectedThread", selectedThread)
            put("unit", unit)
            put("totalSamples", totalSamples)
        }
    }

    private fun resolveFrames(stack: RecordedStackTrace?): List<String> {
        if (stack == null) return emptyList()
        return stack.frames.mapNotNull { frameName(it) }.asReversed()
    }

    private fun frameName(frame: RecordedFrame): String? {
        val method = frame.method ?: return null
        val typeName = method.type?.name.orEmpty()
        val methodName = method.name.orEmpty()
        return when {
            typeName.isBlank() && methodName.isBlank() -> null
            typeName.isBlank() -> methodName
            methodName.isBlank() -> typeName
            else -> "$typeName.$methodName"
        }
    }

    private fun sampleKindFor(name: String): SampleKind? = when {
        name == "jdk.ExecutionSample" || name == "jdk.NativeMethodSample" -> CPU
        name == "datadog.ExecutionSample" || name == "datadog.MethodSample" -> CPU
        name.contains("Allocation") -> ALLOC
        name.contains("Monitor") || name.contains("Lock") -> LOCK
        name.endsWith("Block") || name.contains("ThreadPark") -> BLOCK
        name.startsWith("datadog.") -> CPU
        else -> null
    }

    private fun kindOf(key: String): SampleKind? =
        listOf(CPU, ALLOC, LOCK, BLOCK).firstOrNull { it.key == key }

    private fun eventWeight(event: RecordedEvent, kind: SampleKind): Long = when (kind.key) {
        "alloc" -> firstLong(event, "weight", "allocationSize", "objectSize") ?: 1L
        "lock", "block" -> runCatching { event.duration.toNanos() }.getOrDefault(1L).coerceAtLeast(1L)
        else -> 1L
    }

    private fun firstLong(event: RecordedEvent, vararg fields: String): Long? {
        for (field in fields) {
            if (event.hasField(field)) {
                val value = runCatching { event.getLong(field) }.getOrNull()
                if (value != null && value > 0L) return value
            }
        }
        return null
    }

    private fun threadName(event: RecordedEvent): String {
        val thread = (
            if (event.hasField("sampledThread")) {
                runCatching { event.getThread("sampledThread") }.getOrNull()
            } else {
                null
            }
            ) ?: event.thread
        return thread?.javaName?.takeIf { it.isNotBlank() }
            ?: thread?.osName?.takeIf { it.isNotBlank() }
            ?: "unknown"
    }

    private fun framesJson(root: MutableFrame): JsonArray = buildJsonArray {
        root.children.values.sortedByDescending { it.value }.forEach { add(toJson(it)) }
    }

    private fun sampleTypesJson(typeTotals: Map<String, Long>): JsonArray = buildJsonArray {
        typeTotals.entries
            .sortedByDescending { it.value }
            .mapNotNull { kindOf(it.key) }
            .forEach { kind ->
                add(
                    buildJsonObject {
                        put("key", kind.key)
                        put("label", kind.label)
                        put("unit", kind.unit)
                    },
                )
            }
    }

    private fun threadsJson(threadTotals: Map<String, Long>): JsonArray = buildJsonArray {
        threadTotals.entries
            .sortedByDescending { it.value }
            .take(MAX_THREADS)
            .forEach { (name, total) ->
                add(
                    buildJsonObject {
                        put("id", name)
                        put("label", name)
                        put("samples", total)
                    },
                )
            }
    }

    private fun hasJfrMagic(data: ByteArray): Boolean {
        return data.size >= JFR_MAGIC.size &&
            data[0] == JFR_MAGIC[0] &&
            data[1] == JFR_MAGIC[1] &&
            data[2] == JFR_MAGIC[2] &&
            data[JFR_MAGIC_LAST_BYTE_INDEX] == JFR_MAGIC[JFR_MAGIC_LAST_BYTE_INDEX]
    }

    private fun toJson(frame: MutableFrame): JsonObject = buildJsonObject {
        put("name", frame.name)
        put("value", frame.value)
        put(
            "children",
            buildJsonArray {
                frame.children.values
                    .sortedByDescending { it.value }
                    .forEach { add(toJson(it)) }
            }
        )
    }

    private fun emptyFlamegraph(): JsonObject = buildJsonObject {
        put("frames", JsonArray(emptyList()))
        put("sampleTypes", JsonArray(emptyList()))
        put("threads", JsonArray(emptyList()))
        put("unit", "samples")
        put("totalSamples", 0L)
    }

    private val JFR_MAGIC = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'R'.code.toByte(), 0x00)
}
