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

import com.moneat.workflows.engine.temporal.WorkflowApprovalRequestInput
import com.moneat.workflows.engine.temporal.WorkflowApprovalRequestResult

/**
 * Enterprise approval bridge for workflow human gates.
 *
 * Core workflow execution owns the durable Temporal wait and signal contract. The
 * Enterprise workflows module owns persistence, in-app approval routes and
 * notification fan-out. Keeping that boundary here prevents OSS code from needing
 * Enterprise tables while still making approval nodes first-class graph controls.
 */
fun interface WorkflowApprovalBridge {
    suspend fun requestApproval(input: WorkflowApprovalRequestInput): WorkflowApprovalRequestResult
}
