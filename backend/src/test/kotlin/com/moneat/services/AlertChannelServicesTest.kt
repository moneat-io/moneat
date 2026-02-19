package com.moneat.services

import com.moneat.models.EmailsSent
import com.moneat.models.OrganizationIntegrations
import com.moneat.models.Organizations
import com.moneat.models.Users
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import java.util.UUID

class AlertChannelServicesTest {
    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_alert_channel_services;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(
                    Organizations,
                    Users,
                    EmailsSent,
                    OrganizationIntegrations
                )
            }
            dbInitialized = true
        }

        transaction {
            OrganizationIntegrations.deleteAll()
            EmailsSent.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
        }
    }

    private fun seedOrg(name: String = "Channel Org"): Int = transaction {
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

        val sent = transaction {
            EmailsSent.selectAll().first { it[EmailsSent.email_type] == "uptime_alert" }
        }
        assertEquals("recipient@moneat.io", sent[EmailsSent.recipient])
        assertFalse(sent[EmailsSent.success])
    }

    @Test
    fun `SlackService returns false when integration is not configured`() = runBlocking {
        val orgId = seedOrg()
        val slackService = SlackService()

        val sent = slackService.sendSystemAlert(
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
    fun `DiscordService returns false when integration is not configured`() = runBlocking {
        val orgId = seedOrg()
        val discordService = DiscordService()

        val sent = discordService.sendSystemAlert(
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
