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

package com.moneat.otlp.services

/**
 * Extracted exception data from an OTLP span event with name="exception".
 */
data class OtlpExceptionEvent(
    val traceIdHex: String,
    val spanIdHex: String,
    val organizationId: Long,
    val projectId: Long?,
    val serviceNamespace: String,
    val service: String,
    val environment: String,
    val host: String,
    val serviceVersion: String,
    val exceptionType: String,
    val exceptionMessage: String,
    val stackTrace: String,
    val timestampMs: Long,
)
