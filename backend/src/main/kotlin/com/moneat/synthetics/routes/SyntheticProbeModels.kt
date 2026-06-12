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

/** One unit of work a private-location worker pulls and executes. Variables are pre-resolved. */
@Serializable
data class ProbeWorkItem(
    val testId: String,
    val testType: String,
    val url: String? = null,
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val assertions: List<SyntheticAssertion> = emptyList(),
    val steps: List<SyntheticStep> = emptyList(),
    val browserSteps: List<BrowserStep> = emptyList(),
    val timeoutSeconds: Int = 30,
    val config: SyntheticTestConfig? = null
)

@Serializable
data class ProbeWorkResponse(
    val locationCode: String,
    val items: List<ProbeWorkItem>
)

/** A result a private-location worker posts back after executing a [ProbeWorkItem]. */
@Serializable
data class ProbeResultSubmission(
    val testId: String,
    val status: String,
    val durationMs: Long,
    val statusCode: Int = 0,
    val errorMessage: String = "",
    val resolvedIp: String = "",
    val timings: Map<String, Double> = emptyMap(),
    val assertions: List<AssertionResult> = emptyList(),
    val request: CapturedRequest? = null,
    val response: CapturedResponse? = null,
    val browser: BrowserRunDetail? = null
)
