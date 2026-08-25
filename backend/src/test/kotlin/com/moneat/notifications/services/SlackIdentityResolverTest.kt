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

package com.moneat.notifications.services

import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SlackUserMappings
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class SlackIdentityResolverTest {
    companion object {
        private var database: Database? = null
    }

    @BeforeTest
    fun setUp() {
        if (database == null) {
            database = Database.connect(
                url = "jdbc:h2:mem:moneat_slack_identity;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = database
        TestDatabaseHelper.resetSchema(
            Organizations,
            Users,
            Memberships,
            OrganizationIntegrations,
            SlackUserMappings,
        )
    }

    @Test
    fun `maps active Slack user only through an enabled workspace integration`() {
        val (organizationId, userId) = seedMember("member@moneat.io", "T-active", "U-active")

        val result = SlackIdentityResolver().resolve(
            SlackIdentityRequest(teamId = "T-active", userId = "U-active"),
        )

        assertEquals(SlackIdentityStatus.MAPPED, result.status)
        assertEquals(organizationId, result.organizationId)
        assertEquals(userId, result.userId)
        assertTrue(result.isMapped)
        assertTrue(result.canRespond)
    }

    @Test
    fun `unmapped identity receives only the linking path`() {
        val result = SlackIdentityResolver().resolve(
            SlackIdentityRequest(teamId = "T-unknown", userId = "U-unknown"),
        )

        assertEquals(SlackIdentityStatus.UNMAPPED, result.status)
        assertFalse(result.isMapped)
        assertFalse(result.canRespond)
        assertTrue(result.message.contains("link", ignoreCase = true))
    }

    @Test
    fun `guest and external identities fail closed before database lookup`() {
        val guest = SlackIdentityResolver().resolve(
            SlackIdentityRequest(teamId = "T-guest", userId = "U-guest", isGuest = true),
        )
        val external = SlackIdentityResolver().resolve(
            SlackIdentityRequest(teamId = "T-external", userId = "U-external", isExternal = true),
        )

        assertEquals(SlackIdentityStatus.GUEST, guest.status)
        assertEquals(SlackIdentityStatus.EXTERNAL, external.status)
        assertFalse(guest.canRespond)
        assertFalse(external.canRespond)
    }

    @Test
    fun `cross organization and deleted members cannot be resolved`() {
        val (organizationId, userId) = seedMember("deleted@moneat.io", "T-cross", "U-cross")
        val otherOrganizationId = seedOrganization("other")
        transaction {
            Users.update({ Users.id eq userId }) {
                it[deletedAt] = Clock.System.now()
            }
        }

        val deleted = SlackIdentityResolver().resolve(
            SlackIdentityRequest(teamId = "T-cross", userId = "U-cross", organizationId = organizationId),
        )
        assertEquals(SlackIdentityStatus.REVOKED, deleted.status)

        val activeUser = transaction {
            Users.insert {
                it[Users.email] = "cross@moneat.io"
                it[password_hash] = "test"
                it[name] = "cross"
            } get Users.id
        }
        transaction {
            Memberships.insert {
                it[user_id] = activeUser
                it[Memberships.organization_id] = otherOrganizationId
                it[role] = "MEMBER"
            }
            SlackUserMappings.insert {
                it[SlackUserMappings.userId] = activeUser
                it[SlackUserMappings.slackUserId] = "U-cross-active"
                it[SlackUserMappings.slackTeamId] = "T-cross"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }
        val crossOrganization = SlackIdentityResolver().resolve(
            SlackIdentityRequest(teamId = "T-cross", userId = "U-cross-active", organizationId = organizationId),
        )
        assertEquals(SlackIdentityStatus.CROSS_ORGANIZATION, crossOrganization.status)
    }

    private fun seedMember(email: String, teamId: String, slackUserId: String): Pair<Int, Int> {
        val organizationId = seedOrganization(teamId)
        val userId = transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "test"
                it[name] = email.substringBefore('@')
            } get Users.id
        }
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[Memberships.organization_id] = organizationId
                it[role] = "MEMBER"
            }
            SlackUserMappings.insert {
                it[SlackUserMappings.userId] = userId
                it[SlackUserMappings.slackUserId] = slackUserId
                it[SlackUserMappings.slackTeamId] = teamId
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }
        return organizationId to userId
    }

    private fun seedOrganization(teamId: String): Int = transaction {
        val organizationId = Organizations.insert {
            it[name] = teamId
            it[slug] = teamId.lowercase()
        } get Organizations.id
        OrganizationIntegrations.insert {
            it[organization_id] = organizationId
            it[integration_type] = "slack"
            it[OrganizationIntegrations.team_id] = teamId
            it[enabled] = true
            it[created_at] = Clock.System.now()
            it[updated_at] = Clock.System.now()
        }
        organizationId
    }
}
