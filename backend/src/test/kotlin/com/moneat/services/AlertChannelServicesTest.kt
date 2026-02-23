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

import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.EmailsSent
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AlertChannelServicesTest {
    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url =
                "jdbc:h2:mem:moneat_alert_channel_services;MODE=PostgreSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            SchemaUtils.drop(OrganizationIntegrations, EmailsSent, Organizations, Users)
            SchemaUtils.create(Users, Organizations, EmailsSent, OrganizationIntegrations)
        }
    }

    private fun seedOrg(name: String = "Channel Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    @Test
    fun `EmailService tracks uptime alert email even when smtp is disabled`() {
        val emailService = EmailService()
        emailService.sendUptimeAlertEmail(
            to = "recipient@moneat.io",
            monitorName = "Checkout API",
            status = "down",
            message = "HTTP 500",
            monitorUrl = "https://app.moneat.io/uptime/abc"
        )

        val sent =
            transaction {
                EmailsSent.selectAll().first { it[EmailsSent.email_type] == "uptime_alert" }
            }
        assertEquals("recipient@moneat.io", sent[EmailsSent.recipient])
        assertFalse(sent[EmailsSent.success])
    }

    @Test
    fun `SlackService returns false when integration is not configured`() =
        runBlocking {
            val orgId = seedOrg()
            val slackService = SlackService()

            val sent =
                slackService.sendSystemAlert(
                    organizationId = orgId,
                    systemName = "api-prod",
                    metric = "CPU Usage",
                    condition = ">",
                    threshold = "80%",
                    currentValue = "95%",
                    systemId = UUID.randomUUID(),
                    baseUrl = "https://app.moneat.io"
                )

            assertFalse(sent)
        }

    @Test
    fun `DiscordService returns false when integration is not configured`() =
        runBlocking {
            val orgId = seedOrg()
            val discordService = DiscordService()

            val sent =
                discordService.sendSystemAlert(
                    organizationId = orgId,
                    systemName = "api-prod",
                    metric = "CPU Usage",
                    condition = ">",
                    threshold = "80%",
                    currentValue = "95%",
                    systemId = UUID.randomUUID(),
                    baseUrl = "https://app.moneat.io"
                )

            assertFalse(sent)
        }
}
