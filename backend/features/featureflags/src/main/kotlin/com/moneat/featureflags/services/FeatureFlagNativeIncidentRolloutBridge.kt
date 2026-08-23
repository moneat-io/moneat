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

import com.moneat.enterprise.NATIVE_INCIDENT_ROLLOUT_FLAG_KEY
import com.moneat.enterprise.NativeIncidentRolloutBridge
import com.moneat.enterprise.NativeIncidentRolloutState
import com.moneat.enterprise.NativeIncidentRolloutStatus
import com.moneat.featureflags.models.FLAG_KEY_TYPE_SERVER
import com.moneat.featureflags.models.FeatureFlagSnapshotFlag
import com.moneat.featureflags.models.FeatureFlagValueType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger(FeatureFlagNativeIncidentRolloutBridge::class.java.name)
private const val DISABLED_VARIANT_KEY = "disabled"
private const val ENABLED_VARIANT_KEY = "enabled"
private val emptyTargetingRules = JsonObject(mapOf("rules" to JsonArray(emptyList())))

class FeatureFlagNativeIncidentRolloutBridge(
    private val featureFlagService: FeatureFlagService,
    private val evaluator: FeatureFlagEvaluator,
) : NativeIncidentRolloutBridge {
    private val provisionedOrganizations = ConcurrentHashMap<Int, Boolean>()

    override fun status(organizationId: Int, environment: String): NativeIncidentRolloutStatus =
        runCatching {
            provisionedOrganizations.computeIfAbsent(organizationId) {
                featureFlagService.ensureNativeIncidentRolloutFlag(organizationId)
                true
            }
            val snapshot =
                featureFlagService.getSnapshot(organizationId, environment)
                    ?: return disabled(environment, NativeIncidentRolloutState.ENVIRONMENT_NOT_FOUND)
            val flag = snapshot.flags.singleOrNull { it.key == NATIVE_INCIDENT_ROLLOUT_FLAG_KEY }
            if (flag == null) {
                return disabled(environment, NativeIncidentRolloutState.FLAG_NOT_FOUND)
            }
            if (!isCanonicalReservedFlag(flag)) {
                return disabled(environment, NativeIncidentRolloutState.EVALUATION_ERROR)
            }
            val response =
                evaluator.evaluate(
                    snapshot = snapshot,
                    flagKey = NATIVE_INCIDENT_ROLLOUT_FLAG_KEY,
                    context = JsonObject(mapOf("targetingKey" to JsonPrimitive(organizationId))),
                    expectedType = FeatureFlagValueType.BOOLEAN,
                    keyType = FLAG_KEY_TYPE_SERVER,
                )
            if (response.errorCode != null) {
                return disabled(environment, NativeIncidentRolloutState.EVALUATION_ERROR)
            }
            val enabled = (response.value as? JsonPrimitive)?.booleanOrNull == true
            NativeIncidentRolloutStatus(
                enabled = enabled,
                environment = environment,
                state = if (enabled) {
                    NativeIncidentRolloutState.ENABLED
                } else {
                    NativeIncidentRolloutState.DISABLED
                },
            )
        }.getOrElse { error ->
            logger.log(
                Level.SEVERE,
                "Native incident rollout evaluation failed for organization $organizationId in $environment",
                error,
            )
            disabled(environment, NativeIncidentRolloutState.EVALUATION_ERROR)
        }

    private fun disabled(environment: String, state: NativeIncidentRolloutState) =
        NativeIncidentRolloutStatus(enabled = false, environment = environment, state = state)

    private fun isCanonicalReservedFlag(flag: FeatureFlagSnapshotFlag): Boolean {
        val variants =
            flag.variants.associate { variant ->
                variant.key to (variant.value as? JsonPrimitive)?.booleanOrNull
            }
        return flag.valueType == FeatureFlagValueType.BOOLEAN &&
            !flag.clientVisible &&
            flag.config.defaultVariantKey == ENABLED_VARIANT_KEY &&
            flag.config.offVariantKey == DISABLED_VARIANT_KEY &&
            flag.config.rules == emptyTargetingRules &&
            variants.size == 2 &&
            variants[DISABLED_VARIANT_KEY] == false &&
            variants[ENABLED_VARIANT_KEY] == true
    }
}
