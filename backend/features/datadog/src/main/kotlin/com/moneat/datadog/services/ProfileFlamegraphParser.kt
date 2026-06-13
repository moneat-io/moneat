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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    private const val JFR_PROFILE_TYPE = "jfr"
    private val json = Json { ignoreUnknownKeys = true }

    private data class SentryProfileData(
        val frames: JsonArray,
        val stacks: JsonArray,
        val samples: JsonArray,
    )

    private data class MutableFrame(
        val name: String,
        var value: Int = 0,
        val children: MutableMap<String, MutableFrame> = mutableMapOf(),
    )

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
                val isJfrType = profileType.lowercase() == JFR_PROFILE_TYPE
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
        val sentryData = parseSentryData(data) ?: return emptyFlamegraph()
        val rootFrame = MutableFrame("(root)")
        sentryData.samples.forEach { sample ->
            addSampleToFrameTree(sample.jsonObject, sentryData.stacks, sentryData.frames, rootFrame)
        }
        return buildJsonObject {
            put("frames", childrenToJson(rootFrame))
        }
    }

    private fun parseSentryData(data: ByteArray): SentryProfileData? {
        return runCatching {
            val root = json.parseToJsonElement(String(data)).jsonObject
            val profileObj = root["profile"]?.jsonObject ?: return null
            SentryProfileData(
                frames = profileObj["frames"]?.jsonArray ?: return null,
                stacks = profileObj["stacks"]?.jsonArray ?: return null,
                samples = profileObj["samples"]?.jsonArray ?: return null,
            )
        }.getOrNull()
    }

    private fun addSampleToFrameTree(
        sample: JsonObject,
        stacks: JsonArray,
        frames: JsonArray,
        rootFrame: MutableFrame,
    ) {
        val stackIdx = sample["stack_id"]?.jsonPrimitive?.int ?: return
        val stack = stacks.getOrNull(stackIdx)?.jsonArray ?: return
        var current = rootFrame
        for (i in stack.size - 1 downTo 0) {
            val frameIdx = stack[i].jsonPrimitive.int
            val frameObj = frames.getOrNull(frameIdx)?.jsonObject ?: continue
            val name = sentryFrameName(frameObj)
            current = current.children.getOrPut(name) { MutableFrame(name) }
            current.value++
        }
    }

    private fun sentryFrameName(frameObj: JsonObject): String {
        val function = frameObj["function"]?.jsonPrimitive?.content ?: "unknown"
        val module = frameObj["module"]?.jsonPrimitive?.content
        return if (module != null) "$module.$function" else function
    }

    private fun childrenToJson(parent: MutableFrame): JsonArray = buildJsonArray {
        parent.children.values.sortedByDescending { it.value }.forEach { add(frameToJson(it)) }
    }

    private fun frameToJson(frame: MutableFrame): JsonObject = buildJsonObject {
        put("name", frame.name)
        put("value", frame.value)
        put("children", childrenToJson(frame))
    }
}
