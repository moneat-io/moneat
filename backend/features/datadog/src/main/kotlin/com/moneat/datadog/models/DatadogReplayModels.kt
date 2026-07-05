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

package com.moneat.datadog.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DdReplayIdRef(
    val id: String = "",
)

@Serializable
data class DdReplaySegmentEvent(
    val source: String = "",
    @SerialName("creation_reason") val creationReason: String = "",
    val start: Long? = null,
    val end: Long? = null,
    @SerialName("records_count") val recordsCount: Int = 0,
    @SerialName("index_in_view") val indexInView: Int? = null,
    @SerialName("has_full_snapshot") val hasFullSnapshot: Boolean? = null,
    @SerialName("raw_segment_size") val rawSegmentSize: Long? = null,
    @SerialName("compressed_segment_size") val compressedSegmentSize: Long? = null,
    val application: DdReplayIdRef? = null,
    val session: DdReplayIdRef? = null,
    val view: DdReplayIdRef? = null,
)
