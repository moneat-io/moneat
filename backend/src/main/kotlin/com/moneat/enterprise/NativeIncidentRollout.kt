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

package com.moneat.enterprise

const val NATIVE_INCIDENT_ROLLOUT_FLAG_KEY = "moneat.system.native-incident-response"

enum class NativeIncidentRolloutState {
    ENABLED,
    DISABLED,
    ENVIRONMENT_NOT_FOUND,
    FLAG_NOT_FOUND,
    EVALUATION_ERROR,
    PROVIDER_UNAVAILABLE,
    MODULE_UNAVAILABLE,
}

data class NativeIncidentRolloutStatus(
    val enabled: Boolean,
    val environment: String,
    val state: NativeIncidentRolloutState,
)

/**
 * Runtime bridge implemented by the open-source feature flag module.
 * Native incident consumers use this instead of depending on feature flag persistence directly.
 */
interface NativeIncidentRolloutBridge {
    fun status(organizationId: Int, environment: String): NativeIncidentRolloutStatus
}
