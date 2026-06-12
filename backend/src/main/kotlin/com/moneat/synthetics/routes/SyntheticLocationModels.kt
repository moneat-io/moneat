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

package com.moneat.synthetics.routes

import kotlinx.serialization.Serializable

/** A managed or private probe location. External id is a resource UUID; tests reference it by `code`. */
@Serializable
data class SyntheticLocationResponse(
    val id: String,
    val code: String,
    val name: String,
    val region: String,
    val type: String,
    val active: Boolean,
    val workerCount: Int,
    val lastSeenAt: Long? = null
)

/** Register a private location; the server returns a one-time probe key the worker authenticates with. */
@Serializable
data class CreatePrivateLocationRequest(
    val code: String,
    val name: String,
    val region: String = ""
)

@Serializable
data class CreatePrivateLocationResponse(
    val location: SyntheticLocationResponse,
    val key: String
)

/** Per-location uptime/latency rollup for a test's detail grid. */
@Serializable
data class LocationSummary(
    val locationCode: String,
    val uptimePercent: Double,
    val avgResponseMs: Double,
    val p95ResponseMs: Double,
    val totalRuns: Long,
    val failureCount: Long
)
