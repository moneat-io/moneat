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

import com.moneat.enterprise.FeatureRegistry

/**
 * Trusted connection-resolution seam for workflow activities that need vaulted credentials.
 *
 * The returned value contains plaintext secret material. Call this only from a trusted activity
 * immediately before using the secret, and never return it to workflow code, API responses, logs,
 * or Temporal workflow history. When the Enterprise workflows module is not licensed, the vault is
 * absent and resolution degrades to null.
 */
class ResolveConnectionActivity(
    private val vaultProvider: () -> WorkflowConnectionVault? = { FeatureRegistry.getWorkflowConnectionVault() }
) {

    suspend fun resolve(
        organizationId: Int,
        reference: WorkflowConnectionReference,
        runScope: Map<String, String>
    ): WorkflowResolvedConnection? =
        vaultProvider()?.resolveSecret(
            organizationId = organizationId,
            reference = reference,
            runScope = runScope
        )
}
