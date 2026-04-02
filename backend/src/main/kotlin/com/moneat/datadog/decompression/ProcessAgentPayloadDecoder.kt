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

import com.google.protobuf.CodedInputStream
import com.moneat.datadog.models.DatadogContainer
import com.moneat.datadog.models.DatadogContainerPayload
import com.moneat.datadog.models.DatadogProcess
import com.moneat.datadog.models.DatadogProcessPayload

/**
 * Decodes the Datadog process-agent MessageV3 binary wire format.
 *
 * Header layout (16 bytes, little-endian):
 *   byte 0   : version  (uint8) — must be 3
 *   byte 1   : encoding (uint8) — 0=Protobuf, 2=ZstdPB, 4=Zstd1xPB, 5=ZstdPBxNoCgo
 *   byte 2   : type     (uint8) — 12=Proc, 39=Container, 53=ProcDiscovery
 *   byte 3   : subscriptionID (uint8, unused by agent)
 *   bytes 4-7: orgID    (int32 LE, unused by agent)
 *   bytes 8-15: timestamp (int64 LE)
 *   bytes 16+: [zstd-]compressed protobuf body
 */
object ProcessAgentPayloadDecoder {

    private const val HEADER_SIZE = 16
    private const val MSG_VERSION_V3: Byte = 3

    // Encoding constants from agent-payload/v5/process/message.go
    private const val ENC_PROTOBUF: Int = 0
    private const val ENC_ZSTD_PB: Int = 2
    private const val ENC_ZSTD_1X_PB: Int = 4
    private const val ENC_ZSTD_PB_NO_CGO: Int = 5

    // Protobuf wire format constants
    private const val PROTO_FIELD_SHIFT = 3
    private const val PROTO_PAYLOAD_ITEMS_FIELD = 3

    // Type constants
    const val TYPE_COLLECTOR_PROC: Int = 12
    const val TYPE_COLLECTOR_CONTAINER: Int = 39
    const val TYPE_COLLECTOR_PROC_DISCOVERY: Int = 53

    private val CONTAINER_STATES = mapOf(
        0 to "unknown",
        1 to "created",
        2 to "restarting",
        3 to "running",
        4 to "paused",
        5 to "exited",
        6 to "dead"
    )
    private val PROCESS_STATES = mapOf(
        0 to "unknown",
        1 to "D",
        2 to "R",
        3 to "S",
        4 to "T",
        5 to "W",
        6 to "X",
        7 to "Z"
    )

    data class MessageHeader(val version: Int, val encoding: Int, val type: Int)

    /** Returns null if the data is not a valid MessageV3 payload. */
    fun readHeader(data: ByteArray): MessageHeader? {
        if (data.size < HEADER_SIZE) return null
        if (data[0] != MSG_VERSION_V3) return null
        return MessageHeader(
            version = data[0].toInt() and 0xFF,
            encoding = data[1].toInt() and 0xFF,
            type = data[2].toInt() and 0xFF
        )
    }

    /** Decompresses the body portion (bytes 16+) of a MessageV3 payload. */
    fun decompressBody(data: ByteArray, encoding: Int): ByteArray {
        val body = data.copyOfRange(HEADER_SIZE, data.size)
        return when (encoding) {
            ENC_PROTOBUF -> body
            ENC_ZSTD_PB, ENC_ZSTD_1X_PB, ENC_ZSTD_PB_NO_CGO ->
                DecompressionService.decompress(body, "zstd")
            else -> body
        }
    }

    // ── CollectorContainer (type 39) ──────────────────────────────────────────
    // Proto fields:
    //   1 (string)           : hostName
    //   3 (repeated message) : containers

    fun decodeCollectorContainer(proto: ByteArray): DatadogContainerPayload {
        val input = CodedInputStream.newInstance(proto)
        var hostName = ""
        val containers = mutableListOf<DatadogContainer>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                // field 1, LEN: hostName
                (1 shl PROTO_FIELD_SHIFT) or 2 -> hostName = input.readString()
                // field 3, LEN: repeated Container
                (PROTO_PAYLOAD_ITEMS_FIELD shl PROTO_FIELD_SHIFT) or 2 ->
                    containers += decodeContainer(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return DatadogContainerPayload(host = hostName, containers = containers)
    }

    // Container proto fields we care about:
    //   2  (string): id           (containerId)
    //   3  (string): name
    //   4  (string): image
    //   6  (uint64): memoryLimit  → memLimit
    //   8  (enum)  : state
    //   16 (float) : netRcvdBps  → netRxBytes (bps, convert to long)
    //   17 (float) : netSentBps  → netTxBytes
    //   20 (float) : totalPct    → cpuPercent
    //   21 (uint64): memRss      → memUsage
    //   26 (string, repeated): tags
    @Suppress("MagicNumber")
    private fun decodeContainer(bytes: ByteArray): DatadogContainer {
        val input = CodedInputStream.newInstance(bytes)
        var id = ""
        var name = ""
        var image = ""
        var state = 0
        var memLimit = 0L
        var memRss = 0L
        var netRcvdBps = 0f
        var netSentBps = 0f
        var totalPct = 0f
        val tags = mutableListOf<String>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (2 shl 3) or 2 -> id = input.readString()
                (3 shl 3) or 2 -> name = input.readString()
                (4 shl 3) or 2 -> image = input.readString()
                (6 shl 3) or 0 -> memLimit = input.readUInt64()
                (8 shl 3) or 0 -> state = input.readEnum()
                (16 shl 3) or 5 -> netRcvdBps = input.readFloat()
                (17 shl 3) or 5 -> netSentBps = input.readFloat()
                (20 shl 3) or 5 -> totalPct = input.readFloat()
                (21 shl 3) or 0 -> memRss = input.readUInt64()
                (26 shl 3) or 2 -> tags += input.readString()
                else -> input.skipField(tag)
            }
        }
        return DatadogContainer(
            containerId = id,
            name = name,
            image = image,
            state = CONTAINER_STATES[state] ?: "unknown",
            cpuPercent = totalPct.toDouble(),
            memUsage = memRss,
            memLimit = memLimit,
            netRxBytes = netRcvdBps.toLong(),
            netTxBytes = netSentBps.toLong(),
            tags = tags
        )
    }

    // ── CollectorProc (type 12) ───────────────────────────────────────────────
    // Proto fields:
    //   2 (string)           : hostName
    //   3 (repeated message) : processes

    fun decodeCollectorProc(proto: ByteArray): DatadogProcessPayload {
        val input = CodedInputStream.newInstance(proto)
        var hostName = ""
        val processes = mutableListOf<DatadogProcess>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (2 shl PROTO_FIELD_SHIFT) or 2 -> hostName = input.readString()
                (PROTO_PAYLOAD_ITEMS_FIELD shl PROTO_FIELD_SHIFT) or 2 ->
                    processes += decodeProcess(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return DatadogProcessPayload(host = hostName, processes = processes)
    }

    // Process proto fields:
    //   2  (int32)  : pid
    //   4  (message): command → args[0] = name, joined = command
    //   5  (message): user    → name
    //   7  (message): memory  → rss, vms
    //   8  (message): cpu     → totalPct, numThreads
    //   11 (int32)  : openFdCount
    //   12 (enum)   : state
    //   23 (repeated string): tags
    @Suppress("MagicNumber")
    private fun decodeProcess(bytes: ByteArray): DatadogProcess {
        val input = CodedInputStream.newInstance(bytes)
        var pid = 0
        var openFdCount = 0
        var state = 0
        var cmdName = ""
        var cmdFull = ""
        var userName = ""
        var memRss = 0L
        var memVms = 0L
        var cpuTotalPct = 0f
        var numThreads = 0
        val tags = mutableListOf<String>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (2 shl 3) or 0 -> pid = input.readInt32()
                (4 shl 3) or 2 -> {
                    val r = decodeCommand(
                        input.readByteArray()
                    )
                    cmdName = r.first
                    cmdFull = r.second
                }
                (5 shl 3) or 2 -> userName = decodeProcessUser(input.readByteArray())
                (7 shl 3) or 2 -> {
                    val r = decodeMemoryStat(
                        input.readByteArray()
                    )
                    memRss = r.first
                    memVms = r.second
                }
                (8 shl 3) or 2 -> {
                    val r = decodeCpuStat(
                        input.readByteArray()
                    )
                    cpuTotalPct = r.first
                    numThreads = r.second
                }
                (11 shl 3) or 0 -> openFdCount = input.readInt32()
                (12 shl 3) or 0 -> state = input.readEnum()
                (23 shl 3) or 2 -> tags += input.readString()
                else -> input.skipField(tag)
            }
        }
        return DatadogProcess(
            pid = pid,
            name = cmdName,
            command = cmdFull,
            user = userName,
            cpuPercent = cpuTotalPct.toDouble(),
            memRss = memRss,
            memVms = memVms,
            state = PROCESS_STATES[state] ?: "S",
            threadCount = numThreads,
            openFdCount = openFdCount,
            tags = tags
        )
    }

    // Command: field 1 (repeated string) args, field 8 (string) exe
    @Suppress("MagicNumber")
    private fun decodeCommand(bytes: ByteArray): Pair<String, String> {
        val input = CodedInputStream.newInstance(bytes)
        val args = mutableListOf<String>()
        var exe = ""
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl 3) or 2 -> args += input.readString()
                (8 shl 3) or 2 -> exe = input.readString()
                else -> input.skipField(tag)
            }
        }
        val name = exe.substringAfterLast('/').ifBlank { args.firstOrNull()?.substringAfterLast('/') ?: "" }
        return Pair(name, args.joinToString(" "))
    }

    // ProcessUser: field 1 (string) name
    private fun decodeProcessUser(bytes: ByteArray): String {
        val input = CodedInputStream.newInstance(bytes)
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl PROTO_FIELD_SHIFT) or 2 -> return input.readString()
                else -> input.skipField(tag)
            }
        }
        return ""
    }

    // MemoryStat: field 1 (uint64) rss, field 2 (uint64) vms
    private fun decodeMemoryStat(bytes: ByteArray): Pair<Long, Long> {
        val input = CodedInputStream.newInstance(bytes)
        var rss = 0L
        var vms = 0L
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (1 shl PROTO_FIELD_SHIFT) or 0 -> rss = input.readUInt64()
                (2 shl PROTO_FIELD_SHIFT) or 0 -> vms = input.readUInt64()
                else -> input.skipField(tag)
            }
        }
        return Pair(rss, vms)
    }

    // CPUStat: field 2 (float) totalPct, field 5 (int32) numThreads
    @Suppress("MagicNumber")
    private fun decodeCpuStat(bytes: ByteArray): Pair<Float, Int> {
        val input = CodedInputStream.newInstance(bytes)
        var totalPct = 0f
        var numThreads = 0
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                (2 shl 3) or 5 -> totalPct = input.readFloat()
                (5 shl 3) or 0 -> numThreads = input.readInt32()
                else -> input.skipField(tag)
            }
        }
        return Pair(totalPct, numThreads)
    }
}
