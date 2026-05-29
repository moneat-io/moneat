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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/**
 * Aggregates the individual profiles in a service/time window into a single
 * merged flamegraph by summing their parsed frame trees. Powers the service
 * explorer's "aggregated over the window" view.
 *
 * Bounded by design: [ProfileIngestionService.selectProfilesForMerge] evenly
 * samples at most a capped number of profiles across the window, and blobs are
 * read+parsed with limited parallelism under a total-bytes guard.
 */
object ProfileMergeService {
    const val DEFAULT_MAX_PROFILES = 25
    private const val MAX_PARALLELISM = 8
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    private class MutableFrame(
        val name: String,
        var value: Long = 0,
        val children: LinkedHashMap<String, MutableFrame> = LinkedHashMap(),
    )

    suspend fun mergeFlamegraph(
        organizationId: Int,
        service: String?,
        profileType: String?,
        env: String?,
        host: String?,
        version: String?,
        fromMs: Long,
        toMs: Long,
        sampleType: String?,
        thread: String?,
        maxProfiles: Int,
    ): JsonObject {
        val selection = ProfileIngestionService.selectProfilesForMerge(
            organizationId = organizationId,
            service = service,
            profileType = profileType,
            env = env,
            host = host,
            version = version,
            fromMs = fromMs,
            toMs = toMs,
            maxProfiles = maxProfiles,
        )
        if (selection.candidates.isEmpty()) {
            return emptyMerged(mergedCount = 0, total = selection.totalInWindow)
        }

        val semaphore = Semaphore(MAX_PARALLELISM)
        val totalBytes = AtomicLong(0)
        val parsed = coroutineScope {
            selection.candidates.map { candidate ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val data = ProfileStorageService.read(candidate.storageKey)
                            ?: return@withPermit null
                        if (totalBytes.addAndGet(data.size.toLong()) > MAX_TOTAL_BYTES) {
                            return@withPermit null
                        }
                        runCatching {
                            ProfileFlamegraphParser.parse(
                                source = candidate.source,
                                profileType = candidate.profileType,
                                data = data,
                                sampleType = sampleType,
                                thread = thread,
                            )
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }

        return mergeParsed(parsed, thread, selection.totalInWindow)
    }

    private fun mergeParsed(
        parsed: List<JsonObject>,
        thread: String?,
        total: Long,
    ): JsonObject {
        if (parsed.isEmpty()) return emptyMerged(0, total)

        val root = MutableFrame("(root)")
        val sampleTypes = LinkedHashMap<String, JsonObject>()
        val threadSamples = LinkedHashMap<String, Long>()
        val threadLabels = HashMap<String, String>()
        var totalSamples = 0L
        var unit = "samples"
        var selectedSampleType: String? = null

        for (obj in parsed) {
            obj["frames"]?.jsonArray?.let { mergeInto(root, it) }
            totalSamples += obj["totalSamples"]?.jsonPrimitive?.longOrNull ?: 0
            obj["unit"]?.jsonPrimitive?.contentOrNull?.let { unit = it }
            if (selectedSampleType == null) {
                selectedSampleType = obj["selectedSampleType"]?.jsonPrimitive?.contentOrNull
            }
            obj["sampleTypes"]?.jsonArray?.forEach { st ->
                val key = st.jsonObject["key"]?.jsonPrimitive?.contentOrNull
                if (key != null) sampleTypes.putIfAbsent(key, st.jsonObject)
            }
            obj["threads"]?.jsonArray?.forEach { th ->
                val o = th.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                threadSamples.merge(id, o["samples"]?.jsonPrimitive?.longOrNull ?: 0, Long::plus)
                o["label"]?.jsonPrimitive?.contentOrNull?.let { threadLabels[id] = it }
            }
        }

        return buildJsonObject {
            put("frames", rootFramesJson(root))
            put(
                "sampleTypes",
                buildJsonArray { sampleTypes.values.forEach { add(it) } },
            )
            put(
                "threads",
                buildJsonArray {
                    threadSamples.entries
                        .sortedByDescending { it.value }
                        .forEach { (id, samples) ->
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    put("label", threadLabels[id] ?: id)
                                    put("samples", samples)
                                },
                            )
                        }
                },
            )
            selectedSampleType?.let { put("selectedSampleType", it) }
            thread?.takeIf { it.isNotBlank() && it != "all" }?.let { put("selectedThread", it) }
            put("unit", unit)
            put("totalSamples", totalSamples)
            put("mergedCount", parsed.size)
            put("totalCount", total)
        }
    }

    private fun mergeInto(parent: MutableFrame, framesArr: JsonArray) {
        for (element in framesArr) {
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val value = obj["value"]?.jsonPrimitive?.longOrNull ?: 0L
            val child = parent.children.getOrPut(name) { MutableFrame(name) }
            child.value += value
            obj["children"]?.jsonArray?.let { mergeInto(child, it) }
        }
    }

    private fun rootFramesJson(root: MutableFrame): JsonArray = buildJsonArray {
        root.children.values.sortedByDescending { it.value }.forEach { add(frameToJson(it)) }
    }

    private fun frameToJson(frame: MutableFrame): JsonObject = buildJsonObject {
        put("name", frame.name)
        put("value", frame.value)
        put(
            "children",
            buildJsonArray {
                frame.children.values
                    .sortedByDescending { it.value }
                    .forEach { add(frameToJson(it)) }
            },
        )
    }

    private fun emptyMerged(mergedCount: Int, total: Long): JsonObject = buildJsonObject {
        put("frames", buildJsonArray {})
        put("mergedCount", mergedCount)
        put("totalCount", total)
    }
}
