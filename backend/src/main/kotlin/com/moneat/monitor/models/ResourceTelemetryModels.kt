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

package com.moneat.monitor.models

import kotlinx.serialization.Serializable

// Real over-time telemetry for a single catalog resource. Every metric carries
// actual sampled points from the underlying store (host/container metric rollups,
// APM hourly span stats) over the requested range — there is no synthesized
// history. A metric is only present when its source produced at least one sample.

/** One sample. `value` is null for gaps where the source reported no data. */
@Serializable
data class ResourceTelemetryPoint(
    val ts: Long,
    val value: Double? = null
)

/** A named series within a metric (e.g. load "1m"/"5m"/"15m", network "Received"/"Sent"). */
@Serializable
data class ResourceTelemetryLine(
    val name: String,
    val points: List<ResourceTelemetryPoint>
)

/** A single chartable metric (cpu, mem, disk, load, network, latency, throughput, errorRate). */
@Serializable
data class ResourceTelemetryMetric(
    val key: String,
    val label: String,
    val unit: String,
    val lines: List<ResourceTelemetryLine>
)

@Serializable
data class ResourceTelemetryResponse(
    val kind: String,
    val rangeSeconds: Long,
    val intervalSeconds: Int,
    val metrics: List<ResourceTelemetryMetric>
)
