// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.statuspage

import com.moneat.enterprise.incidents.commands.IncidentCommandActor
import com.moneat.enterprise.incidents.commands.IncidentCommandConflictException
import com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.commands.IncidentCommandService
import com.moneat.enterprise.incidents.commands.IncidentSourceReference
import com.moneat.enterprise.incidents.commands.LinkIncidentSourceCommand
import com.moneat.enterprise.incidents.models.IncidentParticipationType
import com.moneat.enterprise.incidents.models.IncidentSourceType
import com.moneat.enterprise.incidents.models.NativeIncidentParticipants
import com.moneat.enterprise.incidents.models.NativeIncidentRoleAssignments
import com.moneat.enterprise.incidents.models.NativeIncidentSourceLinks
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.incidents.timeline.IncidentTimelineProducer
import com.moneat.enterprise.incidents.timeline.IncidentTimelineProducerEvent
import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.org.services.OrgRole
import com.moneat.shared.models.Memberships
import com.moneat.statuspage.models.CreateIncidentRequest
import com.moneat.statuspage.models.CreateIncidentUpdateRequest
import com.moneat.statuspage.models.IncidentResponse
import com.moneat.statuspage.models.UpdateIncidentRequest
import com.moneat.statuspage.services.StatusPageService
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.net.URI
import java.util.UUID
import kotlin.time.Clock

data class CreateLinkedStatusPageIncidentRequest(
    val title: String,
    val status: String,
    val impact: String,
    val message: String,
    val sourceUrl: String,
    val correlationKey: String,
)

data class UpdateLinkedStatusPageIncidentRequest(
    val title: String? = null,
    val status: String? = null,
    val impact: String? = null,
    val message: String? = null,
)

data class IncidentStatusPageLinkTarget(
    val organizationId: Int,
    val actorUserId: Int,
    val incidentId: Int,
    val statusPageId: UUID,
    val statusPageIncidentId: UUID? = null,
)

class IncidentStatusPageLinkService(
    private val statusPageService: StatusPageService = StatusPageService(),
    private val incidentCommandService: IncidentCommandService = IncidentCommandService(),
    private val timelineProducer: IncidentTimelineProducer = IncidentTimelineProducer(),
) {
    fun create(
        target: IncidentStatusPageLinkTarget,
        request: CreateLinkedStatusPageIncidentRequest,
        commandKey: String,
    ): IncidentResponse {
        requireIncidentAccess(target.organizationId, target.actorUserId, target.incidentId)
        validateCustomerContent(request.title, request.message)
        requireSafeSourceUrl(request.sourceUrl)
        requireCorrelationKey(request.correlationKey)

        val sourceKey = sourceKey(target.statusPageId, request.correlationKey)
        return try {
            transaction {
                existingLinkedIncident(target.organizationId, target.incidentId, sourceKey)?.let { linkedId ->
                    return@transaction statusPageService.getIncident(
                        target.statusPageId,
                        target.organizationId,
                        linkedId,
                    )
                        ?: throw IncidentCommandNotFoundException("Linked status-page incident not found")
                }

                val statusPageIncidentId = UUID.randomUUID()
                val created = statusPageService.createIncidentWithId(
                    target.statusPageId,
                    target.organizationId,
                    statusPageIncidentId,
                    CreateIncidentRequest(
                        title = request.title.trim(),
                        status = request.status.trim(),
                        impact = request.impact.trim(),
                        message = request.message.trim(),
                    ),
                )
                linkAndRecord(
                    LinkRecordRequest(
                        target = target,
                        statusPageIncident = created,
                        sourceKey = sourceKey,
                        sourceUrl = request.sourceUrl,
                        commandKey = commandKey,
                        eventType = "STATUS_PAGE_INCIDENT_CREATED",
                        details = mapOf(
                            "statusPageIncidentId" to JsonPrimitive(created.id),
                            "status" to JsonPrimitive(created.status),
                            "impact" to JsonPrimitive(created.impact),
                        ),
                    ),
                )
                created
            }
        } catch (exception: IncidentCommandConflictException) {
            existingLinkedIncident(target.organizationId, target.incidentId, sourceKey)?.let { linkedId ->
                statusPageService.getIncident(target.statusPageId, target.organizationId, linkedId)
                    ?: throw IncidentCommandNotFoundException("Linked status-page incident not found")
            } ?: throw exception
        }
    }

    fun update(
        target: IncidentStatusPageLinkTarget,
        request: UpdateLinkedStatusPageIncidentRequest,
        commandKey: String,
    ): IncidentResponse {
        val statusPageIncidentId = checkNotNull(target.statusPageIncidentId) {
            "Status-page incident ID is required"
        }
        requireIncidentAccess(target.organizationId, target.actorUserId, target.incidentId)
        request.title?.let { validateCustomerContent(it, null) }
        request.message?.let { validateCustomerContent(null, it) }

        val source = linkedSource(target.organizationId, target.incidentId, target.statusPageId, statusPageIncidentId)
            ?: throw IncidentCommandNotFoundException("Status-page incident is not linked to this incident")

        val current = statusPageService.getIncident(target.statusPageId, target.organizationId, statusPageIncidentId)
            ?: throw IncidentCommandNotFoundException("Status-page incident not found")
        val updated = if (request.message != null) {
            val base = if (request.title != null || request.impact != null) {
                statusPageService.updateIncident(
                    target.statusPageId,
                    target.organizationId,
                    statusPageIncidentId,
                    UpdateIncidentRequest(
                        title = request.title?.trim(),
                        status = request.status?.trim(),
                        impact = request.impact?.trim(),
                    ),
                ) ?: throw IncidentCommandNotFoundException("Status-page incident not found")
            } else {
                current
            }
            statusPageService.createIncidentUpdate(
                target.statusPageId,
                target.organizationId,
                statusPageIncidentId,
                CreateIncidentUpdateRequest(
                    status = (request.status ?: base.status).trim(),
                    message = request.message.trim(),
                ),
            )
        } else {
            statusPageService.updateIncident(
                target.statusPageId,
                target.organizationId,
                statusPageIncidentId,
                UpdateIncidentRequest(
                    title = request.title?.trim(),
                    status = request.status?.trim(),
                    impact = request.impact?.trim(),
                ),
            )
        } ?: throw IncidentCommandNotFoundException("Status-page incident not found")

        timelineProducer.recordStatusPageChange(
            IncidentTimelineProducerEvent(
                organizationId = target.organizationId,
                incidentId = target.incidentId,
                eventKey = "status-page:$statusPageIncidentId:$commandKey",
                eventType = "STATUS_PAGE_INCIDENT_UPDATED",
                originalOccurredAt = Clock.System.now(),
                actorUserId = target.actorUserId,
                sourceReference = source.sourceKey,
                sourceUrl = source.sourceUrl,
                details = mapOf(
                    "statusPageIncidentId" to JsonPrimitive(updated.id),
                    "status" to JsonPrimitive(updated.status),
                    "impact" to JsonPrimitive(updated.impact),
                ),
            ),
        )
        return updated
    }

    private fun linkAndRecord(request: LinkRecordRequest) {
        incidentCommandService.execute(
            LinkIncidentSourceCommand(
                commandKey = request.commandKey,
                actor = IncidentCommandActor(request.target.organizationId, request.target.actorUserId, "STATUS_PAGE"),
                incidentId = request.target.incidentId,
                source = IncidentSourceReference(
                    sourceType = IncidentSourceType.URL,
                    sourceKey = request.sourceKey,
                    label = request.statusPageIncident.title,
            sourceUrl = request.sourceUrl.trim(),
                    metadata = mapOf(
                        "statusPageId" to JsonPrimitive(request.target.statusPageId.toString()),
                        "statusPageIncidentId" to JsonPrimitive(request.statusPageIncident.id),
                    ),
                ),
            ),
        )
        timelineProducer.recordStatusPageChange(
            IncidentTimelineProducerEvent(
                organizationId = request.target.organizationId,
                incidentId = request.target.incidentId,
                eventKey = "status-page:${request.statusPageIncident.id}:${request.eventType}",
                eventType = request.eventType,
                originalOccurredAt = Clock.System.now(),
                actorUserId = request.target.actorUserId,
                sourceReference = request.sourceKey,
                sourceUrl = request.sourceUrl,
                details = request.details,
            ),
        )
    }

    private fun existingLinkedIncident(
        organizationId: Int,
        incidentId: Int,
        sourceKey: String,
    ): UUID? = transaction {
        NativeIncidentSourceLinks
            .selectAll()
            .where {
                (NativeIncidentSourceLinks.organizationId eq organizationId) and
                    (NativeIncidentSourceLinks.incidentId eq incidentId) and
                    (NativeIncidentSourceLinks.sourceType eq IncidentSourceType.URL.wire) and
                    (NativeIncidentSourceLinks.sourceKey eq sourceKey)
            }
            .firstOrNull()
            ?.get(NativeIncidentSourceLinks.metadata)
            ?.get("statusPageIncidentId")
            ?.let { (it as? JsonPrimitive)?.content }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    private fun linkedSource(
        organizationId: Int,
        incidentId: Int,
        statusPageId: UUID,
        statusPageIncidentId: UUID,
    ): LinkedSource? = transaction {
        NativeIncidentSourceLinks
            .selectAll()
            .where {
                (NativeIncidentSourceLinks.organizationId eq organizationId) and
                    (NativeIncidentSourceLinks.incidentId eq incidentId) and
                    (NativeIncidentSourceLinks.sourceType eq IncidentSourceType.URL.wire)
            }
            .mapNotNull { row ->
                val linkedId = row[NativeIncidentSourceLinks.metadata]["statusPageIncidentId"]
                    ?.let { (it as? JsonPrimitive)?.content }
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (linkedId == statusPageIncidentId) {
                    val linkedPageId = row[NativeIncidentSourceLinks.metadata]["statusPageId"]
                        ?.let { (it as? JsonPrimitive)?.content }
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    if (linkedPageId == statusPageId) {
                        LinkedSource(row[NativeIncidentSourceLinks.sourceKey], row[NativeIncidentSourceLinks.sourceUrl])
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            .firstOrNull()
    }

    private fun requireIncidentAccess(
        organizationId: Int,
        actorUserId: Int,
        incidentId: Int,
    ) {
        val allowed = transaction {
            val incident = OnCallIncidents
                .selectAll()
                .where {
                    (OnCallIncidents.id eq incidentId) and
                        (OnCallIncidents.organizationId eq organizationId)
                }
                .firstOrNull() ?: throw IncidentCommandNotFoundException("Native incident not found")
            val role = Memberships
                .selectAll()
                .where {
                    (Memberships.organization_id eq organizationId) and
                        (Memberships.user_id eq actorUserId)
                }
                .firstOrNull()
                ?.get(Memberships.role)
                ?.let(OrgRole::fromString)
                ?: throw IncidentCommandDeniedException("Actor is not a member of the incident organization")
            if (incident[OnCallIncidents.visibility] != NativeIncidentVisibility.PRIVATE.wire ||
                role.level >= OrgRole.ADMIN.level
            ) {
                return@transaction true
            }
            val assigned = NativeIncidentRoleAssignments
                .selectAll()
                .where {
                    (NativeIncidentRoleAssignments.organizationId eq organizationId) and
                        (NativeIncidentRoleAssignments.incidentId eq incidentId) and
                        (NativeIncidentRoleAssignments.assigneeUserId eq actorUserId) and
                        NativeIncidentRoleAssignments.endedAt.isNull()
                }
                .limit(1)
                .firstOrNull() != null
            val participant = NativeIncidentParticipants
                .selectAll()
                .where {
                    (NativeIncidentParticipants.organizationId eq organizationId) and
                        (NativeIncidentParticipants.incidentId eq incidentId) and
                        (NativeIncidentParticipants.userId eq actorUserId) and
                        (NativeIncidentParticipants.participationType eq IncidentParticipationType.PARTICIPANT.wire) and
                        NativeIncidentParticipants.leftAt.isNull()
                }
                .limit(1)
                .firstOrNull() != null
            assigned || participant
        }
        if (!allowed) throw IncidentCommandDeniedException("Actor is not authorized to update this incident")
    }

    private fun validateCustomerContent(title: String?, message: String?) {
        title?.let {
            require(it.trim().isNotEmpty()) { "Status-page title is required" }
            require(it.length <= MAX_TITLE_LENGTH) { "Status-page title is too long" }
        }
        message?.let {
            require(it.trim().isNotEmpty()) { "Status-page message is required" }
            require(it.length <= MAX_MESSAGE_LENGTH) { "Status-page message is too long" }
        }
    }

    private fun requireSafeSourceUrl(value: String) {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        require(
            uri != null && uri.isAbsolute && uri.rawAuthority?.isNotBlank() == true &&
                uri.scheme.lowercase() in setOf("http", "https"),
        ) { "Status-page source URL must use HTTP or HTTPS" }
    }

    private fun requireCorrelationKey(value: String) {
        require(value.trim().isNotEmpty()) { "Status-page correlation key is required" }
        require(value.length <= MAX_CORRELATION_KEY_LENGTH) { "Status-page correlation key is too long" }
    }

    private fun sourceKey(statusPageId: UUID, correlationKey: String): String =
        "status-page:$statusPageId:${correlationKey.trim()}"

    private data class LinkedSource(
        val sourceKey: String,
        val sourceUrl: String?,
    )

    private data class LinkRecordRequest(
        val target: IncidentStatusPageLinkTarget,
        val statusPageIncident: IncidentResponse,
        val sourceKey: String,
        val sourceUrl: String,
        val commandKey: String,
        val eventType: String,
        val details: Map<String, JsonPrimitive>,
    )

    private companion object {
        const val MAX_TITLE_LENGTH = 255
        const val MAX_MESSAGE_LENGTH = 10_000
        const val MAX_CORRELATION_KEY_LENGTH = 160
    }
}
