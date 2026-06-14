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

package com.moneat.featureflags.services

import com.moneat.config.ClickHouseClient
import com.moneat.featureflags.models.FeatureFlagSdkKeyPrincipal
import com.moneat.featureflags.models.OfrepFlagEvaluationResponse
import com.moneat.featureflags.models.OfrepTrackRequest
import com.moneat.utils.ClickHouseSqlUtils.escapeSql
import com.moneat.utils.suspendRunCatching
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class FeatureFlagEventService {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun recordEvaluation(
        principal: FeatureFlagSdkKeyPrincipal,
        response: OfrepFlagEvaluationResponse,
        context: JsonObject,
        durationMs: Double,
    ) {
        if (!ClickHouseClient.isInitialized()) return
        val query = """
            INSERT INTO feature_flag_evaluations
            (
                organization_id,
                environment,
                flag_key,
                variant_key,
                value_type,
                reason,
                error_code,
                targeting_key,
                sdk_key_prefix,
                key_type,
                context_json,
                duration_ms
            )
            VALUES (
                ${principal.organizationId},
                '${escapeSql(principal.environmentKey)}',
                '${escapeSql(response.key)}',
                '${escapeSql(response.variant.orEmpty())}',
                '${escapeSql(response.metadata["valueType"].orEmpty())}',
                '${escapeSql(response.reason)}',
                '${escapeSql(response.errorCode.orEmpty())}',
                '${escapeSql(targetingKey(context).orEmpty())}',
                '${escapeSql(principal.keyPrefix)}',
                '${escapeSql(principal.keyType)}',
                '${escapeSql(json.encodeToString<JsonElement>(context))}',
                $durationMs
            )
        """.trimIndent()
        suspendRunCatching { ClickHouseClient.execute(query) }
    }

    suspend fun recordTrackingEvent(
        principal: FeatureFlagSdkKeyPrincipal,
        request: OfrepTrackRequest,
    ) {
        if (!ClickHouseClient.isInitialized()) return
        val query = """
            INSERT INTO feature_flag_tracking_events
            (
                organization_id,
                environment,
                event_name,
                targeting_key,
                flag_key,
                variant_key,
                sdk_key_prefix,
                key_type,
                value,
                properties_json
            )
            VALUES (
                ${principal.organizationId},
                '${escapeSql(principal.environmentKey)}',
                '${escapeSql(request.eventName)}',
                '${escapeSql(targetingKey(request.context).orEmpty())}',
                ${nullableSqlString(request.flagKey)},
                ${nullableSqlString(request.variant)},
                '${escapeSql(principal.keyPrefix)}',
                '${escapeSql(principal.keyType)}',
                ${request.value ?: "NULL"},
                '${escapeSql(json.encodeToString<JsonElement>(request.properties))}'
            )
        """.trimIndent()
        suspendRunCatching { ClickHouseClient.execute(query) }
    }

    private fun targetingKey(context: JsonObject): String? {
        return readString(context["targetingKey"])
            ?: readString(context["targeting_key"])
            ?: readString(context["key"])
    }

    private fun readString(element: JsonElement?): String? {
        return (element as? JsonPrimitive)?.contentOrNull
    }

    private fun nullableSqlString(value: String?): String {
        return value?.let { "'${escapeSql(it)}'" } ?: "NULL"
    }
}
