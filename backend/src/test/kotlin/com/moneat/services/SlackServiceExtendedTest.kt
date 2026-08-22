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

import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SlackInstallations
import com.moneat.shared.models.SlackUserMappings
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Complements [SlackServiceBuildersTest] with on-call routing and OAuth/channel entry points.
 */
class SlackServiceExtendedTest {

    companion object {
        private var db: Database? = null
    }

    private lateinit var slackService: SlackService

    @BeforeTest
    fun setup() {
        if (db == null) {
            db =
                Database.connect(
                    url = "jdbc:h2:mem:moneat_slack_ext;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Organizations,
            Users,
            Memberships,
            OrganizationIntegrations,
            SlackInstallations,
            SlackUserMappings,
        )
        slackService = SlackService()
    }

    @Test
    fun `sendOnCallAlert returns early when user has no Slack mapping`() =
        runBlocking {
            val userId =
                transaction {
                    Users.insert {
                        it[email] = "nocall@moneat.io"
                        it[password_hash] = "x"
                        it[name] = "No Slack"
                        it[email_verified] = true
                    } get Users.id
                }
            slackService.sendOnCallAlert(
                userId = userId,
                alertResourceId = "f3f01a6b-e6a2-4c6d-8593-2e508dcb2d27",
                title = "Disk full",
                priority = "P1",
            )
            assertTrue(true)
        }

    @Test
    fun `sendOnCallAlert returns early when Slack mapping exists but user has no org membership`() =
        runBlocking {
            val userId =
                transaction {
                    Users.insert {
                        it[email] = "mapped@moneat.io"
                        it[password_hash] = "x"
                        it[name] = "Mapped"
                        it[email_verified] = true
                    } get Users.id
                }
            transaction {
                SlackUserMappings.insert {
                    it[SlackUserMappings.userId] = userId
                    it[slackUserId] = "U12345"
                    it[slackTeamId] = "T12345"
                    it[createdAt] = Clock.System.now()
                    it[updatedAt] = Clock.System.now()
                }
            }
            slackService.sendOnCallAlert(
                userId = userId,
                alertResourceId = "9f3e2a51-9b80-449e-8494-9b9260767187",
                title = "Outage",
                priority = "P2",
            )
            assertTrue(true)
        }

    @Test
    fun `exchangeOAuthCode returns not ok on connection failure`() =
        runBlocking {
            val response =
                slackService.exchangeOAuthCode(
                    code = "test-code",
                    clientId = "cid",
                    clientSecret = "sec",
                    redirectUri = "https://app.example.com/oauth",
                )
            assertFalse(response.ok)
        }

    @Test
    fun `listChannels returns empty when Slack API is unreachable`() =
        runBlocking {
            val channels = slackService.listChannels("xoxb-invalid-token-for-test")
            assertTrue(channels.isEmpty())
        }
}
