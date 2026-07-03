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

package com.moneat.events.services

import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import org.msgpack.value.ValueType
import java.util.Base64

internal data class MobileReplaySegmentPayloads(
    val replayEvent: ByteArray? = null,
    val replayRecording: ByteArray? = null,
    val replayVideo: ByteArray? = null
) {
    val hasAnyPayload: Boolean
        get() = replayEvent != null || replayRecording != null || replayVideo != null
}

internal object MobileReplaySegmentPayloadParser {
    fun rawBytes(payload: String, payloadBytes: ByteArray?): ByteArray =
        payloadBytes
            ?: runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
            ?: payload.toByteArray(Charsets.UTF_8)

    fun decode(rawBytes: ByteArray): MobileReplaySegmentPayloads? =
        runCatching {
            val unpacker = MessagePack.newDefaultUnpacker(rawBytes)
            try {
                val topMapSize = unpacker.unpackMapHeader()
                var replayEvent: ByteArray? = null
                var replayRecording: ByteArray? = null
                var replayVideo: ByteArray? = null

                repeat(topMapSize) {
                    when (unpacker.unpackString()) {
                        "replay_event" -> replayEvent = readBinaryOrString(unpacker)
                        "replay_recording" -> replayRecording = readBinaryOrString(unpacker)
                        "replay_video" -> replayVideo = readBinaryOrString(unpacker)
                        else -> unpacker.skipValue()
                    }
                }

                MobileReplaySegmentPayloads(
                    replayEvent = replayEvent,
                    replayRecording = replayRecording,
                    replayVideo = replayVideo
                ).takeIf { it.hasAnyPayload }
            } finally {
                unpacker.close()
            }
        }.getOrNull()

    fun readBinaryOrString(unpacker: MessageUnpacker): ByteArray? =
        when (unpacker.nextFormat.valueType) {
            ValueType.BINARY -> {
                val size = unpacker.unpackBinaryHeader()
                unpacker.readPayload(size)
            }

            ValueType.STRING -> unpacker.unpackString().toByteArray(Charsets.UTF_8)

            else -> {
                unpacker.skipValue()
                null
            }
        }
}
