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

import com.google.protobuf.CodedInputStream
import com.moneat.datadog.decompression.DecompressionService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val HEX_RADIX = 16
private const val PROTO_TAG_SHIFT = 3
private const val PROTO_FIELD_3 = 3
private const val PROTO_FIELD_4 = 4
private const val PROTO_FIELD_5 = 5
private const val PROTO_FIELD_6 = 6
private const val PROTO_FIELD_14 = 14
private const val JFR_MAGIC_LAST_INDEX = 3

object DatadogPprofFlamegraphService {

    data class MutableFrame(
        val name: String,
        var value: Long = 0L,
        val children: MutableMap<String, MutableFrame> = mutableMapOf(),
    )

    private data class PprofProfile(
        val sampleTypes: List<ValueType>,
        val samples: List<Sample>,
        val mappingsById: Map<Long, Mapping>,
        val locationsById: Map<Long, Location>,
        val locationsByOrdinal: List<Location>,
        val functionsById: Map<Long, Function>,
        val strings: List<String>,
        val defaultSampleType: Long,
    )

    private data class ValueType(val typeIdx: Long, val unitIdx: Long)
    private data class Sample(
        val locationIds: List<Long>,
        val locationIdx: List<Long>,
        val values: List<Long>,
    )
    private data class Mapping(
        val id: Long,
        val filenameIdx: Long,
        val buildIdIdx: Long,
    )
    private data class Location(
        val id: Long,
        val mappingId: Long,
        val address: Long,
        val lines: List<Line>,
    )
    private data class Line(val functionId: Long, val line: Long)
    private data class Function(
        val id: Long,
        val nameIdx: Long,
        val systemNameIdx: Long,
        val filenameIdx: Long,
    )

    fun parseToFrames(data: ByteArray): JsonObject {
        return runCatching {
            val payload = normalizePayload(data)
            if (hasJfrMagic(payload)) {
                logger.debug { "Skipping flamegraph parse for JFR payload" }
                emptyFlamegraph()
            } else {
                val profile = decodeProfile(payload)
                val roots = buildFrameTree(profile)
                buildJsonObject {
                    put(
                        "frames",
                        buildJsonArray {
                            roots.sortedByDescending { it.value }
                                .forEach { add(toJson(it)) }
                        }
                    )
                }
            }
        }.onFailure { e ->
            logger.warn(e) { "Failed to parse Datadog pprof payload into flamegraph frames" }
        }.getOrElse {
            emptyFlamegraph()
        }
    }

    /**
     * Best-effort guard to distinguish actual pprof payloads from
     * unrelated multipart files (for example, JFR attachments).
     */
    fun isSupportedPprof(data: ByteArray): Boolean {
        return runCatching {
            val payload = normalizePayload(data)
            if (hasJfrMagic(payload)) return@runCatching false
            val profile = decodeProfile(payload)
            profile.sampleTypes.isNotEmpty() ||
                profile.samples.isNotEmpty() ||
                profile.locationsById.isNotEmpty() ||
                profile.functionsById.isNotEmpty()
        }.getOrDefault(false)
    }

    fun isLikelyJfrPayload(data: ByteArray): Boolean {
        return runCatching {
            hasJfrMagic(normalizePayload(data))
        }.getOrDefault(false)
    }

    private fun buildFrameTree(profile: PprofProfile): List<MutableFrame> {
        if (profile.samples.isEmpty()) return emptyList()
        val valueIndex = resolveValueIndex(profile)
        val root = MutableFrame("(root)")

        profile.samples.forEach { sample ->
            val weight = sample.values.getOrNull(valueIndex)
                ?: sample.values.lastOrNull()
                ?: return@forEach

            val normalizedWeight = if (weight == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(weight)
            if (normalizedWeight == 0L) return@forEach

            val names = resolveSampleFrames(profile, sample)
            if (names.isEmpty()) return@forEach

            var current = root
            names.forEach { name ->
                val child = current.children.getOrPut(name) { MutableFrame(name) }
                child.value += normalizedWeight
                current = child
            }
        }

        return root.children.values.toList()
    }

    private fun resolveSampleFrames(
        profile: PprofProfile,
        sample: Sample,
    ): List<String> {
        val locationIds = if (sample.locationIds.isNotEmpty()) {
            sample.locationIds
        } else {
            // Some producers emit location_idx instead of location_id.
            sample.locationIdx.mapNotNull { idx ->
                profile.locationsByOrdinal.getOrNull(idx.toInt())?.id
            }
        }
        if (locationIds.isEmpty()) return emptyList()

        val names = mutableListOf<String>()

        // pprof stores the leaf frame first; flamegraphs require root→leaf order.
        locationIds.asReversed().forEach { locationId ->
            val location = profile.locationsById[locationId]
                ?: profile.locationsByOrdinal.getOrNull(locationId.toInt())
                ?: return@forEach

            val lineNames = resolveLocationFrameNames(profile, location)
            if (lineNames.isNotEmpty()) {
                names += lineNames
            }
        }

        return names
    }

    private fun resolveLocationFrameNames(
        profile: PprofProfile,
        location: Location,
    ): List<String> {
        if (location.lines.isEmpty()) {
            return listOf(locationFallbackName(profile, location))
        }

        val names = mutableListOf<String>()
        // For inlined frames, later entries are callers into previous entries.
        location.lines.asReversed().forEach { line ->
            val fn = profile.functionsById[line.functionId]
            if (fn == null) {
                names += locationFallbackName(profile, location)
                return@forEach
            }
            val name = stringAt(profile.strings, fn.nameIdx)
                .ifBlank { stringAt(profile.strings, fn.systemNameIdx) }
                .ifBlank { stringAt(profile.strings, fn.filenameIdx) }
                .ifBlank { locationFallbackName(profile, location) }
            names += name
        }
        return names
    }

    private fun locationFallbackName(
        profile: PprofProfile,
        location: Location,
    ): String {
        val mapping = profile.mappingsById[location.mappingId]
        val file = mapping?.let { stringAt(profile.strings, it.filenameIdx) }.orEmpty()
        val buildId = mapping?.let { stringAt(profile.strings, it.buildIdIdx) }.orEmpty()
        val address = "0x${location.address.toString(HEX_RADIX)}"
        return when {
            file.isNotBlank() && buildId.isNotBlank() -> "$file@$buildId:$address"
            file.isNotBlank() -> "$file:$address"
            else -> address
        }
    }

    private fun resolveValueIndex(profile: PprofProfile): Int {
        if (profile.sampleTypes.isEmpty()) return 0

        if (profile.defaultSampleType != 0L) {
            val defaultIdx = profile.sampleTypes.indexOfFirst {
                it.typeIdx == profile.defaultSampleType
            }
            if (defaultIdx >= 0) return defaultIdx
        }

        val totals = profile.sampleTypes.indices.associateWith { idx ->
            profile.samples.sumOf { sample ->
                kotlin.math.abs(sample.values.getOrNull(idx) ?: 0L)
            }
        }
        return totals.maxByOrNull { it.value }?.key ?: 0
    }

    private fun decodeProfile(bytes: ByteArray): PprofProfile {
        val input = CodedInputStream.newInstance(bytes)
        val sampleTypes = mutableListOf<ValueType>()
        val samples = mutableListOf<Sample>()
        val mappings = mutableMapOf<Long, Mapping>()
        val locations = mutableMapOf<Long, Location>()
        val locationList = mutableListOf<Location>()
        val functions = mutableMapOf<Long, Function>()
        val strings = mutableListOf<String>()
        var defaultSampleType = 0L

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl PROTO_TAG_SHIFT) or 2 -> sampleTypes += decodeValueType(input.readByteArray())
                (2 shl PROTO_TAG_SHIFT) or 2 -> samples += decodeSample(input.readByteArray())
                (PROTO_FIELD_3 shl PROTO_TAG_SHIFT) or 2 -> {
                    val mapping = decodeMapping(input.readByteArray())
                    mappings[mapping.id] = mapping
                }
                (PROTO_FIELD_4 shl PROTO_TAG_SHIFT) or 2 -> {
                    val location = decodeLocation(input.readByteArray())
                    if (location.id != 0L) {
                        locations[location.id] = location
                    }
                    locationList += location
                }
                (PROTO_FIELD_5 shl PROTO_TAG_SHIFT) or 2 -> {
                    val fn = decodeFunction(input.readByteArray())
                    functions[fn.id] = fn
                }
                (PROTO_FIELD_6 shl PROTO_TAG_SHIFT) or 2 -> strings += input.readString()
                (PROTO_FIELD_14 shl PROTO_TAG_SHIFT) or 0 -> defaultSampleType = input.readInt64()
                else -> input.skipField(tag)
            }
        }

        return PprofProfile(
            sampleTypes = sampleTypes,
            samples = samples,
            mappingsById = mappings,
            locationsById = locations,
            locationsByOrdinal = locationList,
            functionsById = functions,
            strings = strings,
            defaultSampleType = defaultSampleType,
        )
    }

    private fun decodeValueType(bytes: ByteArray): ValueType {
        val input = CodedInputStream.newInstance(bytes)
        var typeIdx = 0L
        var unitIdx = 0L
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl PROTO_TAG_SHIFT) or 0 -> typeIdx = input.readInt64()
                (2 shl PROTO_TAG_SHIFT) or 0 -> unitIdx = input.readInt64()
                else -> input.skipField(tag)
            }
        }
        return ValueType(typeIdx, unitIdx)
    }

    private fun decodeSample(bytes: ByteArray): Sample {
        val input = CodedInputStream.newInstance(bytes)
        val locationIds = mutableListOf<Long>()
        val locationIdx = mutableListOf<Long>()
        val values = mutableListOf<Long>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl PROTO_TAG_SHIFT) or 0 -> locationIds += input.readUInt64()
                (1 shl PROTO_TAG_SHIFT) or 2 -> readPackedUInt64(input.readByteArray(), locationIds)
                (2 shl PROTO_TAG_SHIFT) or 0 -> values += input.readInt64()
                (2 shl PROTO_TAG_SHIFT) or 2 -> readPackedInt64(input.readByteArray(), values)
                (PROTO_FIELD_4 shl PROTO_TAG_SHIFT) or 0 -> locationIdx += input.readUInt64()
                (PROTO_FIELD_4 shl PROTO_TAG_SHIFT) or 2 -> readPackedUInt64(input.readByteArray(), locationIdx)
                else -> input.skipField(tag)
            }
        }

        return Sample(
            locationIds = locationIds,
            locationIdx = locationIdx,
            values = values,
        )
    }

    private fun decodeMapping(bytes: ByteArray): Mapping {
        val input = CodedInputStream.newInstance(bytes)
        var id = 0L
        var filenameIdx = 0L
        var buildIdIdx = 0L
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl PROTO_TAG_SHIFT) or 0 -> id = input.readUInt64()
                (PROTO_FIELD_5 shl PROTO_TAG_SHIFT) or 0 -> filenameIdx = input.readInt64()
                (PROTO_FIELD_6 shl PROTO_TAG_SHIFT) or 0 -> buildIdIdx = input.readInt64()
                else -> input.skipField(tag)
            }
        }
        return Mapping(id = id, filenameIdx = filenameIdx, buildIdIdx = buildIdIdx)
    }

    private fun decodeLocation(bytes: ByteArray): Location {
        val input = CodedInputStream.newInstance(bytes)
        var id = 0L
        var mappingId = 0L
        var address = 0L
        val lines = mutableListOf<Line>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl PROTO_TAG_SHIFT) or 0 -> id = input.readUInt64()
                (2 shl PROTO_TAG_SHIFT) or 0 -> mappingId = input.readUInt64()
                (PROTO_FIELD_3 shl PROTO_TAG_SHIFT) or 0 -> address = input.readUInt64()
                (PROTO_FIELD_4 shl PROTO_TAG_SHIFT) or 2 -> lines += decodeLine(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return Location(
            id = id,
            mappingId = mappingId,
            address = address,
            lines = lines,
        )
    }

    private fun decodeLine(bytes: ByteArray): Line {
        val input = CodedInputStream.newInstance(bytes)
        var functionId = 0L
        var line = 0L
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl PROTO_TAG_SHIFT) or 0 -> functionId = input.readUInt64()
                (2 shl PROTO_TAG_SHIFT) or 0 -> line = input.readInt64()
                else -> input.skipField(tag)
            }
        }
        return Line(functionId = functionId, line = line)
    }

    private fun decodeFunction(bytes: ByteArray): Function {
        val input = CodedInputStream.newInstance(bytes)
        var id = 0L
        var nameIdx = 0L
        var systemNameIdx = 0L
        var filenameIdx = 0L

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl PROTO_TAG_SHIFT) or 0 -> id = input.readUInt64()
                (2 shl PROTO_TAG_SHIFT) or 0 -> nameIdx = input.readInt64()
                (PROTO_FIELD_3 shl PROTO_TAG_SHIFT) or 0 -> systemNameIdx = input.readInt64()
                (PROTO_FIELD_4 shl PROTO_TAG_SHIFT) or 0 -> filenameIdx = input.readInt64()
                else -> input.skipField(tag)
            }
        }

        return Function(
            id = id,
            nameIdx = nameIdx,
            systemNameIdx = systemNameIdx,
            filenameIdx = filenameIdx,
        )
    }

    private fun readPackedUInt64(bytes: ByteArray, into: MutableList<Long>) {
        val packed = CodedInputStream.newInstance(bytes)
        while (!packed.isAtEnd) {
            into += packed.readUInt64()
        }
    }

    private fun readPackedInt64(bytes: ByteArray, into: MutableList<Long>) {
        val packed = CodedInputStream.newInstance(bytes)
        while (!packed.isAtEnd) {
            into += packed.readInt64()
        }
    }

    private fun stringAt(strings: List<String>, idx: Long): String {
        if (idx < 0 || idx >= strings.size.toLong()) return ""
        return strings[idx.toInt()]
    }

    private fun normalizePayload(data: ByteArray): ByteArray {
        return DecompressionService.decompress(data, null)
    }

    private fun hasJfrMagic(data: ByteArray): Boolean {
        return data.size >= JFR_MAGIC.size &&
            data[0] == JFR_MAGIC[0] &&
            data[1] == JFR_MAGIC[1] &&
            data[2] == JFR_MAGIC[2] &&
            data[JFR_MAGIC_LAST_INDEX] == JFR_MAGIC[JFR_MAGIC_LAST_INDEX]
    }

    private fun toJson(frame: MutableFrame): JsonObject = buildJsonObject {
        put("name", frame.name)
        put("value", frame.value)
        put(
            "children",
            buildJsonArray {
                frame.children.values.sortedByDescending { it.value }
                    .forEach { add(toJson(it)) }
            }
        )
    }

    private fun emptyFlamegraph(): JsonObject = buildJsonObject {
        put("frames", JsonArray(emptyList()))
    }

    private val JFR_MAGIC = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'R'.code.toByte(), 0x00)
}
