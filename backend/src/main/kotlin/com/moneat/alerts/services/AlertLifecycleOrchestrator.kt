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

package com.moneat.alerts.services

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertStatus
import com.moneat.utils.suspendRunCatching
import mu.KotlinLogging
import org.koin.core.context.GlobalContext

private val logger = KotlinLogging.logger {}

/** Delivery arms selected by a producer without changing episode ownership. */
data class AlertFanoutPlan(
    val workflow: Boolean = true,
    val nativeOnCall: Boolean = false,
    val incidentProviders: Boolean = false,
    val route: Boolean = true,
) {
    companion object {
        val WORKFLOW_ONLY = AlertFanoutPlan()
        val FULL = AlertFanoutPlan(nativeOnCall = true, incidentProviders = true)
    }
}

/** One immutable episode-first context shared by every fan-out arm. */
data class AlertFanoutContext(
    val event: AlertLifecycleEvent,
    val episodeDecision: AlertEpisodeDecision?,
    val episode: AlertEpisodeContext?,
    val deliverySilenced: Boolean,
) {
    val deliveryKey: String = buildString {
        append(episode?.episodeKey ?: "${event.source.name}:${event.deduplicationKey}")
        append(':')
        append(event.status.name)
        append(':')
        append(episodeDecision?.notificationSequence ?: 0)
    }
}

enum class AlertFanoutArm {
    ROUTE,
    WORKFLOW,
    NATIVE_ON_CALL,
    INCIDENT_PROVIDERS,
}

enum class AlertFanoutState {
    SUCCEEDED,
    FAILED,
    SKIPPED,
    UNAVAILABLE,
}

data class AlertFanoutOutcome(
    val arm: AlertFanoutArm,
    val state: AlertFanoutState,
    val error: String? = null,
)

data class AlertOrchestrationResult(
    val context: AlertFanoutContext,
    val outcomes: List<AlertFanoutOutcome>,
)

fun interface AlertWorkflowFanout {
    suspend fun publish(context: AlertFanoutContext)
}

interface AlertIncidentFanout {
    suspend fun fireNative(context: AlertFanoutContext)

    suspend fun fireProviders(context: AlertFanoutContext)

    suspend fun resolveNative(context: AlertFanoutContext)

    suspend fun resolveProviders(context: AlertFanoutContext)
}

fun interface AlertRouteFanout {
    suspend fun process(context: AlertFanoutContext)
}

/** Optional enterprise route arm; core-only deployments safely retain the no-op bridge. */
object AlertRouteFanoutRegistry {
    @Volatile
    private var registered: AlertRouteFanout? = null

    fun register(fanout: AlertRouteFanout) {
        registered = fanout
    }

    fun unregister(fanout: AlertRouteFanout) {
        synchronized(this) {
            if (registered === fanout) registered = null
        }
    }

    fun current(): AlertRouteFanout? = registered
}

/** Records or resolves one episode once, then isolates every selected downstream arm. */
class AlertLifecycleOrchestrator(
    private val episodeService: AlertEpisodeService = AlertEpisodeService(),
    private val workflowFanoutProvider: () -> AlertWorkflowFanout? = ::workflowFanoutFromKoin,
    private val incidentFanoutProvider: () -> AlertIncidentFanout? = ::incidentFanoutFromKoin,
    private val routeFanoutProvider: () -> AlertRouteFanout? = AlertRouteFanoutRegistry::current,
) {
    suspend fun process(
        event: AlertLifecycleEvent,
        plan: AlertFanoutPlan = AlertFanoutPlan.WORKFLOW_ONLY,
    ): AlertOrchestrationResult = fanOut(recordEpisode(event), plan)

    /** Retries selected arms without recording the episode or reserving another notification. */
    suspend fun retry(
        context: AlertFanoutContext,
        plan: AlertFanoutPlan,
    ): AlertOrchestrationResult = fanOut(context, plan)

    private suspend fun fanOut(
        context: AlertFanoutContext,
        plan: AlertFanoutPlan,
    ): AlertOrchestrationResult {
        val outcomes = mutableListOf<AlertFanoutOutcome>()
        outcomes += runRouteArm(context, plan)

        val workflowDue = !context.deliverySilenced && context.episodeDecision?.shouldPublish == true
        val incidentDue = context.episodeDecision?.shouldPublish != false &&
            (!context.deliverySilenced || context.event.status == AlertStatus.RESOLVED)
        outcomes += runWorkflowArm(context, plan, workflowDue)
        outcomes += runIncidentArm(context, plan.nativeOnCall, incidentDue, AlertFanoutArm.NATIVE_ON_CALL)
        outcomes += runIncidentArm(
            context,
            plan.incidentProviders,
            incidentDue,
            AlertFanoutArm.INCIDENT_PROVIDERS,
        )
        return AlertOrchestrationResult(context, outcomes)
    }

    private fun recordEpisode(event: AlertLifecycleEvent): AlertFanoutContext {
        val silenced = isDeliverySilenced(event.organizationId)
        return if (event.status == AlertStatus.RESOLVED) {
            val decision = episodeService.recordResolved(event)
            AlertFanoutContext(event, decision, decision?.episode, silenced)
        } else if (silenced) {
            val episode = episodeService.recordFiringWithoutNotification(event)
            AlertFanoutContext(event, null, episode, deliverySilenced = true)
        } else {
            val decision = episodeService.recordFiring(event)
            AlertFanoutContext(event, decision, decision?.episode, deliverySilenced = false)
        }
    }

    private fun isDeliverySilenced(organizationId: Int): Boolean =
        runCatching { episodeService.isOrganizationSilenced(organizationId) }
            .getOrElse { error ->
                logger.error(error) {
                    "Failed to check alert silence for organization $organizationId; delivering alert"
                }
                false
            }

    private suspend fun runRouteArm(context: AlertFanoutContext, plan: AlertFanoutPlan): AlertFanoutOutcome {
        if (!plan.route) return AlertFanoutOutcome(AlertFanoutArm.ROUTE, AlertFanoutState.SKIPPED)
        val fanout = routeFanoutProvider() ?: return AlertFanoutOutcome(
            AlertFanoutArm.ROUTE,
            AlertFanoutState.UNAVAILABLE,
        )
        return runArm(AlertFanoutArm.ROUTE) { fanout.process(context) }
    }

    private suspend fun runWorkflowArm(
        context: AlertFanoutContext,
        plan: AlertFanoutPlan,
        deliveryDue: Boolean,
    ): AlertFanoutOutcome {
        if (!plan.workflow || !deliveryDue) {
            return AlertFanoutOutcome(AlertFanoutArm.WORKFLOW, AlertFanoutState.SKIPPED)
        }
        val fanout = workflowFanoutProvider() ?: return AlertFanoutOutcome(
            AlertFanoutArm.WORKFLOW,
            AlertFanoutState.UNAVAILABLE,
        )
        return runArm(AlertFanoutArm.WORKFLOW) { fanout.publish(context) }
    }

    private suspend fun runIncidentArm(
        context: AlertFanoutContext,
        selected: Boolean,
        deliveryDue: Boolean,
        arm: AlertFanoutArm,
    ): AlertFanoutOutcome {
        if (!selected || !deliveryDue) return AlertFanoutOutcome(arm, AlertFanoutState.SKIPPED)
        val fanout = incidentFanoutProvider() ?: return AlertFanoutOutcome(arm, AlertFanoutState.UNAVAILABLE)
        return runArm(arm) {
            when (arm) {
                AlertFanoutArm.NATIVE_ON_CALL -> if (context.event.status == AlertStatus.RESOLVED) {
                    fanout.resolveNative(context)
                } else {
                    fanout.fireNative(context)
                }
                AlertFanoutArm.INCIDENT_PROVIDERS -> if (context.event.status == AlertStatus.RESOLVED) {
                    fanout.resolveProviders(context)
                } else {
                    fanout.fireProviders(context)
                }
                else -> error("Incident fan-out received unsupported arm $arm")
            }
        }
    }

    private suspend fun runArm(arm: AlertFanoutArm, block: suspend () -> Unit): AlertFanoutOutcome =
        suspendRunCatching { block() }
            .fold(
                onSuccess = { AlertFanoutOutcome(arm, AlertFanoutState.SUCCEEDED) },
                onFailure = { error ->
                    logger.error(error) { "Alert fan-out arm $arm failed" }
                    AlertFanoutOutcome(arm, AlertFanoutState.FAILED, error.message)
                },
            )
}

private fun workflowFanoutFromKoin(): AlertWorkflowFanout? =
    GlobalContext.getOrNull()?.getOrNull()

private fun incidentFanoutFromKoin(): AlertIncidentFanout? =
    GlobalContext.getOrNull()?.getOrNull()
