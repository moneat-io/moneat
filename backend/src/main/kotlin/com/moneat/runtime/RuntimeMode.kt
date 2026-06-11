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

package com.moneat.runtime

import com.moneat.config.EnvConfig
import com.moneat.ingestion.queue.IngestionPipeline
import com.moneat.ingestion.queue.IngestionQueueSettings

enum class MoneatProcessRole {
    ALL,
    API,
    SCHEDULER,
    INGESTION_WORKER,
    WORKFLOW_EGRESS;

    companion object {
        fun from(raw: String?): MoneatProcessRole =
            when (raw?.trim()?.lowercase()) {
                null, "", "all" -> ALL
                "api", "api-only" -> API
                "scheduler", "schedulers" -> SCHEDULER
                "ingestion", "ingestion-worker", "worker", "workers" -> INGESTION_WORKER
                "workflow-egress", "egress" -> WORKFLOW_EGRESS
                else -> throw IllegalArgumentException(
                    "Invalid MONEAT_PROCESS_ROLE value '$raw'. Expected all, api, scheduler, " +
                        "ingestion-worker, or workflow-egress."
                )
            }
    }
}

object RuntimeMode {
    @Volatile
    private var cachedRole: MoneatProcessRole? = null

    fun role(): MoneatProcessRole =
        cachedRole ?: synchronized(this) {
            cachedRole ?: MoneatProcessRole.from(EnvConfig.get(PROCESS_ROLE_ENV)).also { parsedRole ->
                cachedRole = parsedRole
            }
        }

    fun servesApi(): Boolean =
        role() == MoneatProcessRole.ALL || role() == MoneatProcessRole.API

    fun startsSchedulers(): Boolean =
        role() == MoneatProcessRole.ALL || role() == MoneatProcessRole.SCHEDULER

    fun startsIngestionWorkers(): Boolean =
        role() == MoneatProcessRole.ALL || role() == MoneatProcessRole.INGESTION_WORKER

    fun startsWorkflowEgressOnly(): Boolean =
        role() == MoneatProcessRole.WORKFLOW_EGRESS

    fun shouldStartPipeline(pipeline: IngestionPipeline): Boolean =
        startsIngestionWorkers() && IngestionQueueSettings.isSelected(pipeline)

    internal fun resetCachedRoleForTest() {
        cachedRole = null
    }

    const val PROCESS_ROLE_ENV = "MONEAT_PROCESS_ROLE"
}
