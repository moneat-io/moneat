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

package com.moneat.datadog.decompression

import com.moneat.datadog.decompression.ProtoWireConstants as Wire

import com.google.protobuf.CodedInputStream
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_3
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_4
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_5
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_7
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_8
import com.moneat.datadog.models.DatadogEvent
import com.moneat.datadog.models.DdContainerImagePayload
import com.moneat.datadog.models.DdSbomPackage
import com.moneat.datadog.models.DdSbomPayload

/**
 * Decodes Datadog agent-payload protobufs for miscellaneous V2 intake endpoints.
 *
 * IDL source:
 * - github.com/DataDog/agent-payload/proto/contimage/contimage.proto
 * - github.com/DataDog/agent-payload/proto/sbom/sbom.proto
 * - github.com/DataDog/agent-payload/proto/contlcycle/contlcycle.proto
 */
object MiscPayloadDecoder {
    private const val FIELD_10 = 10
    private const val FIELD_11 = 11
    private const val FIELD_12 = 12
    private const val FIELD_14 = 14
    private const val FIELD_15 = 15
    private const val FIELD_16 = 16
    private const val FIELD_17 = 17
    private const val FIELD_21 = 21
    private const val FIELD_6 = 6
    private const val FIELD_9 = 9
    private const val FIELD_WIRE_LEN = 2
    private const val FIELD_WIRE_VARINT = 0
    private const val SBOM_TYPE_CONTAINER_IMAGE_LAYERS = 1
    private const val SBOM_TYPE_CONTAINER_FILE_SYSTEM = 2
    private const val SBOM_TYPE_HOST_FILE_SYSTEM = 3
    private const val SBOM_TYPE_CI_PIPELINE = 4
    private const val SBOM_TYPE_HOST_IMAGE = 5
    private const val SBOM_TYPE_SERVERLESS_FUNCTION = 6
    private const val CYCLONEDX_CLASSIFICATION_APPLICATION = 1
    private const val CYCLONEDX_CLASSIFICATION_FRAMEWORK = 2
    private const val CYCLONEDX_CLASSIFICATION_LIBRARY = 3
    private const val CYCLONEDX_CLASSIFICATION_OPERATING_SYSTEM = 4
    private const val CYCLONEDX_CLASSIFICATION_CONTAINER = 7
    private const val OBJECT_KIND_CONTAINER = 0
    private const val OBJECT_KIND_POD = 1
    private const val OBJECT_KIND_TASK = 2
    private const val UNIX_NANOS_THRESHOLD = 10_000_000_000_000_000L
    private const val UNIX_MILLIS_THRESHOLD = 10_000_000_000L
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val MILLIS_PER_SECOND = 1_000L

    fun decodeContainerImages(proto: ByteArray): List<DdContainerImagePayload> {
        val input = CodedInputStream.newInstance(proto)
        var host = ""
        var source = ""
        val images = mutableListOf<ContainerImageProto>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(2, FIELD_WIRE_LEN) -> host = input.readString()
                Wire.tag(FIELD_3, FIELD_WIRE_LEN) -> images += decodeContainerImage(input.readByteArray())
                Wire.tag(FIELD_4, FIELD_WIRE_LEN) -> source = input.readString()
                else -> input.skipField(tag)
            }
        }

        return images.map { image ->
            DdContainerImagePayload(
                imageName = image.name.ifBlank { image.shortName },
                imageTag = image.imageTag(),
                digest = image.digest.ifBlank { image.repoDigests.firstOrNull().orEmpty() },
                registry = image.registry,
                sizeBytes = image.sizeBytes,
                os = image.osName,
                architecture = image.architecture,
                layers = image.layerCount,
                tags = buildList {
                    addAll(image.ddTags)
                    addTag("host", host)
                    addTag("source", source)
                    addTag("image_id", image.id)
                    addTag("short_name", image.shortName)
                    image.repoTags.firstOrNull()?.let { addTag("repo_tag", it) }
                    image.repoDigests.firstOrNull()?.let { addTag("repo_digest", it) }
                },
            )
        }
    }

    fun decodeSbom(proto: ByteArray): DdSbomPayload {
        val input = CodedInputStream.newInstance(proto)
        var host = ""
        var source = ""
        var env = ""
        val entities = mutableListOf<SbomEntityProto>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(2, FIELD_WIRE_LEN) -> host = input.readString()
                Wire.tag(FIELD_3, FIELD_WIRE_LEN) -> source = input.readString()
                Wire.tag(FIELD_4, FIELD_WIRE_LEN) -> entities += decodeSbomEntity(input.readByteArray())
                Wire.tag(FIELD_5, FIELD_WIRE_LEN) -> env = input.readString()
                else -> input.skipField(tag)
            }
        }

        val packages = mutableListOf<DdSbomPackage>()
        val tags = mutableListOf<String>()
        entities.forEach { entity ->
            tags += entity.ddTags
            tags.addTag("source", source)
            tags.addTag("env", env)
            tags.addTag("entity_id", entity.id)
            tags.addTag("entity_type", entity.typeName())
            entity.repoTags.firstOrNull()?.let { tags.addTag("repo_tag", it) }
            entity.repoDigests.firstOrNull()?.let { tags.addTag("repo_digest", it) }

            packages += entity.components.map { component ->
                DdSbomPackage(
                    name = component.name,
                    version = component.version,
                    type = component.packageType(),
                    cveIds = component.cveIds,
                )
            }
        }

        return DdSbomPayload(
            host = host,
            containerId = entities.firstOrNull()?.id.orEmpty(),
            imageName = entities.firstNotNullOfOrNull { it.imageName() }.orEmpty(),
            packages = packages,
            tags = tags.distinct(),
        )
    }

    fun decodeContainerLifecycleEvents(proto: ByteArray): List<DatadogEvent> {
        val input = CodedInputStream.newInstance(proto)
        var host = ""
        var objectKind = 0
        var clusterId = ""
        val events = mutableListOf<ContainerLifecycleEventProto>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(2, FIELD_WIRE_LEN) -> host = input.readString()
                Wire.tag(FIELD_3, FIELD_WIRE_VARINT) -> objectKind = input.readEnum()
                Wire.tag(FIELD_4, FIELD_WIRE_LEN) ->
                    events += decodeContainerLifecycleEvent(input.readByteArray())
                Wire.tag(FIELD_5, FIELD_WIRE_LEN) -> clusterId = input.readString()
                else -> input.skipField(tag)
            }
        }

        return events.map { event ->
            val kind = event.kind.ifBlank { objectKindName(objectKind) }
            val objectId = event.objectId
            val title = "Datadog container lifecycle ${event.eventTypeName}: $kind $objectId"
            DatadogEvent(
                title = title,
                text = event.description(kind),
                dateHappened = event.exitTimestamp?.let(::normalizeUnixSeconds),
                priority = "normal",
                host = host,
                tags = buildList {
                    addTag("event_type", event.eventTypeName)
                    addTag("object_kind", kind)
                    addTag("source", event.source)
                    addTag("cluster_id", clusterId)
                    addTag("object_id", objectId)
                    event.exitCode?.let { addTag("exit_code", it.toString()) }
                    addAll(event.extraTags)
                },
                alertType = "info",
                aggregationKey = "datadog_container_lifecycle:$kind:$objectId",
                sourceTypeName = "datadog_container_lifecycle",
                deviceName = objectId,
            )
        }
    }

    private fun decodeContainerImage(bytes: ByteArray): ContainerImageProto {
        val input = CodedInputStream.newInstance(bytes)
        val image = ContainerImageProto()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, FIELD_WIRE_LEN) -> image.id = input.readString()
                Wire.tag(2, FIELD_WIRE_LEN) -> image.name = input.readString()
                Wire.tag(FIELD_3, FIELD_WIRE_LEN) -> image.registry = input.readString()
                Wire.tag(FIELD_4, FIELD_WIRE_LEN) -> image.shortName = input.readString()
                Wire.tag(FIELD_5, FIELD_WIRE_LEN) -> image.repoTags += input.readString()
                Wire.tag(FIELD_6, FIELD_WIRE_LEN) -> image.digest = input.readString()
                Wire.tag(FIELD_7, FIELD_WIRE_VARINT) -> image.sizeBytes = input.readInt64()
                Wire.tag(FIELD_8, FIELD_WIRE_LEN) -> image.repoDigests += input.readString()
                Wire.tag(FIELD_9, FIELD_WIRE_LEN) -> image.applyOs(input.readByteArray())
                Wire.tag(FIELD_10, FIELD_WIRE_LEN) -> {
                    input.readByteArray()
                    image.layerCount += 1
                }
                Wire.tag(FIELD_12, FIELD_WIRE_LEN) -> image.ddTags += input.readString()
                else -> input.skipField(tag)
            }
        }

        return image
    }

    private fun decodeSbomEntity(bytes: ByteArray): SbomEntityProto {
        val input = CodedInputStream.newInstance(bytes)
        val entity = SbomEntityProto()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, FIELD_WIRE_VARINT) -> entity.type = input.readEnum()
                Wire.tag(2, FIELD_WIRE_LEN) -> entity.id = input.readString()
                Wire.tag(FIELD_4, FIELD_WIRE_LEN) -> entity.repoTags += input.readString()
                Wire.tag(FIELD_7, FIELD_WIRE_LEN) -> entity.ddTags += input.readString()
                Wire.tag(FIELD_10, FIELD_WIRE_LEN) ->
                    entity.components += decodeCycloneDxComponents(input.readByteArray())
                Wire.tag(FIELD_11, FIELD_WIRE_VARINT) -> entity.status = input.readEnum()
                Wire.tag(FIELD_12, FIELD_WIRE_LEN) -> entity.error = input.readString()
                Wire.tag(FIELD_14, FIELD_WIRE_LEN) -> entity.repoDigests += input.readString()
                Wire.tag(FIELD_15, FIELD_WIRE_LEN) -> entity.kernelVersion = input.readString()
                Wire.tag(FIELD_16, FIELD_WIRE_LEN) -> entity.cpuArchitecture = input.readString()
                else -> input.skipField(tag)
            }
        }

        return entity
    }

    private fun decodeCycloneDxComponents(bytes: ByteArray): List<CycloneDxComponentProto> {
        val input = CodedInputStream.newInstance(bytes)
        val components = mutableListOf<CycloneDxComponentProto>()
        val cvesByRef = mutableMapOf<String, MutableSet<String>>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(FIELD_5, FIELD_WIRE_LEN) ->
                    components += decodeCycloneDxComponent(input.readByteArray())
                Wire.tag(FIELD_10, FIELD_WIRE_LEN) ->
                    decodeCycloneDxVulnerability(input.readByteArray(), cvesByRef)
                else -> input.skipField(tag)
            }
        }

        return components.flatMap { component ->
            component.withCves(cvesByRef).flatten()
        }
    }

    private fun decodeCycloneDxComponent(bytes: ByteArray): CycloneDxComponentProto {
        val input = CodedInputStream.newInstance(bytes)
        val component = CycloneDxComponentProto()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, FIELD_WIRE_VARINT) -> component.classification = input.readEnum()
                Wire.tag(FIELD_3, FIELD_WIRE_LEN) -> component.bomRef = input.readString()
                Wire.tag(FIELD_7, FIELD_WIRE_LEN) -> component.group = input.readString()
                Wire.tag(FIELD_8, FIELD_WIRE_LEN) -> component.name = input.readString()
                Wire.tag(FIELD_9, FIELD_WIRE_LEN) -> component.version = input.readString()
                Wire.tag(FIELD_16, FIELD_WIRE_LEN) -> component.purl = input.readString()
                Wire.tag(FIELD_21, FIELD_WIRE_LEN) ->
                    component.components += decodeCycloneDxComponent(input.readByteArray())
                else -> input.skipField(tag)
            }
        }

        return component
    }

    private fun decodeCycloneDxVulnerability(
        bytes: ByteArray,
        cvesByRef: MutableMap<String, MutableSet<String>>,
    ) {
        val input = CodedInputStream.newInstance(bytes)
        var vulnerabilityId = ""
        val refs = mutableListOf<String>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(2, FIELD_WIRE_LEN) -> vulnerabilityId = input.readString()
                Wire.tag(FIELD_17, FIELD_WIRE_LEN) ->
                    refs += decodeVulnerabilityAffects(input.readByteArray())
                else -> input.skipField(tag)
            }
        }

        if (vulnerabilityId.isBlank()) return
        refs.filter { it.isNotBlank() }.forEach { ref ->
            cvesByRef.getOrPut(ref) { mutableSetOf() } += vulnerabilityId
        }
    }

    private fun decodeVulnerabilityAffects(bytes: ByteArray): String {
        val input = CodedInputStream.newInstance(bytes)
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, FIELD_WIRE_LEN) -> return input.readString()
                else -> input.skipField(tag)
            }
        }
        return ""
    }

    private fun decodeContainerLifecycleEvent(bytes: ByteArray): ContainerLifecycleEventProto {
        val input = CodedInputStream.newInstance(bytes)
        var eventType = 0
        var event = ContainerLifecycleEventProto()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, FIELD_WIRE_VARINT) -> eventType = input.readEnum()
                Wire.tag(2, FIELD_WIRE_LEN) -> event = decodeContainerEvent(input.readByteArray())
                Wire.tag(FIELD_3, FIELD_WIRE_LEN) -> event = decodePodEvent(input.readByteArray())
                Wire.tag(FIELD_4, FIELD_WIRE_LEN) -> event = decodeTaskEvent(input.readByteArray())
                else -> input.skipField(tag)
            }
        }

        event.eventTypeName = when (eventType) {
            0 -> "delete"
            else -> "unknown"
        }
        return event
    }

    private fun decodeContainerEvent(bytes: ByteArray): ContainerLifecycleEventProto {
        val input = CodedInputStream.newInstance(bytes)
        val event = ContainerLifecycleEventProto(kind = "container")

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, FIELD_WIRE_LEN) -> event.objectId = input.readString()
                Wire.tag(2, FIELD_WIRE_LEN) -> event.source = input.readString()
                Wire.tag(FIELD_3, FIELD_WIRE_VARINT) -> event.exitCode = input.readInt32()
                Wire.tag(FIELD_4, FIELD_WIRE_VARINT) -> event.exitTimestamp = input.readInt64()
                Wire.tag(FIELD_5, FIELD_WIRE_LEN) ->
                    event.extraTags += decodeLifecycleOwner(input.readByteArray())
                else -> input.skipField(tag)
            }
        }

        return event
    }

    private fun decodePodEvent(bytes: ByteArray): ContainerLifecycleEventProto {
        val input = CodedInputStream.newInstance(bytes)
        val event = ContainerLifecycleEventProto(kind = "pod")

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, FIELD_WIRE_LEN) -> event.objectId = input.readString()
                Wire.tag(2, FIELD_WIRE_LEN) -> event.source = input.readString()
                Wire.tag(FIELD_3, FIELD_WIRE_VARINT) -> event.exitTimestamp = input.readInt64()
                else -> input.skipField(tag)
            }
        }

        return event
    }

    private fun decodeTaskEvent(bytes: ByteArray): ContainerLifecycleEventProto {
        val input = CodedInputStream.newInstance(bytes)
        val event = ContainerLifecycleEventProto(kind = "task")

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, FIELD_WIRE_LEN) -> event.objectId = input.readString()
                Wire.tag(2, FIELD_WIRE_LEN) -> event.source = input.readString()
                Wire.tag(FIELD_3, FIELD_WIRE_VARINT) -> event.exitTimestamp = input.readInt64()
                else -> input.skipField(tag)
            }
        }

        return event
    }

    private fun decodeLifecycleOwner(bytes: ByteArray): List<String> {
        val input = CodedInputStream.newInstance(bytes)
        var ownerType = ""
        var ownerUid = ""

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, FIELD_WIRE_VARINT) -> ownerType = objectKindName(input.readEnum())
                Wire.tag(2, FIELD_WIRE_LEN) -> ownerUid = input.readString()
                else -> input.skipField(tag)
            }
        }

        return buildList {
            addTag("owner_type", ownerType)
            addTag("owner_uid", ownerUid)
        }
    }

    private class ContainerImageProto {
        var id = ""
        var name = ""
        var registry = ""
        var shortName = ""
        var digest = ""
        var sizeBytes = 0L
        var osName = ""
        var architecture = ""
        var layerCount = 0
        val repoTags = mutableListOf<String>()
        val repoDigests = mutableListOf<String>()
        val ddTags = mutableListOf<String>()

        fun applyOs(bytes: ByteArray) {
            val input = CodedInputStream.newInstance(bytes)
            while (!input.isAtEnd) {
                when (val tag = input.readTag()) {
                    0 -> break
                    Wire.tag(1, FIELD_WIRE_LEN) -> osName = input.readString()
                    Wire.tag(FIELD_3, FIELD_WIRE_LEN) -> architecture = input.readString()
                    else -> input.skipField(tag)
                }
            }
        }

        fun imageTag(): String {
            val repoTag = repoTags.firstOrNull() ?: return ""
            val fullName = name.ifBlank { shortName }
            return if (fullName.isNotBlank() && repoTag.startsWith("$fullName:")) {
                repoTag.removePrefix("$fullName:")
            } else {
                repoTag.substringAfterLast(':', "")
            }
        }
    }

    private class SbomEntityProto {
        var type = 0
        var id = ""
        var status = 0
        var error = ""
        var kernelVersion = ""
        var cpuArchitecture = ""
        val repoTags = mutableListOf<String>()
        val repoDigests = mutableListOf<String>()
        val ddTags = mutableListOf<String>()
        val components = mutableListOf<CycloneDxComponentProto>()

        fun imageName(): String? = repoTags.firstOrNull()?.substringBefore(':')?.ifBlank { null } ?: id.ifBlank { null }

        fun typeName(): String = when (type) {
            SBOM_TYPE_CONTAINER_IMAGE_LAYERS -> "container_image_layers"
            SBOM_TYPE_CONTAINER_FILE_SYSTEM -> "container_file_system"
            SBOM_TYPE_HOST_FILE_SYSTEM -> "host_file_system"
            SBOM_TYPE_CI_PIPELINE -> "ci_pipeline"
            SBOM_TYPE_HOST_IMAGE -> "host_image"
            SBOM_TYPE_SERVERLESS_FUNCTION -> "serverless_function"
            else -> "unspecified"
        }
    }

    private class CycloneDxComponentProto {
        var classification = 0
        var bomRef = ""
        var group = ""
        var name = ""
        var version = ""
        var purl = ""
        var cveIds = emptyList<String>()
        val components = mutableListOf<CycloneDxComponentProto>()

        fun withCves(cvesByRef: Map<String, Set<String>>): CycloneDxComponentProto {
            cveIds = cvesByRef[bomRef]?.toList().orEmpty()
            components.forEach { it.withCves(cvesByRef) }
            return this
        }

        fun flatten(): List<CycloneDxComponentProto> {
            val current = if (name.isBlank()) emptyList() else listOf(this)
            return current + components.flatMap { it.flatten() }
        }

        fun packageType(): String {
            val purlType = purl.removePrefix("pkg:").substringBefore('/').takeIf { it != purl && it.isNotBlank() }
            if (purlType != null) return purlType
            return when (classification) {
                CYCLONEDX_CLASSIFICATION_APPLICATION -> "application"
                CYCLONEDX_CLASSIFICATION_FRAMEWORK -> "framework"
                CYCLONEDX_CLASSIFICATION_LIBRARY -> "library"
                CYCLONEDX_CLASSIFICATION_OPERATING_SYSTEM -> "operating-system"
                CYCLONEDX_CLASSIFICATION_CONTAINER -> "container"
                else -> "unknown"
            }
        }
    }

    private class ContainerLifecycleEventProto(
        var kind: String = "",
        var objectId: String = "",
        var source: String = "",
        var exitCode: Int? = null,
        var exitTimestamp: Long? = null,
        var eventTypeName: String = "unknown",
        val extraTags: MutableList<String> = mutableListOf(),
    ) {
        fun description(kind: String): String {
            val exit = exitCode?.let { " exit_code=$it" }.orEmpty()
            return "$kind $objectId lifecycle event: $eventTypeName from $source$exit"
        }
    }

    private fun MutableList<String>.addTag(key: String, value: String) {
        if (value.isNotBlank()) add("$key:$value")
    }

    private fun objectKindName(kind: Int): String {
        return when (kind) {
            OBJECT_KIND_CONTAINER -> "container"
            OBJECT_KIND_POD -> "pod"
            OBJECT_KIND_TASK -> "task"
            else -> "unknown"
        }
    }

    private fun normalizeUnixSeconds(timestamp: Long): Long {
        return when {
            timestamp > UNIX_NANOS_THRESHOLD -> timestamp / NANOS_PER_SECOND
            timestamp > UNIX_MILLIS_THRESHOLD -> timestamp / MILLIS_PER_SECOND
            else -> timestamp
        }
    }
}
