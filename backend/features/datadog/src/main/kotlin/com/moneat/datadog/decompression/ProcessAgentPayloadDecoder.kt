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
import com.google.protobuf.CodedOutputStream
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_3
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_4
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_5
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_6
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_7
import com.moneat.datadog.decompression.ProtoWireConstants.FIELD_8
import com.moneat.datadog.models.DatadogConnection
import com.moneat.datadog.models.DatadogConnectionsPayload
import com.moneat.datadog.models.DatadogContainer
import com.moneat.datadog.models.DatadogContainerPayload
import com.moneat.datadog.models.DatadogProcess
import com.moneat.datadog.models.DatadogProcessPayload
import com.moneat.ingest.DecompressionService
import java.io.ByteArrayOutputStream

/**
 * Decodes the Datadog process-agent MessageV3 binary wire format.
 *
 * Header layout (16 bytes, little-endian):
 *   byte 0   : version  (uint8) — must be 3
 *   byte 1   : encoding (uint8) — 0=Protobuf, 2=ZstdPB, 4=Zstd1xPB, 5=ZstdPBxNoCgo
 *   byte 2   : type     (uint8) — 12=Proc, 22=Connections, 39=Container, 53=ProcDiscovery
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
    private const val PROTO_WIRE_FLOAT = 5 // 32-bit float wire type
    private const val PROTO_FIELD_10 = 10
    private const val PROTO_FIELD_11 = 11
    private const val PROTO_FIELD_12 = 12
    private const val PROTO_FIELD_16 = 16
    private const val PROTO_FIELD_17 = 17
    private const val PROTO_FIELD_19 = 19
    private const val PROTO_FIELD_20 = 20
    private const val PROTO_FIELD_21 = 21
    private const val PROTO_FIELD_23 = 23
    private const val PROTO_FIELD_26 = 26
    private const val IPV6_PROTOCOL_SUFFIX = "6"

    // Type constants
    const val TYPE_COLLECTOR_PROC: Int = 12
    const val TYPE_COLLECTOR_CONNECTIONS: Int = 22
    const val TYPE_RES_COLLECTOR: Int = 23
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
    private val CONNECTION_DIRECTIONS = mapOf(
        1 to "incoming",
        2 to "outgoing",
        3 to "local",
        4 to "none"
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

    fun encodeCollectorResponse(
        activeClients: Int = 0,
        intervalSeconds: Int = 0,
    ): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        header[0] = MSG_VERSION_V3
        header[1] = ENC_PROTOBUF.toByte()
        header[2] = TYPE_RES_COLLECTOR.toByte()

        val responseHeader = encodeProto {
            writeInt32(FIELD_4, TYPE_RES_COLLECTOR)
        }
        val status = encodeProto {
            writeInt32(1, activeClients)
            writeInt32(2, intervalSeconds)
        }
        val body = encodeProto {
            writeByteArray(1, responseHeader)
            writeByteArray(FIELD_3, status)
        }
        return header + body
    }

    // ── CollectorContainer (type 39) ──────────
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
                Wire.tag(1, Wire.WIRE_LENGTH_DELIMITED) -> hostName = input.readString()
                // field 3, LEN: repeated Container
                Wire.tag(FIELD_3, Wire.WIRE_LENGTH_DELIMITED) ->
                    containers += decodeContainer(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return DatadogContainerPayload(host = hostName, containers = containers)
    }

    // ── CollectorConnections (type 22) ───────
    // Proto fields:
    //   2 (string)           : hostName
    //   3 (repeated message) : connections

    fun decodeCollectorConnections(proto: ByteArray): DatadogConnectionsPayload {
        val input = CodedInputStream.newInstance(proto)
        var hostName = ""
        val connections = mutableListOf<DatadogConnection>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(2, Wire.WIRE_LENGTH_DELIMITED) -> hostName = input.readString()
                Wire.tag(FIELD_3, Wire.WIRE_LENGTH_DELIMITED) ->
                    connections += decodeConnection(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return DatadogConnectionsPayload(host = hostName, connections = connections)
    }

    // Connection proto fields we care about:
    //   1  (int32)  : pid
    //   5  (message): laddr
    //   6  (message): raddr
    //   10 (enum)   : family (0=v4, 1=v6)
    //   11 (enum)   : type (0=tcp, 1=udp)
    //   16 (uint64) : lastBytesSent
    //   17 (uint64) : lastBytesReceived
    //   19 (enum)   : direction
    private fun decodeConnection(bytes: ByteArray): DatadogConnection {
        val input = CodedInputStream.newInstance(bytes)
        var pid = 0
        var localAddr = NetworkAddr()
        var remoteAddr = NetworkAddr()
        var family = 0
        var type = 0
        var bytesSent = 0L
        var bytesReceived = 0L
        var direction = 0

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, Wire.WIRE_VARINT) -> pid = input.readInt32()
                Wire.tag(FIELD_5, Wire.WIRE_LENGTH_DELIMITED) -> localAddr = decodeAddr(input.readByteArray())
                Wire.tag(FIELD_6, Wire.WIRE_LENGTH_DELIMITED) -> remoteAddr = decodeAddr(input.readByteArray())
                Wire.tag(PROTO_FIELD_10, Wire.WIRE_VARINT) -> family = input.readEnum()
                Wire.tag(PROTO_FIELD_11, Wire.WIRE_VARINT) -> type = input.readEnum()
                Wire.tag(PROTO_FIELD_16, Wire.WIRE_VARINT) -> bytesSent = input.readUInt64()
                Wire.tag(PROTO_FIELD_17, Wire.WIRE_VARINT) -> bytesReceived = input.readUInt64()
                Wire.tag(PROTO_FIELD_19, Wire.WIRE_VARINT) -> direction = input.readEnum()
                else -> input.skipField(tag)
            }
        }

        val familyName = if (family == 1) "IPv6" else "IPv4"
        return DatadogConnection(
            pid = pid,
            localAddr = localAddr.ip,
            localPort = localAddr.port,
            remoteAddr = remoteAddr.ip,
            remotePort = remoteAddr.port,
            protocol = connectionProtocol(type, familyName),
            family = familyName,
            direction = CONNECTION_DIRECTIONS[direction] ?: "",
            bytesSent = bytesSent,
            bytesRecv = bytesReceived,
        )
    }

    private data class NetworkAddr(
        val ip: String = "",
        val port: Int = 0,
    )

    // Addr proto fields:
    //   2 (string): ip
    //   3 (int32) : port
    private fun decodeAddr(bytes: ByteArray): NetworkAddr {
        val input = CodedInputStream.newInstance(bytes)
        var ip = ""
        var port = 0

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(2, Wire.WIRE_LENGTH_DELIMITED) -> ip = input.readString()
                Wire.tag(FIELD_3, Wire.WIRE_VARINT) -> port = input.readInt32()
                else -> input.skipField(tag)
            }
        }
        return NetworkAddr(ip = ip, port = port)
    }

    private fun connectionProtocol(type: Int, family: String): String {
        val baseProtocol = if (type == 1) "udp" else "tcp"
        return if (family == "IPv6") "$baseProtocol$IPV6_PROTOCOL_SUFFIX" else baseProtocol
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
                Wire.tag(2, Wire.WIRE_LENGTH_DELIMITED) -> id = input.readString()
                Wire.tag(FIELD_3, Wire.WIRE_LENGTH_DELIMITED) -> name = input.readString()
                Wire.tag(FIELD_4, Wire.WIRE_LENGTH_DELIMITED) -> image = input.readString()
                Wire.tag(FIELD_6, Wire.WIRE_VARINT) -> memLimit = input.readUInt64()
                Wire.tag(FIELD_8, Wire.WIRE_VARINT) -> state = input.readEnum()
                Wire.tag(PROTO_FIELD_16, PROTO_WIRE_FLOAT) -> netRcvdBps = input.readFloat()
                Wire.tag(PROTO_FIELD_17, PROTO_WIRE_FLOAT) -> netSentBps = input.readFloat()
                Wire.tag(PROTO_FIELD_20, PROTO_WIRE_FLOAT) -> totalPct = input.readFloat()
                Wire.tag(PROTO_FIELD_21, Wire.WIRE_VARINT) -> memRss = input.readUInt64()
                Wire.tag(PROTO_FIELD_26, Wire.WIRE_LENGTH_DELIMITED) -> tags += input.readString()
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

    // ── CollectorProc (type 12) ──────────────
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
                Wire.tag(2, Wire.WIRE_LENGTH_DELIMITED) -> hostName = input.readString()
                Wire.tag(FIELD_3, Wire.WIRE_LENGTH_DELIMITED) ->
                    processes += decodeProcess(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return DatadogProcessPayload(host = hostName, processes = processes)
    }

    // ── CollectorProcDiscovery (type 53) ─────
    // Proto fields:
    //   1 (string)           : hostName
    //   4 (repeated message) : processDiscoveries

    fun decodeCollectorProcDiscovery(proto: ByteArray): DatadogProcessPayload {
        val input = CodedInputStream.newInstance(proto)
        var hostName = ""
        val processes = mutableListOf<DatadogProcess>()

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, Wire.WIRE_LENGTH_DELIMITED) -> hostName = input.readString()
                Wire.tag(FIELD_4, Wire.WIRE_LENGTH_DELIMITED) ->
                    processes += decodeProcessDiscovery(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return DatadogProcessPayload(host = hostName, processes = processes)
    }

    private fun decodeProcessDiscovery(bytes: ByteArray): DatadogProcess {
        val input = CodedInputStream.newInstance(bytes)
        var pid = 0
        var nsPid = 0
        var cmdName = ""
        var cmdFull = ""
        var userName = ""

        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, Wire.WIRE_VARINT) -> pid = input.readInt32()
                Wire.tag(2, Wire.WIRE_VARINT) -> nsPid = input.readInt32()
                Wire.tag(FIELD_4, Wire.WIRE_LENGTH_DELIMITED) -> {
                    val command = decodeCommand(input.readByteArray())
                    cmdName = command.first
                    cmdFull = command.second
                }
                Wire.tag(FIELD_5, Wire.WIRE_LENGTH_DELIMITED) -> userName = decodeProcessUser(input.readByteArray())
                else -> input.skipField(tag)
            }
        }
        return DatadogProcess(
            pid = if (pid != 0) pid else nsPid,
            name = cmdName,
            command = cmdFull,
            user = userName,
            state = "unknown",
        )
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
                Wire.tag(2, Wire.WIRE_VARINT) -> pid = input.readInt32()
                Wire.tag(FIELD_4, Wire.WIRE_LENGTH_DELIMITED) -> {
                    val r = decodeCommand(
                        input.readByteArray()
                    )
                    cmdName = r.first
                    cmdFull = r.second
                }
                Wire.tag(FIELD_5, Wire.WIRE_LENGTH_DELIMITED) -> userName = decodeProcessUser(input.readByteArray())
                Wire.tag(FIELD_7, Wire.WIRE_LENGTH_DELIMITED) -> {
                    val r = decodeMemoryStat(
                        input.readByteArray()
                    )
                    memRss = r.first
                    memVms = r.second
                }
                Wire.tag(FIELD_8, Wire.WIRE_LENGTH_DELIMITED) -> {
                    val r = decodeCpuStat(
                        input.readByteArray()
                    )
                    cpuTotalPct = r.first
                    numThreads = r.second
                }
                Wire.tag(PROTO_FIELD_11, Wire.WIRE_VARINT) -> openFdCount = input.readInt32()
                Wire.tag(PROTO_FIELD_12, Wire.WIRE_VARINT) -> state = input.readEnum()
                Wire.tag(PROTO_FIELD_23, Wire.WIRE_LENGTH_DELIMITED) -> tags += input.readString()
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
    private fun decodeCommand(bytes: ByteArray): Pair<String, String> {
        val input = CodedInputStream.newInstance(bytes)
        val args = mutableListOf<String>()
        var exe = ""
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(1, Wire.WIRE_LENGTH_DELIMITED) -> args += input.readString()
                Wire.tag(FIELD_8, Wire.WIRE_LENGTH_DELIMITED) -> exe = input.readString()
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
                Wire.tag(1, Wire.WIRE_LENGTH_DELIMITED) -> return input.readString()
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
                Wire.tag(1, Wire.WIRE_VARINT) -> rss = input.readUInt64()
                Wire.tag(2, Wire.WIRE_VARINT) -> vms = input.readUInt64()
                else -> input.skipField(tag)
            }
        }
        return Pair(rss, vms)
    }

    // CPUStat: field 2 (float) totalPct, field 5 (int32) numThreads
    private fun decodeCpuStat(bytes: ByteArray): Pair<Float, Int> {
        val input = CodedInputStream.newInstance(bytes)
        var totalPct = 0f
        var numThreads = 0
        while (!input.isAtEnd) {
            when (val tag = input.readTag()) {
                0 -> break
                Wire.tag(2, PROTO_WIRE_FLOAT) -> totalPct = input.readFloat()
                Wire.tag(FIELD_5, Wire.WIRE_VARINT) -> numThreads = input.readInt32()
                else -> input.skipField(tag)
            }
        }
        return Pair(totalPct, numThreads)
    }

    private fun encodeProto(block: CodedOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val coded = CodedOutputStream.newInstance(output)
        coded.block()
        coded.flush()
        return output.toByteArray()
    }
}
