// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.alertroutes.services

import com.moneat.alerts.models.AlertEpisodes
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.enterprise.alertroutes.commands.AlertRoutePolicy
import com.moneat.enterprise.alertroutes.context.AlertRouteContext
import com.moneat.enterprise.alertroutes.context.AlertRouteContextFactory
import com.moneat.enterprise.alertroutes.context.AlertRouteEpisodeIdentity
import com.moneat.enterprise.alertroutes.context.AlertRouteMetadataPolicy
import com.moneat.enterprise.alertroutes.context.AlertRouteOwnershipResolver
import com.moneat.enterprise.alertroutes.context.AlertRouteSampleInput
import com.moneat.enterprise.alertroutes.context.DatabaseAlertRouteOwnershipResolver
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteEscalationPolicyRef
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteEvaluationResult
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteEvaluator
import com.moneat.enterprise.alertroutes.evaluation.AlertRouteTeamEscalationResolver
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.OrganizationTeams
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** Resolves a team's default escalation policy for TEAM route targets. */
class DatabaseAlertRouteTeamEscalationResolver : AlertRouteTeamEscalationResolver {
    override fun escalationPolicy(organizationId: Int, teamId: Int): AlertRouteEscalationPolicyRef? =
        transaction {
            val escalationPolicyId =
                OrganizationTeams
                    .selectAll()
                    .where {
                        (OrganizationTeams.organizationId eq organizationId) and
                            (OrganizationTeams.id eq teamId)
                    }.limit(1)
                    .firstOrNull()
                    ?.get(OrganizationTeams.escalationPolicyId)
                    ?: return@transaction null
            val resourceId =
                EscalationPolicies
                    .selectAll()
                    .where { EscalationPolicies.id eq escalationPolicyId }
                    .limit(1)
                    .firstOrNull()
                    ?.get(EscalationPolicies.resourceId)
            AlertRouteEscalationPolicyRef(escalationPolicyId, resourceId)
        }
}

/**
 * Evaluates alert routes for real alerts and for preview samples.
 *
 * Preview accepts either a saved alert episode or a supplied sample and returns the same
 * explanation structure used for live evaluation, so operators can see why a route matched.
 */
class AlertRouteEvaluationService(
    private val policy: AlertRoutePolicy = AlertRoutePolicy(),
    private val ownershipResolver: AlertRouteOwnershipResolver = DatabaseAlertRouteOwnershipResolver(),
    private val evaluator: AlertRouteEvaluator = AlertRouteEvaluator(DatabaseAlertRouteTeamEscalationResolver()),
) {
    /** Evaluate the routes of the event's organization against a live alert. */
    fun evaluate(
        event: AlertLifecycleEvent,
        episode: AlertRouteEpisodeIdentity = AlertRouteEpisodeIdentity.UNKNOWN,
    ): AlertRouteEvaluationResult {
        policy.requireReadAllowed(event.organizationId)
        val approved = AlertRouteMetadataPolicy.approve(event.metadata)
        val ownership = ownershipResolver.resolve(event.organizationId, approved.values)
        return evaluateContext(AlertRouteContextFactory.fromLifecycleEvent(event, episode, ownership))
    }

    /** Explain match and non-match for a supplied alert sample. */
    fun preview(organizationId: Int, sample: AlertRouteSampleInput): AlertRouteEvaluationResult {
        policy.requireReadAllowed(organizationId)
        val approved = AlertRouteMetadataPolicy.approve(sample.metadata.mapValues { JsonPrimitive(it.value) })
        val ownership = ownershipResolver.resolve(organizationId, approved.values)
        return evaluateContext(AlertRouteContextFactory.fromSample(organizationId, sample, ownership))
    }

    /** Explain match and non-match for a saved alert episode, optionally enriched with metadata. */
    fun previewSavedEpisode(
        organizationId: Int,
        episodeId: Uuid,
        metadata: Map<String, String> = emptyMap(),
    ): AlertRouteEvaluationResult {
        policy.requireReadAllowed(organizationId)
        val sample = loadEpisodeSample(organizationId, episodeId, metadata)
        val approved = AlertRouteMetadataPolicy.approve(sample.metadata.mapValues { JsonPrimitive(it.value) })
        val ownership = ownershipResolver.resolve(organizationId, approved.values)
        return evaluateContext(AlertRouteContextFactory.fromSample(organizationId, sample, ownership))
    }

    private fun loadEpisodeSample(
        organizationId: Int,
        episodeId: Uuid,
        metadata: Map<String, String>,
    ): AlertRouteSampleInput =
        transaction {
            val row =
                AlertEpisodes
                    .selectAll()
                    .where {
                        (AlertEpisodes.organizationId eq organizationId) and
                            (AlertEpisodes.resourceId eq episodeId)
                    }.limit(1)
                    .firstOrNull()
                    ?: throw IncidentCommandNotFoundException("Alert episode not found")
            AlertRouteSampleInput(
                source = row[AlertEpisodes.sourceName],
                status = row[AlertEpisodes.status],
                priority = row[AlertEpisodes.priority].orEmpty(),
                title = row[AlertEpisodes.title].orEmpty(),
                description = row[AlertEpisodes.description].orEmpty(),
                deduplicationKey = row[AlertEpisodes.deduplicationKey],
                metadata = metadata,
                episode =
                    AlertRouteEpisodeIdentity(
                        resourceId = row[AlertEpisodes.resourceId].toString(),
                        key = row[AlertEpisodes.episodeKey],
                        sequence = row[AlertEpisodes.episodeSeq],
                        status = row[AlertEpisodes.status],
                    ),
            )
        }

    private fun evaluateContext(context: AlertRouteContext): AlertRouteEvaluationResult {
        val routes = transaction { AlertRouteReader.load(context.organizationId) }
        return evaluator.evaluate(routes, context)
    }
}
