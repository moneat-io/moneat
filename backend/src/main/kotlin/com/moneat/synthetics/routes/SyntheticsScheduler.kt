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

package com.moneat.synthetics.routes

import com.moneat.synthetics.models.SyntheticTestData
import com.moneat.synthetics.services.SyntheticsCheckExecutor
import com.moneat.synthetics.services.SyntheticsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.Collections
import java.util.UUID

private val logger = KotlinLogging.logger {}

class SyntheticsScheduler(
    private val service: SyntheticsService = SyntheticsService(),
    private val executor: SyntheticsCheckExecutor = SyntheticsCheckExecutor()
) {

    private var schedulerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runningTests = Collections.synchronizedSet(mutableSetOf<UUID>())

    fun start() {
        if (schedulerJob?.isActive == true) {
            logger.warn { "Synthetics scheduler is already running" }
            return
        }

        logger.info { "Starting synthetics scheduler..." }

        schedulerJob = scope.launch {
            while (isActive) {
                try {
                    checkTests(this)
                } catch (e: Exception) {
                    logger.error(e) { "Error in synthetics scheduler loop: ${e.message}" }
                }
                delay(2000)
            }
        }

        logger.info { "Synthetics scheduler started" }
    }

    fun stop() {
        logger.info { "Stopping synthetics scheduler..." }
        schedulerJob?.cancel()
        schedulerJob = null
        logger.info { "Synthetics scheduler stopped" }
    }

    private suspend fun checkTests(schedulerScope: CoroutineScope) {
        val tests = try {
            service.getTestsDueForRun()
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch synthetic tests due for run: ${e.message}" }
            return
        }

        if (tests.isEmpty()) return

        tests.forEach { test ->
            if (!runningTests.add(test.id)) return@forEach

            schedulerScope.launch {
                try {
                    performCheck(test)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to perform synthetic test ${test.id}: ${e.message}" }
                } finally {
                    runningTests.remove(test.id)
                }
            }
        }
    }

    private suspend fun performCheck(test: SyntheticTestData) {
        service.executeTestAndRecord(test, executor)
    }
}
