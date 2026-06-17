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

package com.moneat.org.services

import com.moneat.billing.services.EntitlementService
import com.moneat.enterprise.FeatureRegistry
import com.moneat.monitor.models.CatalogCurrentOnCall
import com.moneat.monitor.models.CatalogOwner
import com.moneat.monitor.services.ResourceCatalogTeamResolver
import com.moneat.org.models.CreateOrganizationTeamRequest
import com.moneat.org.models.OrganizationTeamCurrentOnCallResponse
import com.moneat.org.models.OrganizationTeamMemberResponse
import com.moneat.org.models.OrganizationTeamResponse
import com.moneat.org.models.UpdateOrganizationTeamRequest
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.OrganizationTeamMembers
import com.moneat.shared.models.OrganizationTeams
import com.moneat.shared.models.Users
import com.moneat.shared.services.toUuidOrNull
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private const val MAX_TEAM_NAME_LENGTH = 200
private const val MAX_TEAM_SLUG_LENGTH = 120
private const val MAX_TEAM_SLUG_SUFFIX_ATTEMPTS = 1_000

class OrganizationTeamService(
    private val membershipService: OrgMembershipService,
    private val entitlementService: EntitlementService,
) : ResourceCatalogTeamResolver {
    fun listTeams(
        organizationId: Int,
        userId: Int,
    ): List<OrganizationTeamResponse> {
        entitlementService.requireTeamsEnabled(organizationId)
        membershipService.requireRole(organizationId, userId, OrgRole.MEMBER)
        val rows = teamRows(organizationId)
        val membersByTeamId = membersForTeams(organizationId, rows.map { row -> row.internalId }.toSet())
        return rows.map { row -> row.toResponse(membersByTeamId) }
    }

    fun createTeam(
        organizationId: Int,
        userId: Int,
        request: CreateOrganizationTeamRequest,
    ): OrganizationTeamResponse {
        requireTeamMutation(organizationId, userId)
        val name = normalizedRequiredName(request.name)
        val memberUserIds = resolveMemberUserIds(organizationId, request.memberIds)
        val now = Clock.System.now()
        val teamId =
            transaction {
                val slug = nextAvailableSlug(organizationId, slugForName(name))
                val scheduleId = resolveScheduleId(organizationId, request.onCallScheduleId)
                val policyId = resolveEscalationPolicyId(organizationId, request.escalationPolicyId)
                val insertedTeamId = OrganizationTeams.insertAndGetId {
                    it[OrganizationTeams.organizationId] = organizationId
                    it[OrganizationTeams.name] = name
                    it[OrganizationTeams.slug] = slug
                    it[description] = normalizedOptional(request.description)
                    it[slackChannel] = normalizedOptional(request.slack)
                    it[repository] = normalizedOptional(request.repo)
                    it[onCallScheduleId] = scheduleId
                    it[escalationPolicyId] = policyId
                    it[createdAt] = now
                    it[updatedAt] = now
                }.value
                replaceMemberUserIds(insertedTeamId, memberUserIds)
                insertedTeamId
            }
        return teamResponse(organizationId, teamId) ?: throw NotFoundException("Team not found")
    }

    fun updateTeam(
        organizationId: Int,
        userId: Int,
        teamResourceId: String,
        request: UpdateOrganizationTeamRequest,
    ): OrganizationTeamResponse {
        requireTeamMutation(organizationId, userId)
        val teamId = resolveTeamId(organizationId, teamResourceId) ?: throw NotFoundException("Team not found")
        val name = request.name?.let(::normalizedRequiredName)
        val memberUserIds = request.memberIds?.let { memberIds -> resolveMemberUserIds(organizationId, memberIds) }
        transaction {
            val current = teamRowInTx(organizationId, teamId) ?: throw NotFoundException("Team not found")
            val nextName = name ?: current.name
            val nextSlug =
                if (name == null || nextName == current.name) {
                    current.slug
                } else {
                    nextAvailableSlug(organizationId, slugForName(nextName), excludeTeamId = teamId)
                }
            val nextDescription =
                if (request.description != null) normalizedOptional(request.description) else current.description
            val nextSlack =
                if (request.slack != null) normalizedOptional(request.slack) else current.slack
            val nextRepository =
                if (request.repo != null) normalizedOptional(request.repo) else current.repo
            val scheduleId =
                if (request.onCallScheduleId != null) {
                    resolveScheduleId(organizationId, request.onCallScheduleId)
                } else {
                    current.onCallScheduleId
                }
            val policyId =
                if (request.escalationPolicyId != null) {
                    resolveEscalationPolicyId(organizationId, request.escalationPolicyId)
                } else {
                    current.escalationPolicyId
                }
            OrganizationTeams.update({
                (OrganizationTeams.organizationId eq organizationId) and (OrganizationTeams.id eq teamId)
            }) {
                it[OrganizationTeams.name] = nextName
                it[slug] = nextSlug
                it[description] = nextDescription
                it[slackChannel] = nextSlack
                it[repository] = nextRepository
                it[onCallScheduleId] = scheduleId
                it[escalationPolicyId] = policyId
                it[updatedAt] = Clock.System.now()
            }
            memberUserIds?.let { replaceMemberUserIds(teamId, it) }
        }
        return teamResponse(organizationId, teamId) ?: throw NotFoundException("Team not found")
    }

    fun deleteTeam(
        organizationId: Int,
        userId: Int,
        teamResourceId: String,
    ): Boolean {
        requireTeamMutation(organizationId, userId)
        val teamId = resolveTeamId(organizationId, teamResourceId) ?: return false
        return transaction {
            OrganizationTeams.deleteWhere {
                (OrganizationTeams.organizationId eq organizationId) and (OrganizationTeams.id eq teamId)
            } > 0
        }
    }

    override fun resolveTeamId(
        organizationId: Int,
        teamResourceId: String,
    ): Int? {
        val resourceId = teamResourceId.toUuidOrNull() ?: throw BadRequestException("Invalid team ID")
        return transaction {
            OrganizationTeams
                .selectAll()
                .where {
                    (OrganizationTeams.organizationId eq organizationId) and
                        (OrganizationTeams.resourceId eq resourceId)
                }
                .firstOrNull()
                ?.get(OrganizationTeams.id)
                ?.value
        }
    }

    override fun catalogOwnersByInternalIds(
        organizationId: Int,
        teamIds: Set<Int>,
    ): Map<Int, CatalogOwner> {
        if (teamIds.isEmpty()) return emptyMap()
        return teamRows(organizationId, teamIds).associate { row ->
            row.internalId to CatalogOwner(
                teamId = row.resourceId,
                teamName = row.name,
                slack = row.slack,
                repo = row.repo,
                onCallScheduleId = row.onCallScheduleResourceId,
                escalationPolicyId = row.escalationPolicyResourceId,
                currentOnCall = row.currentCatalogOnCall(),
            )
        }
    }

    private fun requireTeamMutation(
        organizationId: Int,
        userId: Int,
    ) {
        entitlementService.requireTeamsEnabled(organizationId)
        membershipService.requireRole(organizationId, userId, OrgRole.ADMIN)
    }

    private fun teamResponse(
        organizationId: Int,
        teamId: Int,
    ): OrganizationTeamResponse? {
        val row = teamRows(organizationId, setOf(teamId)).firstOrNull() ?: return null
        return row.toResponse(membersForTeams(organizationId, setOf(teamId)))
    }

    private fun TeamRow.toResponse(membersByTeamId: Map<Int, List<OrganizationTeamMemberResponse>>) =
        OrganizationTeamResponse(
            id = resourceId,
            name = name,
            slug = slug,
            description = description,
            slack = slack,
            repo = repo,
            onCallScheduleId = onCallScheduleResourceId,
            escalationPolicyId = escalationPolicyResourceId,
            currentOnCall = currentOrganizationOnCall(),
            members = membersByTeamId[internalId].orEmpty(),
        )

    private fun TeamRow.currentCatalogOnCall(): CatalogCurrentOnCall? {
        val onCall = currentOnCall() ?: return null
        return CatalogCurrentOnCall(userId = onCall.userId, userName = onCall.userName)
    }

    private fun TeamRow.currentOrganizationOnCall(): OrganizationTeamCurrentOnCallResponse? {
        val onCall = currentOnCall() ?: return null
        return OrganizationTeamCurrentOnCallResponse(userId = onCall.userId, userName = onCall.userName)
    }

    private fun TeamRow.currentOnCall() =
        onCallScheduleId?.let { scheduleId ->
            FeatureRegistry.getOnCallBridge()?.getCurrentOnCall(organizationId, scheduleId)
        }

    private fun teamRows(
        organizationId: Int,
        teamIds: Set<Int>? = null,
    ): List<TeamRow> =
        transaction { teamRowsInTx(organizationId, teamIds) }

    private fun teamRowsInTx(
        organizationId: Int,
        teamIds: Set<Int>? = null,
    ): List<TeamRow> {
        val condition =
            if (teamIds == null) {
                OrganizationTeams.organizationId eq organizationId
            } else {
                (OrganizationTeams.organizationId eq organizationId) and (OrganizationTeams.id inList teamIds)
            }
        val rows =
            OrganizationTeams
                .selectAll()
                .where { condition }
                .orderBy(OrganizationTeams.name to SortOrder.ASC)
                .toList()

        val scheduleResourceIds = scheduleResourceIds(rows.mapNotNull { it[OrganizationTeams.onCallScheduleId] })
        val policyResourceIds = policyResourceIds(rows.mapNotNull { it[OrganizationTeams.escalationPolicyId] })
        return rows.map { row -> row.toTeamRow(scheduleResourceIds, policyResourceIds) }
    }

    private fun teamRow(
        organizationId: Int,
        teamId: Int,
    ): TeamRow? = teamRows(organizationId, setOf(teamId)).firstOrNull()

    private fun teamRowInTx(
        organizationId: Int,
        teamId: Int,
    ): TeamRow? = teamRowsInTx(organizationId, setOf(teamId)).firstOrNull()

    private fun ResultRow.toTeamRow(
        scheduleResourceIds: Map<Int, String>,
        policyResourceIds: Map<Int, String>,
    ): TeamRow {
        val scheduleId = this[OrganizationTeams.onCallScheduleId]
        val policyId = this[OrganizationTeams.escalationPolicyId]
        return TeamRow(
            internalId = this[OrganizationTeams.id].value,
            resourceId = this[OrganizationTeams.resourceId].toString(),
            organizationId = this[OrganizationTeams.organizationId],
            name = this[OrganizationTeams.name],
            slug = this[OrganizationTeams.slug],
            description = this[OrganizationTeams.description],
            slack = this[OrganizationTeams.slackChannel],
            repo = this[OrganizationTeams.repository],
            onCallScheduleId = scheduleId,
            onCallScheduleResourceId = scheduleId?.let(scheduleResourceIds::get),
            escalationPolicyId = policyId,
            escalationPolicyResourceId = policyId?.let(policyResourceIds::get),
        )
    }

    private fun scheduleResourceIds(scheduleIds: List<Int>): Map<Int, String> {
        if (scheduleIds.isEmpty()) return emptyMap()
        return OnCallSchedules
            .selectAll()
            .where { OnCallSchedules.id inList scheduleIds.distinct() }
            .associate { row -> row[OnCallSchedules.id].value to row[OnCallSchedules.resourceId].toString() }
    }

    private fun policyResourceIds(policyIds: List<Int>): Map<Int, String> {
        if (policyIds.isEmpty()) return emptyMap()
        return EscalationPolicies
            .selectAll()
            .where { EscalationPolicies.id inList policyIds.distinct() }
            .associate { row -> row[EscalationPolicies.id].value to row[EscalationPolicies.resourceId].toString() }
    }

    private fun membersForTeams(
        organizationId: Int,
        teamIds: Set<Int>,
    ): Map<Int, List<OrganizationTeamMemberResponse>> {
        if (teamIds.isEmpty()) return emptyMap()
        return transaction {
            (OrganizationTeamMembers innerJoin Users innerJoin Memberships)
                .selectAll()
                .where {
                    (OrganizationTeamMembers.teamId inList teamIds) and
                        (Memberships.organization_id eq organizationId) and
                        (Memberships.user_id eq OrganizationTeamMembers.userId)
                }
                .orderBy(Users.email to SortOrder.ASC)
                .groupBy(
                    keySelector = { row -> row[OrganizationTeamMembers.teamId] },
                    valueTransform = { row ->
                        OrganizationTeamMemberResponse(
                            userId = row[Users.resource_id].toString(),
                            email = row[Users.email],
                            name = row[Users.name],
                        )
                    },
                )
        }
    }

    private fun resolveMemberUserIds(
        organizationId: Int,
        memberIds: List<String>,
    ): List<Int> =
        memberIds.distinct().map { memberResourceId ->
            val uuid = memberResourceId.toUuidOrNull() ?: throw BadRequestException("Invalid team member ID")
            membershipService.resolveMemberUserId(organizationId, uuid)
                ?: throw NotFoundException("Team member not found")
        }

    private fun replaceMemberUserIds(
        teamId: Int,
        userIds: List<Int>,
    ) {
        OrganizationTeamMembers.deleteWhere { OrganizationTeamMembers.teamId eq teamId }
        for (memberUserId in userIds) {
            OrganizationTeamMembers.insert {
                it[OrganizationTeamMembers.teamId] = teamId
                it[userId] = memberUserId
                it[createdAt] = Clock.System.now()
            }
        }
    }

    private fun resolveScheduleId(
        organizationId: Int,
        scheduleResourceId: String?,
    ): Int? {
        val raw = scheduleResourceId?.takeIf { it.isNotBlank() } ?: return null
        val resourceId = raw.toUuidOrNull() ?: throw BadRequestException("Invalid on-call schedule ID")
        return OnCallSchedules
            .selectAll()
            .where {
                (OnCallSchedules.organizationId eq organizationId) and
                    (OnCallSchedules.resourceId eq resourceId)
            }
            .firstOrNull()
            ?.get(OnCallSchedules.id)
            ?.value
            ?: throw NotFoundException("On-call schedule not found")
    }

    private fun resolveEscalationPolicyId(
        organizationId: Int,
        policyResourceId: String?,
    ): Int? {
        val raw = policyResourceId?.takeIf { it.isNotBlank() } ?: return null
        val resourceId = raw.toUuidOrNull() ?: throw BadRequestException("Invalid escalation policy ID")
        return EscalationPolicies
            .selectAll()
            .where {
                (EscalationPolicies.organizationId eq organizationId) and
                    (EscalationPolicies.resourceId eq resourceId)
            }
            .firstOrNull()
            ?.get(EscalationPolicies.id)
            ?.value
            ?: throw NotFoundException("Escalation policy not found")
    }

    private fun nextAvailableSlug(
        organizationId: Int,
        baseSlug: String,
        excludeTeamId: Int? = null,
    ): String {
        var attempt = 1
        while (attempt <= MAX_TEAM_SLUG_SUFFIX_ATTEMPTS) {
            val suffix = if (attempt == 1) "" else "-$attempt"
            val slug = baseSlug.truncateForSuffix(suffix)
            val exists =
                OrganizationTeams
                    .selectAll()
                    .where {
                        (OrganizationTeams.organizationId eq organizationId) and
                            (OrganizationTeams.slug eq slug)
                    }
                    .any { row -> excludeTeamId == null || row[OrganizationTeams.id].value != excludeTeamId }
            if (!exists) return slug
            attempt += 1
        }
        throw BadRequestException("Unable to generate unique team slug")
    }

    private fun String.truncateForSuffix(suffix: String): String {
        val maxBaseLength = MAX_TEAM_SLUG_LENGTH - suffix.length
        return take(maxBaseLength.coerceAtLeast(1)).trim('-') + suffix
    }

    private fun normalizedRequiredName(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) throw BadRequestException("Team name is required")
        if (normalized.length > MAX_TEAM_NAME_LENGTH) {
            throw BadRequestException("Team name must be $MAX_TEAM_NAME_LENGTH characters or fewer")
        }
        return normalized
    }

    private fun normalizedOptional(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() }

    private fun slugForName(name: String): String =
        name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "team" }
            .take(MAX_TEAM_SLUG_LENGTH)
            .trim('-')
            .ifBlank { "team" }
}

private data class TeamRow(
    val internalId: Int,
    val resourceId: String,
    val organizationId: Int,
    val name: String,
    val slug: String,
    val description: String?,
    val slack: String?,
    val repo: String?,
    val onCallScheduleId: Int?,
    val onCallScheduleResourceId: String?,
    val escalationPolicyId: Int?,
    val escalationPolicyResourceId: String?,
)
