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

import com.moneat.utils.suspendRunCatching
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Turns a stored profile payload into the flamegraph JSON shape consumed by the
 * dashboard ({ frames, sampleTypes, threads, selectedSampleType, unit, totalSamples }).
 *
 * Single source of truth for the source/type → parser dispatch, shared by the
 * per-profile flamegraph route and [ProfileMergeService].
 */
object ProfileFlamegraphParser {
    private const val SENTRY_SOURCE = "sentry"
    private const val DATADOG_SOURCE = "datadog"
    private val json = Json { ignoreUnknownKeys = true }

    fun emptyFlamegraph(): JsonObject = buildJsonObject {
        put("frames", buildJsonArray {})
    }

    fun parse(
        source: String,
        profileType: String,
        data: ByteArray,
        sampleType: String?,
        thread: String?,
    ): JsonObject {
        return when (source) {
            SENTRY_SOURCE -> parseSentryProfileToFrames(data)
            DATADOG_SOURCE -> {
                val isJfrType = profileType.equals("jfr", ignoreCase = true)
                val isJfrPayload = DatadogPprofFlamegraphService.isLikelyJfrPayload(data)
                if (isJfrType || isJfrPayload) {
                    DatadogJfrFlamegraphService.parseToFrames(data, sampleType, thread)
                } else {
                    DatadogPprofFlamegraphService.parseToFrames(data, sampleType, thread)
                }
            }
            else -> emptyFlamegraph()
        }
    }

    /**
     * Parse a Sentry profile JSON (frames[], stacks[], samples[])
     * into the flamegraph tree format.
     */
    private fun parseSentryProfileToFrames(data: ByteArray): JsonObject {
        return suspendRunCatching {
            val root = json.parseToJsonElement(String(data)).jsonObject
            val profileObj = root["profile"]?.jsonObject ?: return emptyFlamegraph()
            val frames = profileObj["frames"]?.jsonArray ?: return emptyFlamegraph()
            val stacks = profileObj["stacks"]?.jsonArray ?: return emptyFlamegraph()
            val samples = profileObj["samples"]?.jsonArray ?: return emptyFlamegraph()

            data class MutableFrame(
                val name: String,
                var value: Int = 0,
                val children: MutableMap<String, MutableFrame> = mutableMapOf(),
            )

            val rootFrame = MutableFrame("(root)")

            for (sample in samples) {
                val stackIdx = sample.jsonObject["stack_id"]
                    ?.jsonPrimitive?.int ?: continue
                if (stackIdx >= stacks.size) continue
                val stack = stacks[stackIdx].jsonArray

                // Walk from bottom (root) to top (leaf)
                var current = rootFrame
                for (i in stack.size - 1 downTo 0) {
                    val frameIdx = stack[i].jsonPrimitive.int
                    if (frameIdx >= frames.size) continue
                    val frameObj = frames[frameIdx].jsonObject
                    val fn = frameObj["function"]?.jsonPrimitive?.content ?: "unknown"
                    val module = frameObj["module"]?.jsonPrimitive?.content
                    val name = if (module != null) "$module.$fn" else fn
                    current = current.children.getOrPut(name) { MutableFrame(name) }
                    current.value++
                }
            }

            fun toJson(f: MutableFrame): JsonObject = buildJsonObject {
                put("name", f.name)
                put("value", f.value)
                put(
                    "children",
                    buildJsonArray {
                        for (child in f.children.values.sortedByDescending { it.value }) {
                            add(toJson(child))
                        }
                    },
                )
            }

            buildJsonObject {
                put(
                    "frames",
                    buildJsonArray {
                        for (child in rootFrame.children.values.sortedByDescending { it.value }) {
                            add(toJson(child))
                        }
                    },
                )
            }
        }.getOrElse { emptyFlamegraph() }
    }
}
