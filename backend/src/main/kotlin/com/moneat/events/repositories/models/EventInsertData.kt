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

package com.moneat.events.repositories.models

data class ErrorEventInsertData(
    val eventId: String,
    val projectId: Long,
    val timestampMs: Long,
    val level: String,
    val message: String,
    val platform: String,
    val environment: String,
    val release: String,
    val dist: String,
    val serverName: String,
    val userId: String,
    val userEmail: String,
    val userUsername: String,
    val userIpAddress: String,
    val exceptionType: String,
    val exceptionValue: String,
    val stackTrace: String,
    val fingerprint: List<String>,
    val issueId: String,
    val tags: Map<String, String>?,
    val contexts: String,
    val breadcrumbs: String,
    val request: String,
    val sdkName: String,
    val sdkVersion: String
)

data class TransactionEventInsertData(
    val eventId: String,
    val projectId: Long,
    val timestampMs: Long,
    val level: String,
    val message: String,
    val platform: String,
    val environment: String,
    val release: String,
    val dist: String,
    val serverName: String,
    val userId: String,
    val userEmail: String,
    val userUsername: String,
    val userIpAddress: String,
    val transactionName: String,
    val transactionOp: String,
    val durationMs: Double,
    val tags: Map<String, String>?,
    val contexts: String,
    val breadcrumbs: String,
    val request: String,
    val sdkName: String,
    val sdkVersion: String
)

data class SpanInsertData(
    val spanId: String,
    val parentSpanId: String,
    val traceId: String,
    val transactionId: String,
    val projectId: Long,
    val op: String,
    val description: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val durationMs: Double,
    val status: String,
    val tags: Map<String, String>?,
    val data: String
)

data class FeedbackInsertData(
    val feedbackId: String,
    val projectId: Long,
    val timestampMs: Long,
    val message: String,
    val contactEmail: String,
    val name: String,
    val url: String,
    val associatedEventId: String,
    val replayId: String,
    val environment: String,
    val release: String,
    val platform: String,
    val userId: String,
    val userEmail: String,
    val userUsername: String,
    val userIpAddress: String,
    val sdkName: String,
    val sdkVersion: String,
    val tags: Map<String, String>?
)

data class ReplayEventInsertData(
    val replayId: String,
    val projectId: Long,
    val segmentId: Int,
    val timestampMs: Long,
    val replayStartTimestampMs: Long,
    val urls: List<String>,
    val errorIds: List<String>,
    val traceIds: List<String>,
    val environment: String,
    val release: String,
    val platform: String,
    val userId: String,
    val userEmail: String,
    val userUsername: String,
    val userIpAddress: String,
    val sdkName: String,
    val sdkVersion: String,
    val browserName: String,
    val browserVersion: String,
    val osName: String,
    val osVersion: String,
    val deviceName: String,
    val deviceFamily: String,
    val activity: Int,
    val tags: String
)

data class ReplayRecordingInsertData(
    val replayId: String,
    val projectId: Long,
    val segmentId: Int,
    val timestampMs: Long,
    val recordingData: String
)

data class LlmGenerationInsertData(
    val generationId: String,
    val projectId: Long,
    val traceId: String,
    val spanId: String,
    val parentSpanId: String,
    val timestampMs: Long,
    val durationMs: Double,
    val name: String,
    val model: String,
    val provider: String,
    val type: String,
    val input: String,
    val output: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val status: String,
    val userId: String,
    val environment: String,
    val release: String,
    val tags: Map<String, String>?
)

data class ProfileInsertData(
    val organizationId: Int,
    val service: String,
    val environment: String,
    val release: String,
    val runtime: String,
    val platform: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationNs: Long,
    val storageKey: String,
    val payloadSizeBytes: Int
)
