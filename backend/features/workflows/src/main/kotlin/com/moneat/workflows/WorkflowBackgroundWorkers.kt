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

import com.moneat.plugins.WorkflowWorkerMode
import com.moneat.plugins.workflowWorkerMode
import com.moneat.runtime.MoneatProcessRole
import com.moneat.runtime.RuntimeMode
import com.moneat.workflows.engine.temporal.ExecuteActionActivityImpl
import com.moneat.workflows.engine.temporal.ExecuteEgressActionActivityImpl
import com.moneat.workflows.engine.temporal.PersistRunActivityImpl
import com.moneat.workflows.engine.temporal.RequestApprovalActivityImpl
import com.moneat.workflows.engine.temporal.TemporalClientProvider
import com.moneat.workflows.engine.temporal.WORKFLOW_EGRESS_TASK_QUEUE
import com.moneat.workflows.engine.temporal.WORKFLOW_TASK_QUEUE
import com.moneat.workflows.engine.temporal.WorkflowInterpreterWorkflowImpl
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.temporal.worker.WorkerFactory
import mu.KotlinLogging
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}
private const val WORKFLOW_WORKER_SHUTDOWN_TIMEOUT_SECONDS = 30L

internal class WorkflowBackgroundWorkers {
    private var activeWorkers: ActiveWorkflowWorkers? = null

    fun start(application: Application, startSchedulers: Boolean) {
        if (shouldStartIsolatedEgressWorker(application)) {
            startIsolatedEgressWorker(application)
            return
        }

        if (startSchedulers) {
            startScheduledWorkers(application)
        }
    }

    fun stop() {
        activeWorkers?.let { workers ->
            shutdownWorkflowWorker(workers.factory, workers.provider)
            activeWorkers = null
        }
    }

    private fun shouldStartIsolatedEgressWorker(application: Application): Boolean =
        RuntimeMode.role() == MoneatProcessRole.WORKFLOW_EGRESS ||
            application.workflowWorkerMode() == WorkflowWorkerMode.EGRESS

    private fun startIsolatedEgressWorker(application: Application) {
        val koin = GlobalContext.get()
        val temporalClientProvider = koin.get<TemporalClientProvider>()
        val workflowWorkerFactory = temporalClientProvider.newWorkerFactory()
        workflowWorkerFactory
            .newWorker(WORKFLOW_EGRESS_TASK_QUEUE)
            .registerActivitiesImplementations(koin.get<ExecuteEgressActionActivityImpl>())
        logger.info { "Starting isolated Temporal egress worker on $WORKFLOW_EGRESS_TASK_QUEUE" }
        activeWorkers = ActiveWorkflowWorkers(temporalClientProvider, workflowWorkerFactory)
        workflowWorkerFactory.start()
        application.monitor.subscribe(ApplicationStopping) {
            stop()
        }
    }

    private fun startScheduledWorkers(application: Application) {
        val workers = initializeWorkflowWorkers(application) ?: return
        activeWorkers = workers
        workers.factory.start()
    }

    private fun initializeWorkflowWorkers(application: Application): ActiveWorkflowWorkers? {
        val koin = GlobalContext.get()
        val workflowWorkerMode = application.workflowWorkerMode()
        return try {
            val provider = koin.get<TemporalClientProvider>()
            val factory = provider.newWorkerFactory()
            if (workflowWorkerMode == WorkflowWorkerMode.ALL || workflowWorkerMode == WorkflowWorkerMode.TRUSTED) {
                val workflowWorker = factory.newWorker(WORKFLOW_TASK_QUEUE)
                workflowWorker.registerWorkflowImplementationTypes(WorkflowInterpreterWorkflowImpl::class.java)
                workflowWorker.registerActivitiesImplementations(
                    koin.get<ExecuteActionActivityImpl>(),
                    koin.get<PersistRunActivityImpl>(),
                    koin.get<RequestApprovalActivityImpl>()
                )
            }
            if (workflowWorkerMode == WorkflowWorkerMode.ALL || workflowWorkerMode == WorkflowWorkerMode.EGRESS) {
                factory
                    .newWorker(WORKFLOW_EGRESS_TASK_QUEUE)
                    .registerActivitiesImplementations(koin.get<ExecuteEgressActionActivityImpl>())
            }
            ActiveWorkflowWorkers(provider, factory)
        } catch (e: Throwable) {
            logger.error(e) {
                "Temporal workflow worker initialization failed; workflow execution disabled on this instance"
            }
            null
        }
    }

    private fun shutdownWorkflowWorker(
        workflowWorkerFactory: WorkerFactory,
        temporalClientProvider: TemporalClientProvider,
    ) {
        try {
            workflowWorkerFactory.shutdown()
            workflowWorkerFactory.awaitTermination(WORKFLOW_WORKER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!workflowWorkerFactory.isTerminated) {
                logger.warn {
                    "Temporal workflow worker did not stop within " +
                        "$WORKFLOW_WORKER_SHUTDOWN_TIMEOUT_SECONDS seconds; forcing shutdown"
                }
                workflowWorkerFactory.shutdownNow()
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn(error) { "Interrupted while stopping Temporal workflow worker; forcing shutdown" }
            workflowWorkerFactory.shutdownNow()
        } finally {
            temporalClientProvider.close()
        }
    }
}

private data class ActiveWorkflowWorkers(
    val provider: TemporalClientProvider,
    val factory: WorkerFactory,
)
