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

import io.ktor.server.application.Application

private const val WORKFLOW_WORKER_MODE_CONFIG = "workflows.workerMode"

internal enum class WorkflowWorkerMode {
    ALL,
    TRUSTED,
    EGRESS,
    NONE
}

internal fun Application.workflowWorkerMode(): WorkflowWorkerMode {
    return parseWorkflowWorkerMode(
        environment.config
            .propertyOrNull(WORKFLOW_WORKER_MODE_CONFIG)
            ?.getString()
    )
}

internal fun parseWorkflowWorkerMode(rawMode: String?): WorkflowWorkerMode {
    val normalized = rawMode?.trim()?.uppercase()
    if (normalized.isNullOrBlank()) {
        return WorkflowWorkerMode.TRUSTED
    }
    return WorkflowWorkerMode.entries.firstOrNull { mode -> mode.name == normalized }
        ?: throw IllegalArgumentException(
            "Invalid $WORKFLOW_WORKER_MODE_CONFIG value '$normalized'. Expected one of: " +
                WorkflowWorkerMode.entries.joinToString { mode -> mode.name.lowercase() }
        )
}
