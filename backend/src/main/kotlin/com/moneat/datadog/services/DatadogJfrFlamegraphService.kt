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
import java.nio.file.Files
import jdk.jfr.consumer.RecordedFrame
import jdk.jfr.consumer.RecordedStackTrace
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

object DatadogJfrFlamegraphService {

    private data class MutableFrame(
        val name: String,
        var value: Long = 0L,
        val children: MutableMap<String, MutableFrame> = mutableMapOf(),
    )

    fun parseToFrames(data: ByteArray): JsonObject {
        return runCatching {
            val payload = DecompressionService.decompress(data, null)
            if (!hasJfrMagic(payload)) {
                emptyFlamegraph()
            } else {
                val root = MutableFrame("(root)")
                val tmp = Files.createTempFile("moneat-profile-", ".jfr")
                try {
                    Files.write(tmp, payload)
                    RecordingFile(tmp).use { recording ->
                        while (recording.hasMoreEvents()) {
                            val event = recording.readEvent()
                            val eventName = event.eventType.name
                            if (!isSampleLikeEvent(eventName)) continue
                            val names = resolveFrames(event.stackTrace)
                            if (names.isEmpty()) continue

                            var current = root
                            names.forEach { name ->
                                val child = current.children.getOrPut(name) {
                                    MutableFrame(name)
                                }
                                child.value += 1
                                current = child
                            }
                        }
                    }
                } finally {
                    runCatching { Files.deleteIfExists(tmp) }
                }

                buildJsonObject {
                    put(
                        "frames",
                        buildJsonArray {
                            root.children.values
                                .sortedByDescending { it.value }
                                .forEach { add(toJson(it)) }
                        }
                    )
                }
            }
        }.onFailure { e ->
            logger.warn(e) { "Failed to parse Datadog JFR payload into flamegraph frames" }
        }.getOrElse {
            emptyFlamegraph()
        }
    }

    private fun resolveFrames(stack: RecordedStackTrace?): List<String> {
        if (stack == null) return emptyList()
        val names = stack.frames
            .mapNotNull { frameName(it) }
            .asReversed()
        return names
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

    private fun isSampleLikeEvent(name: String): Boolean {
        return name == "jdk.ExecutionSample" ||
            name == "jdk.NativeMethodSample" ||
            name.startsWith("datadog.")
    }

    private fun hasJfrMagic(data: ByteArray): Boolean {
        return data.size >= JFR_MAGIC.size &&
            data[0] == JFR_MAGIC[0] &&
            data[1] == JFR_MAGIC[1] &&
            data[2] == JFR_MAGIC[2] &&
            data[3] == JFR_MAGIC[3]
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
    }

    private val JFR_MAGIC = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'R'.code.toByte(), 0x00)
}
