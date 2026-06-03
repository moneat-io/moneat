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

import com.google.protobuf.CodedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtoDecoderTest {

    private fun buildProto(block: CodedOutputStream.() -> Unit): ByteArray {
        val sizeStream = java.io.ByteArrayOutputStream()
        val sizeCos = CodedOutputStream.newInstance(sizeStream)
        sizeCos.block()
        sizeCos.flush()
        return sizeStream.toByteArray()
    }

    @Test
    fun `MetricPayloadDecoder decodes empty payload`() {
        val result = MetricPayloadDecoder.decode(ByteArray(0))
        assertTrue(result.series.isEmpty())
    }

    @Test
    fun `MetricPayloadDecoder decodes single metric series`() {
        val seriesBytes = buildProto {
            writeString(2, "cpu.user")
            writeString(3, "env:prod")
        }
        val payload = buildProto {
            writeByteArray(1, seriesBytes)
        }
        val result = MetricPayloadDecoder.decode(payload)
        assertEquals(1, result.series.size)
        assertEquals("cpu.user", result.series[0].metric)
        assertTrue(result.series[0].tags.contains("env:prod"))
    }

    @Test
    fun `MetricPayloadDecoder decodes host from resource`() {
        val resourceBytes = buildProto {
            writeString(1, "host")
            writeString(2, "web-01")
        }
        val seriesBytes = buildProto {
            writeByteArray(1, resourceBytes)
            writeString(2, "mem.free")
        }
        val payload = buildProto {
            writeByteArray(1, seriesBytes)
        }
        val result = MetricPayloadDecoder.decode(payload)
        assertEquals("web-01", result.series[0].host)
        assertEquals("mem.free", result.series[0].metric)
    }

    @Test
    fun `MetricPayloadDecoder decodes metric type`() {
        val seriesBytes = buildProto {
            writeString(2, "test.metric")
            writeEnum(5, 1) // count
        }
        val payload = buildProto {
            writeByteArray(1, seriesBytes)
        }
        val result = MetricPayloadDecoder.decode(payload)
        assertEquals("count", result.series[0].type)
    }

    @Test
    fun `MetricPayloadDecoder decodes unit and source type name`() {
        val seriesBytes = buildProto {
            writeString(2, "disk.read")
            writeString(6, "byte")
            writeString(7, "system")
        }
        val payload = buildProto {
            writeByteArray(1, seriesBytes)
        }
        val result = MetricPayloadDecoder.decode(payload)
        assertEquals("byte", result.series[0].unit)
        assertEquals("system", result.series[0].sourceTypeName)
    }

    @Test
    fun `MetricPayloadDecoder decodes metric point`() {
        val pointBytes = buildProto {
            writeDouble(1, 42.5)
            writeInt64(2, 1700000000L)
        }
        val seriesBytes = buildProto {
            writeString(2, "test.metric")
            writeByteArray(4, pointBytes)
        }
        val payload = buildProto {
            writeByteArray(1, seriesBytes)
        }
        val result = MetricPayloadDecoder.decode(payload)
        assertEquals(1, result.series[0].points.size)
        assertEquals(1700000000.0, result.series[0].points[0][0])
        assertEquals(42.5, result.series[0].points[0][1])
    }

    @Test
    fun `MetricPayloadDecoder decodes multiple series`() {
        val s1 = buildProto { writeString(2, "metric.a") }
        val s2 = buildProto { writeString(2, "metric.b") }
        val s3 = buildProto { writeString(2, "metric.c") }
        val payload = buildProto {
            writeByteArray(1, s1)
            writeByteArray(1, s2)
            writeByteArray(1, s3)
        }
        val result = MetricPayloadDecoder.decode(payload)
        assertEquals(3, result.series.size)
        assertEquals("metric.a", result.series[0].metric)
        assertEquals("metric.b", result.series[1].metric)
        assertEquals("metric.c", result.series[2].metric)
    }

    @Test
    fun `MetricPayloadDecoder decodes multiple tags`() {
        val seriesBytes = buildProto {
            writeString(2, "test")
            writeString(3, "env:prod")
            writeString(3, "service:web")
            writeString(3, "region:us-east")
        }
        val payload = buildProto { writeByteArray(1, seriesBytes) }
        val result = MetricPayloadDecoder.decode(payload)
        assertEquals(3, result.series[0].tags.size)
    }

    @Test
    fun `SketchPayloadDecoder decodes empty payload`() {
        val result = SketchPayloadDecoder.decode(ByteArray(0))
        assertTrue(result.sketches.isEmpty())
    }

    @Test
    fun `SketchPayloadDecoder decodes sketch with metric and host`() {
        val sketchBytes = buildProto {
            writeString(1, "latency.p99")
            writeString(2, "api-server")
            writeString(3, "env:staging")
        }
        val payload = buildProto {
            writeByteArray(1, sketchBytes)
        }
        val result = SketchPayloadDecoder.decode(payload)
        assertEquals(1, result.sketches.size)
        assertEquals("latency.p99", result.sketches[0].metric)
        assertEquals("api-server", result.sketches[0].host)
        assertTrue(result.sketches[0].tags.contains("env:staging"))
    }

    @Test
    fun `SketchPayloadDecoder decodes distribution point`() {
        val distBytes = buildProto {
            writeInt64(1, 1700000000L) // ts
            writeInt64(2, 100L) // cnt
            writeDouble(3, 1.0) // min
            writeDouble(4, 99.0) // max
            writeDouble(5, 50.0) // avg
            writeDouble(6, 5000.0) // sum
        }
        val sketchBytes = buildProto {
            writeString(1, "test.sketch")
            writeByteArray(4, distBytes)
        }
        val payload = buildProto {
            writeByteArray(1, sketchBytes)
        }
        val result = SketchPayloadDecoder.decode(payload)
        val dist = result.sketches[0].distributions[0]
        assertEquals(1700000000L, dist.ts)
        assertEquals(100L, dist.cnt)
        assertEquals(1.0, dist.min)
        assertEquals(99.0, dist.max)
        assertEquals(50.0, dist.avg)
        assertEquals(5000.0, dist.sum)
    }

    @Test
    fun `ProcessAgentPayloadDecoder readHeader returns null for too-short data`() {
        assertNull(ProcessAgentPayloadDecoder.readHeader(ByteArray(10)))
    }

    @Test
    fun `ProcessAgentPayloadDecoder readHeader returns null for wrong version`() {
        val data = ByteArray(16)
        data[0] = 1 // wrong version
        assertNull(ProcessAgentPayloadDecoder.readHeader(data))
    }

    @Test
    fun `ProcessAgentPayloadDecoder readHeader parses valid header`() {
        val data = ByteArray(16)
        data[0] = 3 // version 3
        data[1] = 2 // encoding: zstd
        data[2] = 12 // type: proc
        val header = ProcessAgentPayloadDecoder.readHeader(data)
        assertNotNull(header)
        assertEquals(3, header.version)
        assertEquals(2, header.encoding)
        assertEquals(12, header.type)
    }

    @Test
    fun `ProcessAgentPayloadDecoder readHeader for container type`() {
        val data = ByteArray(16)
        data[0] = 3
        data[1] = 0 // protobuf encoding
        data[2] = 39 // container type
        val header = ProcessAgentPayloadDecoder.readHeader(data)
        assertNotNull(header)
        assertEquals(ProcessAgentPayloadDecoder.TYPE_COLLECTOR_CONTAINER, header.type)
    }

    @Test
    fun `ProcessAgentPayloadDecoder decompressBody returns raw body for protobuf encoding`() {
        val body = "hello".toByteArray()
        val data = ByteArray(16 + body.size)
        data[0] = 3
        System.arraycopy(body, 0, data, 16, body.size)
        val result = ProcessAgentPayloadDecoder.decompressBody(data, 0)
        assertEquals("hello", String(result))
    }

    @Test
    fun `ProcessAgentPayloadDecoder decodeCollectorContainer with empty proto`() {
        val result = ProcessAgentPayloadDecoder.decodeCollectorContainer(ByteArray(0))
        assertEquals("", result.host)
        assertTrue(result.containers.isEmpty())
    }

    @Test
    fun `ProcessAgentPayloadDecoder decodeCollectorContainer with hostname`() {
        val proto = buildProto {
            writeString(1, "web-server-01")
        }
        val result = ProcessAgentPayloadDecoder.decodeCollectorContainer(proto)
        assertEquals("web-server-01", result.host)
    }

    @Test
    fun `ProcessAgentPayloadDecoder decodeCollectorProc with empty proto`() {
        val result = ProcessAgentPayloadDecoder.decodeCollectorProc(ByteArray(0))
        assertEquals("", result.host)
        assertTrue(result.processes.isEmpty())
    }

    @Test
    fun `ProcessAgentPayloadDecoder decodeCollectorProc with hostname`() {
        val proto = buildProto {
            writeString(2, "worker-01")
        }
        val result = ProcessAgentPayloadDecoder.decodeCollectorProc(proto)
        assertEquals("worker-01", result.host)
    }

    @Test
    fun `ProcessAgentPayloadDecoder decodeCollectorProcDiscovery maps discovered process`() {
        val command = buildProto {
            writeString(1, "/usr/bin/java")
        }
        val user = buildProto {
            writeString(1, "app")
        }
        val discovery = buildProto {
            writeInt32(1, 4321)
            writeByteArray(4, command)
            writeByteArray(5, user)
        }
        val proto = buildProto {
            writeString(1, "worker-01")
            writeByteArray(4, discovery)
        }

        val result = ProcessAgentPayloadDecoder.decodeCollectorProcDiscovery(proto)

        assertEquals("worker-01", result.host)
        val process = result.processes.single()
        assertEquals(4321, process.pid)
        assertEquals("java", process.name)
        assertEquals("/usr/bin/java", process.command)
        assertEquals("app", process.user)
    }

    @Test
    fun `ProcessAgentPayloadDecoder decodeCollectorConnections maps network fields`() {
        val localAddr = buildProto {
            writeString(2, "10.0.0.5")
            writeInt32(3, 8080)
        }
        val remoteAddr = buildProto {
            writeString(2, "2001:db8::1")
            writeInt32(3, 443)
        }
        val connection = buildProto {
            writeInt32(1, 1234)
            writeByteArray(5, localAddr)
            writeByteArray(6, remoteAddr)
            writeEnum(10, 1)
            writeEnum(11, 0)
            writeUInt64(16, 5000)
            writeUInt64(17, 10000)
            writeEnum(19, 2)
        }
        val proto = buildProto {
            writeString(2, "web-01")
            writeByteArray(3, connection)
        }

        val result = ProcessAgentPayloadDecoder.decodeCollectorConnections(proto)

        assertEquals("web-01", result.host)
        assertEquals(1, result.connections.size)
        val decoded = result.connections.single()
        assertEquals(1234, decoded.pid)
        assertEquals("10.0.0.5", decoded.localAddr)
        assertEquals(8080, decoded.localPort)
        assertEquals("2001:db8::1", decoded.remoteAddr)
        assertEquals(443, decoded.remotePort)
        assertEquals("IPv6", decoded.family)
        assertEquals("tcp6", decoded.protocol)
        assertEquals("outgoing", decoded.direction)
        assertEquals(5000, decoded.bytesSent)
        assertEquals(10000, decoded.bytesRecv)
    }

    @Test
    fun `ProtoWireConstants has expected field values`() {
        assertEquals(3, ProtoWireConstants.FIELD_SHIFT)
        assertEquals(3, ProtoWireConstants.FIELD_3)
        assertEquals(4, ProtoWireConstants.FIELD_4)
        assertEquals(5, ProtoWireConstants.FIELD_5)
        assertEquals(6, ProtoWireConstants.FIELD_6)
        assertEquals(7, ProtoWireConstants.FIELD_7)
        assertEquals(8, ProtoWireConstants.FIELD_8)
        assertEquals(14, ProtoWireConstants.FIELD_14)
    }

    @Test
    fun `ProcessAgentPayloadDecoder type constants are correct`() {
        assertEquals(12, ProcessAgentPayloadDecoder.TYPE_COLLECTOR_PROC)
        assertEquals(22, ProcessAgentPayloadDecoder.TYPE_COLLECTOR_CONNECTIONS)
        assertEquals(23, ProcessAgentPayloadDecoder.TYPE_RES_COLLECTOR)
        assertEquals(39, ProcessAgentPayloadDecoder.TYPE_COLLECTOR_CONTAINER)
        assertEquals(53, ProcessAgentPayloadDecoder.TYPE_COLLECTOR_PROC_DISCOVERY)
    }

    @Test
    fun `ProcessAgentPayloadDecoder encodeCollectorResponse returns MessageV3 response`() {
        val response = ProcessAgentPayloadDecoder.encodeCollectorResponse()
        val header = ProcessAgentPayloadDecoder.readHeader(response)

        assertNotNull(header)
        assertEquals(3, header.version)
        assertEquals(ProcessAgentPayloadDecoder.TYPE_RES_COLLECTOR, header.type)
        assertEquals(0, header.encoding)
    }
}
