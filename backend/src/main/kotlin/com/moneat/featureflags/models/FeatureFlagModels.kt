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

package com.moneat.featureflags.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

const val FLAG_KEY_TYPE_SERVER = "server"
const val FLAG_KEY_TYPE_CLIENT = "client"

@Serializable
enum class FeatureFlagValueType {
    BOOLEAN,
    STRING,
    INTEGER,
    DOUBLE,
    OBJECT,
}

@Serializable
data class FeatureFlagEnvironmentResponse(
    val id: Int,
    val key: String,
    val name: String,
    val description: String? = null,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class FeatureFlagVariantRequest(
    val key: String,
    val name: String? = null,
    val value: JsonElement,
)

@Serializable
data class FeatureFlagVariantResponse(
    val id: Int,
    val key: String,
    val name: String,
    val value: JsonElement,
    val sortOrder: Int,
)

@Serializable
data class FeatureFlagConfigResponse(
    val environmentKey: String,
    val environmentName: String,
    val enabled: Boolean,
    val defaultVariantKey: String? = null,
    val offVariantKey: String? = null,
    val rules: JsonElement,
    val version: Int,
    val updatedAt: String,
)

@Serializable
data class FeatureFlagResponse(
    val id: Int,
    val key: String,
    val name: String,
    val description: String? = null,
    val valueType: FeatureFlagValueType,
    val clientVisible: Boolean,
    val tags: List<String>,
    val variants: List<FeatureFlagVariantResponse>,
    val configs: List<FeatureFlagConfigResponse>,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class FeatureFlagListResponse(
    val environments: List<FeatureFlagEnvironmentResponse>,
    val flags: List<FeatureFlagResponse>,
)

@Serializable
data class CreateFeatureFlagEnvironmentRequest(
    val key: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class CreateFeatureFlagRequest(
    val key: String,
    val name: String,
    val description: String? = null,
    val valueType: FeatureFlagValueType,
    val clientVisible: Boolean = false,
    val tags: List<String> = emptyList(),
    val variants: List<FeatureFlagVariantRequest>,
    val defaultVariantKey: String? = null,
    val offVariantKey: String? = null,
)

@Serializable
data class UpdateFeatureFlagRequest(
    val name: String? = null,
    val description: String? = null,
    val clientVisible: Boolean? = null,
    val tags: List<String>? = null,
    val variants: List<FeatureFlagVariantRequest>? = null,
)

@Serializable
data class UpdateFeatureFlagConfigRequest(
    val enabled: Boolean? = null,
    val defaultVariantKey: String? = null,
    val offVariantKey: String? = null,
    val rules: JsonElement? = null,
)

@Serializable
data class FeatureFlagSegmentRequest(
    val key: String,
    val name: String,
    val description: String? = null,
    val conditions: JsonElement = buildJsonObject { put("all", kotlinx.serialization.json.JsonArray(emptyList())) },
)

@Serializable
data class FeatureFlagSegmentResponse(
    val id: Int,
    val key: String,
    val name: String,
    val description: String? = null,
    val conditions: JsonElement,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class FeatureFlagSdkKeyRequest(
    val environmentKey: String,
    val name: String,
    val keyType: String,
)

@Serializable
data class FeatureFlagSdkKeyResponse(
    val id: Int,
    val environmentKey: String,
    val name: String,
    val keyType: String,
    val keyPrefix: String,
    val createdAt: String,
    val lastUsedAt: String? = null,
)

@Serializable
data class CreateFeatureFlagSdkKeyResponse(
    val id: Int,
    val environmentKey: String,
    val name: String,
    val keyType: String,
    val keyPrefix: String,
    val key: String,
    val createdAt: String,
)

@Serializable
data class FeatureFlagAuditEventResponse(
    val id: Int,
    val environmentKey: String? = null,
    val flagKey: String? = null,
    val actorUserId: Int? = null,
    val eventType: String,
    val before: JsonElement? = null,
    val after: JsonElement? = null,
    val createdAt: String,
)

@Serializable
data class FeatureFlagAnalyticsResponse(
    val evaluations: Long,
    val uniqueTargetingKeys: Long,
    val variants: List<FeatureFlagVariantAnalytics>,
    val trackingEvents: List<FeatureFlagTrackingAnalytics>,
)

@Serializable
data class FeatureFlagVariantAnalytics(
    val flagKey: String,
    val variantKey: String,
    val evaluations: Long,
    val uniqueTargetingKeys: Long,
)

@Serializable
data class FeatureFlagTrackingAnalytics(
    val eventName: String,
    val flagKey: String? = null,
    val variantKey: String? = null,
    val events: Long,
    val uniqueTargetingKeys: Long,
    val totalValue: Double,
)

@Serializable
data class OfrepEvaluateRequest(
    val context: JsonObject = JsonObject(emptyMap()),
    val type: FeatureFlagValueType? = null,
    val flagKeys: List<String>? = null,
)

@Serializable
data class OfrepFlagEvaluationResponse(
    val key: String,
    val value: JsonElement,
    val variant: String? = null,
    val reason: String,
    val metadata: Map<String, String> = emptyMap(),
    val errorCode: String? = null,
    val errorDetails: String? = null,
)

@Serializable
data class OfrepBulkEvaluationResponse(
    val flags: List<JsonObject>,
    val metadata: Map<String, String>,
)

@Serializable
data class OfrepTrackRequest(
    val eventName: String,
    val context: JsonObject = JsonObject(emptyMap()),
    val value: Double? = null,
    val flagKey: String? = null,
    val variant: String? = null,
    val properties: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class FeatureFlagEnvironmentSnapshot(
    val id: Int,
    val key: String,
    val name: String,
    val version: Int,
)

@Serializable
data class FeatureFlagVariantSnapshot(
    val key: String,
    val name: String,
    val value: JsonElement,
    val sortOrder: Int,
)

@Serializable
data class FeatureFlagConfigSnapshot(
    val enabled: Boolean,
    val defaultVariantKey: String? = null,
    val offVariantKey: String? = null,
    val rules: JsonElement,
    val version: Int,
)

@Serializable
data class FeatureFlagSnapshotFlag(
    val id: Int,
    val key: String,
    val valueType: FeatureFlagValueType,
    val clientVisible: Boolean,
    val variants: List<FeatureFlagVariantSnapshot>,
    val config: FeatureFlagConfigSnapshot,
)

@Serializable
data class FeatureFlagSegmentSnapshot(
    val key: String,
    val conditions: JsonElement,
)

@Serializable
data class FeatureFlagEnvironmentConfigSnapshot(
    val organizationId: Int,
    val environment: FeatureFlagEnvironmentSnapshot,
    val etag: String,
    val flags: List<FeatureFlagSnapshotFlag>,
    val segments: List<FeatureFlagSegmentSnapshot>,
)

data class FeatureFlagSdkKeyPrincipal(
    val organizationId: Int,
    val environmentId: Int,
    val environmentKey: String,
    val keyType: String,
    val keyPrefix: String,
)
