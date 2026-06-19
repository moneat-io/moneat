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

package com.moneat.services

import com.moneat.billing.services.EntitlementService
import com.moneat.billing.services.FeatureNotAvailableException
import com.moneat.org.models.CreateOrganizationTeamRequest
import com.moneat.org.models.UpdateOrganizationTeamRequest
import com.moneat.org.repositories.OrgMembershipRepositoryImpl
import com.moneat.org.services.OrgMembershipService
import com.moneat.org.services.OrganizationTeamService
import com.moneat.shared.models.EscalationPolicies
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.OrganizationTeamMembers
import com.moneat.shared.models.OrganizationTeams
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.ktor.server.plugins.NotFoundException
import io.mockk.every
import io.mockk.mockk
import java.time.LocalTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class OrganizationTeamServiceTest {
    private var db: Database? = null
    private lateinit var entitlementService: EntitlementService
    private lateinit var service: OrganizationTeamService

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_org_teams;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            EscalationPolicies,
            OnCallSchedules,
            OrganizationTeams,
            OrganizationTeamMembers,
        )
        entitlementService = mockk()
        every { entitlementService.requireTeamsEnabled(any()) } returns Unit
        service = OrganizationTeamService(
            membershipService = OrgMembershipService(OrgMembershipRepositoryImpl()),
            entitlementService = entitlementService,
        )
    }

    @Test
    fun `createTeam stores public identifiers members and routing metadata`() {
        val orgId = insertOrg()
        val adminId = insertUser("admin@test.com")
        val memberId = insertUser("member@test.com", name = "Team Member")
        insertMembership(orgId, adminId, "admin")
        insertMembership(orgId, memberId, "member")
        val memberResourceId = userResourceId(memberId)
        val scheduleResourceId = insertSchedule(orgId)
        val policyResourceId = insertEscalationPolicy(orgId)

        val created = service.createTeam(
            orgId,
            adminId,
            CreateOrganizationTeamRequest(
                name = "Platform Core",
                description = "Owns the core API",
                slack = "#platform",
                repo = "moneat/backend",
                onCallScheduleId = scheduleResourceId,
                escalationPolicyId = policyResourceId,
                memberIds = listOf(memberResourceId, memberResourceId),
            ),
        )

        val internalTeamId = service.resolveTeamId(orgId, created.id)
        assertTrue(created.id.isUuid())
        assertNotEquals(internalTeamId.toString(), created.id)
        assertEquals("Platform Core", created.name)
        assertEquals("platform-core", created.slug)
        assertEquals("#platform", created.slack)
        assertEquals("moneat/backend", created.repo)
        assertEquals(scheduleResourceId, created.onCallScheduleId)
        assertEquals(policyResourceId, created.escalationPolicyId)
        assertNull(created.currentOnCall)
        assertEquals(listOf(memberResourceId), created.members.map { it.userId })
        assertEquals("Team Member", created.members.single().name)

        val catalogOwner = service
            .catalogOwnersByInternalIds(
                orgId,
                setOf(checkNotNull(internalTeamId)),
            )
            .values
            .single()
        assertEquals(created.id, catalogOwner.teamId)
        assertEquals("Platform Core", catalogOwner.teamName)
        assertEquals(policyResourceId, catalogOwner.escalationPolicyId)
    }

    @Test
    fun `updateTeam rejects members outside the organization`() {
        val orgId = insertOrg("Primary Org")
        val otherOrgId = insertOrg("Other Org")
        val adminId = insertUser("admin@test.com")
        val otherMemberId = insertUser("other@test.com")
        insertMembership(orgId, adminId, "admin")
        insertMembership(otherOrgId, otherMemberId, "member")
        val team = service.createTeam(orgId, adminId, CreateOrganizationTeamRequest(name = "Platform"))

        assertFailsWith<NotFoundException> {
            service.updateTeam(
                orgId,
                adminId,
                team.id,
                UpdateOrganizationTeamRequest(memberIds = listOf(userResourceId(otherMemberId))),
            )
        }

        assertTrue(service.listTeams(orgId, adminId).single().members.isEmpty())
    }

    @Test
    fun `updateTeam preserves omitted metadata and clears explicit blanks`() {
        val orgId = insertOrg()
        val adminId = insertUser("admin@test.com")
        insertMembership(orgId, adminId, "admin")
        val scheduleResourceId = insertSchedule(orgId)
        val policyResourceId = insertEscalationPolicy(orgId)
        val team = service.createTeam(
            orgId,
            adminId,
            CreateOrganizationTeamRequest(
                name = "Platform",
                description = "Owns the API",
                slack = "#platform",
                repo = "moneat/backend",
                onCallScheduleId = scheduleResourceId,
                escalationPolicyId = policyResourceId,
            ),
        )

        val renamed = service.updateTeam(
            orgId,
            adminId,
            team.id,
            UpdateOrganizationTeamRequest(name = "Platform Core"),
        )

        assertEquals("Platform Core", renamed.name)
        assertEquals("Owns the API", renamed.description)
        assertEquals("#platform", renamed.slack)
        assertEquals("moneat/backend", renamed.repo)
        assertEquals(scheduleResourceId, renamed.onCallScheduleId)
        assertEquals(policyResourceId, renamed.escalationPolicyId)

        val cleared = service.updateTeam(
            orgId,
            adminId,
            team.id,
            UpdateOrganizationTeamRequest(
                description = "",
                slack = "",
                repo = "",
                onCallScheduleId = "",
                escalationPolicyId = "",
            ),
        )

        assertNull(cleared.description)
        assertNull(cleared.slack)
        assertNull(cleared.repo)
        assertNull(cleared.onCallScheduleId)
        assertNull(cleared.escalationPolicyId)
    }

    @Test
    fun `listTeams filters stale team members that left the organization`() {
        val orgId = insertOrg()
        val adminId = insertUser("admin@test.com")
        val memberId = insertUser("member@test.com", name = "Former Member")
        insertMembership(orgId, adminId, "admin")
        insertMembership(orgId, memberId, "member")
        val memberResourceId = userResourceId(memberId)
        service.createTeam(
            orgId,
            adminId,
            CreateOrganizationTeamRequest(
                name = "Platform",
                memberIds = listOf(memberResourceId),
            ),
        )

        transaction {
            Memberships.deleteWhere {
                (Memberships.organization_id eq orgId) and (Memberships.user_id eq memberId)
            }
        }

        val team = service.listTeams(orgId, adminId).single()

        assertTrue(team.members.isEmpty())
    }

    @Test
    fun `createTeam validates members before inserting the team`() {
        val orgId = insertOrg()
        val adminId = insertUser("admin@test.com")
        insertMembership(orgId, adminId, "admin")

        assertFailsWith<NotFoundException> {
            service.createTeam(
                orgId,
                adminId,
                CreateOrganizationTeamRequest(
                    name = "Platform",
                    memberIds = listOf(Uuid.random().toString()),
                ),
            )
        }

        assertTrue(service.listTeams(orgId, adminId).isEmpty())
    }

    @Test
    fun `deleteTeam is scoped by organization`() {
        val orgId = insertOrg("Primary Org")
        val otherOrgId = insertOrg("Other Org")
        val adminId = insertUser("primary@test.com")
        val otherAdminId = insertUser("other@test.com")
        insertMembership(orgId, adminId, "admin")
        insertMembership(otherOrgId, otherAdminId, "admin")
        val team = service.createTeam(orgId, adminId, CreateOrganizationTeamRequest(name = "Platform"))

        assertFalse(service.deleteTeam(otherOrgId, otherAdminId, team.id))
        assertEquals(1, service.listTeams(orgId, adminId).size)
        assertTrue(service.deleteTeam(orgId, adminId, team.id))
        assertTrue(service.listTeams(orgId, adminId).isEmpty())
    }

    @Test
    fun `createTeam fails when teams entitlement is disabled`() {
        val orgId = insertOrg()
        val adminId = insertUser("admin@test.com")
        every { entitlementService.requireTeamsEnabled(orgId) } throws FeatureNotAvailableException(
            "Teams is not available on your current plan",
        )

        val error = assertFailsWith<FeatureNotAvailableException> {
            service.createTeam(orgId, adminId, CreateOrganizationTeamRequest(name = "Platform"))
        }

        assertEquals("Teams is not available on your current plan", error.message)
    }

    private fun insertOrg(name: String = "Test Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun insertUser(
        email: String,
        name: String? = null,
    ): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hashed"
                it[Users.name] = name
                it[email_verified] = true
            } get Users.id
        }

    private fun insertMembership(
        orgId: Int,
        userId: Int,
        role: String,
    ) {
        transaction {
            Memberships.insert {
                it[organization_id] = orgId
                it[user_id] = userId
                it[Memberships.role] = role
            }
        }
    }

    private fun userResourceId(userId: Int): String =
        transaction {
            Users.selectAll()
                .where { Users.id eq userId }
                .single()[Users.resource_id]
                .toString()
        }

    private fun insertSchedule(orgId: Int): String =
        transaction {
            val now = Clock.System.now()
            val scheduleId = OnCallSchedules.insert {
                it[organizationId] = orgId
                it[name] = "Primary Schedule"
                it[rotationType] = "weekly"
                it[handoffTime] = LocalTime.NOON
                it[timezone] = "UTC"
                it[createdAt] = now
                it[updatedAt] = now
            } get OnCallSchedules.id
            OnCallSchedules.selectAll()
                .where { OnCallSchedules.id eq scheduleId }
                .single()[OnCallSchedules.resourceId]
                .toString()
        }

    private fun insertEscalationPolicy(orgId: Int): String =
        transaction {
            val now = Clock.System.now()
            val policyId = EscalationPolicies.insert {
                it[organizationId] = orgId
                it[name] = "Default Policy"
                it[description] = null
                it[repeatCount] = 1
                it[createdAt] = now
                it[updatedAt] = now
            } get EscalationPolicies.id
            EscalationPolicies.selectAll()
                .where { EscalationPolicies.id eq policyId }
                .single()[EscalationPolicies.resourceId]
                .toString()
        }

    private fun String.isUuid(): Boolean =
        runCatching { Uuid.parse(this) }.isSuccess
}
