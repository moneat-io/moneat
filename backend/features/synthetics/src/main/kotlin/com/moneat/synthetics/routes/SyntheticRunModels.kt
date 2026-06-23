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

/** One assertion's outcome within a run (expected vs. actual). */
@Serializable
data class AssertionResult(
    val label: String,
    val expected: String = "",
    val actual: String = "",
    val passed: Boolean
)

/** Captured HTTP request for a run — secret values + auth redacted before persist. */
@Serializable
data class CapturedRequest(
    val method: String = "",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String = ""
)

/** Captured HTTP response for a run — body truncated. */
@Serializable
data class CapturedResponse(
    val statusCode: Int = 0,
    val statusText: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String = ""
)

@Serializable
data class BrowserConsoleEntry(
    val level: String,
    val text: String
)

@Serializable
data class BrowserNetworkEntry(
    val status: Int = 0,
    val method: String = "",
    val url: String = "",
    val durationMs: Long = 0
)

/** A browser step outcome, with an optional object-store key for its screenshot. */
@Serializable
data class BrowserStepResult(
    val action: String,
    val label: String = "",
    val status: String,
    val durationMs: Long = 0,
    val screenshotKey: String = "",
    val errorMessage: String = ""
)

/** Browser-specific run detail (steps, console, network, environment). */
@Serializable
data class BrowserRunDetail(
    val steps: List<BrowserStepResult> = emptyList(),
    val console: List<BrowserConsoleEntry> = emptyList(),
    val network: List<BrowserNetworkEntry> = emptyList(),
    val viewport: String = "",
    val browser: String = "",
    val failedStep: Int? = null
)

/** The rich detail persisted alongside a single synthetic result row (keyed by resultId). */
@Serializable
data class SyntheticRunDetail(
    val assertions: List<AssertionResult> = emptyList(),
    val request: CapturedRequest? = null,
    val response: CapturedResponse? = null,
    val timings: Map<String, Double> = emptyMap(),
    val resolvedIp: String = "",
    val browser: BrowserRunDetail? = null
)

/** API response for a single run: the result row plus its rich detail. */
@Serializable
data class SyntheticRunResponse(
    val resultId: String,
    val testId: String,
    val testName: String,
    val testType: String,
    val status: String,
    val locationCode: String,
    val durationMs: Long,
    val statusCode: Int,
    val attempt: Int,
    val assertionsTotal: Int,
    val assertionsFailed: Int,
    val errorMessage: String,
    val timestamp: String,
    val detail: SyntheticRunDetail? = null
)
