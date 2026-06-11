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
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeModeTest {
    @AfterTest
    fun resetEnvConfigMock() {
        unmockkObject(EnvConfig)
    }

    @Test
    fun `all role serves api starts schedulers and starts all ingestion pipelines`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("MONEAT_PROCESS_ROLE") } returns "all"
        every { EnvConfig.get("INGESTION_PIPELINES") } returns null

        assertEquals(MoneatProcessRole.ALL, RuntimeMode.role())
        assertEquals(true, RuntimeMode.servesApi())
        assertEquals(true, RuntimeMode.startsSchedulers())
        assertEquals(true, RuntimeMode.startsIngestionWorkers())
        assertEquals(false, RuntimeMode.startsWorkflowEgressOnly())
        assertEquals(true, RuntimeMode.shouldStartPipeline(IngestionPipeline.LOGS))
    }

    @Test
    fun `api role only serves api`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("MONEAT_PROCESS_ROLE") } returns "api"

        assertEquals(MoneatProcessRole.API, RuntimeMode.role())
        assertEquals(true, RuntimeMode.servesApi())
        assertEquals(false, RuntimeMode.startsSchedulers())
        assertEquals(false, RuntimeMode.startsIngestionWorkers())
        assertEquals(false, RuntimeMode.startsWorkflowEgressOnly())
        assertEquals(false, RuntimeMode.shouldStartPipeline(IngestionPipeline.LOGS))
    }

    @Test
    fun `scheduler role only starts schedulers`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("MONEAT_PROCESS_ROLE") } returns "scheduler"

        assertEquals(MoneatProcessRole.SCHEDULER, RuntimeMode.role())
        assertEquals(false, RuntimeMode.servesApi())
        assertEquals(true, RuntimeMode.startsSchedulers())
        assertEquals(false, RuntimeMode.startsIngestionWorkers())
        assertEquals(false, RuntimeMode.startsWorkflowEgressOnly())
    }

    @Test
    fun `ingestion worker role applies selected pipeline filter`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("MONEAT_PROCESS_ROLE") } returns "ingestion-worker"
        every { EnvConfig.get("INGESTION_PIPELINES") } returns "logs"

        assertEquals(MoneatProcessRole.INGESTION_WORKER, RuntimeMode.role())
        assertEquals(false, RuntimeMode.servesApi())
        assertEquals(false, RuntimeMode.startsSchedulers())
        assertEquals(true, RuntimeMode.startsIngestionWorkers())
        assertEquals(false, RuntimeMode.startsWorkflowEgressOnly())
        assertEquals(true, RuntimeMode.shouldStartPipeline(IngestionPipeline.LOGS))
        assertEquals(false, RuntimeMode.shouldStartPipeline(IngestionPipeline.LLM))
    }

    @Test
    fun `workflow egress role starts only workflow egress`() {
        mockkObject(EnvConfig)
        every { EnvConfig.get("MONEAT_PROCESS_ROLE") } returns "workflow-egress"

        assertEquals(MoneatProcessRole.WORKFLOW_EGRESS, RuntimeMode.role())
        assertEquals(false, RuntimeMode.servesApi())
        assertEquals(false, RuntimeMode.startsSchedulers())
        assertEquals(false, RuntimeMode.startsIngestionWorkers())
        assertEquals(true, RuntimeMode.startsWorkflowEgressOnly())
    }
}
