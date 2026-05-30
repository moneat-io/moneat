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

package com.moneat.workflows

import kotlinx.serialization.json.JsonElement

/**
 * Enterprise bridge for licensed third-party workflow connectors.
 *
 * Core exposes connector action definitions and routes them through this bridge.
 * Enterprise resolves vaulted credentials and performs connector-specific work;
 * unlicensed installations return a clear "requires Enterprise" response.
 */
interface WorkflowPremiumConnectorBridge {
    suspend fun executeConnectorAction(
        organizationId: Int,
        actionName: String,
        params: Map<String, String>,
        actorUserId: Int?
    ): Map<String, JsonElement>
}
