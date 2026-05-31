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

package com.moneat.workflows.services

import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.typedWorkflowScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowActionExecutorTest {
    companion object {
        private var db: Database? = null
    }

    private val emailService = mockk<EmailService>(relaxed = true)
    private val slackService = mockk<SlackService>()
    private val discordService = mockk<DiscordService>()
    private val trustedActions = mockk<WorkflowTrustedActionExecutor>()
    private val renderer = WorkflowStepRenderer()

    private lateinit var executor: WorkflowActionExecutor
    private var orgId: Int = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_action_exec;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.dropAndPatchJsonb(Users, Organizations, Memberships)
        transaction {
            SchemaUtils.create(Users, Organizations, Memberships)
            val verified = seedUser("verified@moneat.io", verified = true)
            val unverified = seedUser("unverified@moneat.io", verified = false)
            orgId =
                Organizations.insert {
                    it[name] = "Org"
                    it[slug] = "org"
                } get Organizations.id
            listOf(verified, unverified).forEach { user ->
                Memberships.insert {
                    it[user_id] = user
                    it[organization_id] = orgId
                    it[role] = "member"
                }
            }
        }
        every { emailService.sendEmail(any(), any(), any(), any(), any()) } just runs
        executor = WorkflowActionExecutor(emailService, slackService, discordService, renderer, trustedActions)
    }

    private fun seedUser(
        email: String,
        verified: Boolean
    ): Int =
        Users.insert {
            it[Users.email] = email
            it[password_hash] = "hash"
            it[name] = email.substringBefore("@")
            it[email_verified] = verified
        } get Users.id

    private fun execute(
        step: WorkflowStepConfig,
        scope: Map<String, String> = emptyMap()
    ): Map<String, JsonElement> =
        runBlocking { executor.executeStep(orgId, step, scope.typedWorkflowScope()) }

    // ──── notification gating ────

    @Test
    fun `email step is skipped when the email channel is disabled`() {
        val result = execute(WorkflowStepConfig(EMAIL_ORG_STEP), mapOf(ALERT_CHANNEL_EMAIL_REFERENCE to "false"))
        assertEquals(true, result["skipped"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `notificationStepEnabled honours each channel flag and defaults non-notification steps to true`() {
        assertTrue(executor.notificationStepEnabled("moneat.logs.search", emptyMap()))
        assertEquals(
            false,
            executor.notificationStepEnabled(SLACK_STEP, mapOf(ALERT_CHANNEL_SLACK_REFERENCE to "false"))
        )
        assertTrue(executor.notificationStepEnabled(DISCORD_STEP, emptyMap()))
    }

    // ──── email dispatch ────

    @Test
    fun `email step sends to verified recipients and returns the count`() {
        val result =
            execute(
                WorkflowStepConfig(EMAIL_ORG_STEP, mapOf("subject" to "Hi", "body" to "Body")),
                mapOf(ALERT_CHANNEL_EMAIL_REFERENCE to "true")
            )
        assertEquals(1, result["recipient_count"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `email step renders the alert lifecycle format`() {
        val result =
            execute(
                WorkflowStepConfig(EMAIL_ORG_STEP, mapOf(FORMAT_PARAM to ALERT_LIFECYCLE_FORMAT)),
                mapOf(
                    ALERT_CHANNEL_EMAIL_REFERENCE to "true",
                    ALERT_DISPLAY_TITLE_REFERENCE to "Outage",
                    ALERT_STATUS_REFERENCE to "FIRING"
                )
            )
        assertEquals(1, result["recipient_count"]?.jsonPrimitive?.content?.toInt())
    }

    // ──── slack dispatch ────

    @Test
    fun `slack freeform step reports sent`() {
        coEvery { slackService.sendWorkflowMessage(any(), any(), any()) } returns true
        val result = execute(WorkflowStepConfig(SLACK_STEP, mapOf("message" to "hi")))
        assertEquals(true, result["sent"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `slack lifecycle step uses the alert renderer`() {
        coEvery { slackService.sendWorkflowAlertMessage(any(), any(), any()) } returns true
        val result =
            execute(
                WorkflowStepConfig(SLACK_STEP, mapOf(FORMAT_PARAM to ALERT_LIFECYCLE_FORMAT)),
                mapOf(ALERT_DISPLAY_TITLE_REFERENCE to "Outage", ALERT_STATUS_REFERENCE to "FIRING")
            )
        assertEquals(true, result["sent"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `slack step throws when the message is not sent`() {
        coEvery { slackService.sendWorkflowMessage(any(), any(), any()) } returns false
        assertFailsWith<IllegalStateException> {
            execute(WorkflowStepConfig(SLACK_STEP, mapOf("message" to "hi", "skip_if_unconfigured" to "false")))
        }
    }

    // ──── discord dispatch ────

    @Test
    fun `discord freeform step reports sent`() {
        coEvery { discordService.sendWorkflowMessage(any(), any(), any(), any()) } returns true
        val result = execute(WorkflowStepConfig(DISCORD_STEP, mapOf("title" to "T", "message" to "M")))
        assertEquals(true, result["sent"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `discord step throws when the message is not sent`() {
        coEvery { discordService.sendWorkflowMessage(any(), any(), any(), any()) } returns false
        assertFailsWith<IllegalStateException> {
            execute(WorkflowStepConfig(DISCORD_STEP, mapOf("message" to "M")))
        }
    }

    // ──── trusted-action delegation ────

    @Test
    fun `unknown step is delegated to trusted actions when supported`() {
        every { trustedActions.supports("moneat.logs.search") } returns true
        coEvery { trustedActions.execute(any(), any(), any(), any()) } returns mapOf("logs" to JsonPrimitive(true))
        val result =
            execute(
                WorkflowStepConfig("moneat.logs.search", mapOf("query" to "{{alert.title}}")),
                mapOf("alert.title" to "Boom", "workflow.actor_id" to "42")
            )
        assertTrue(result.containsKey("logs"))
    }

    @Test
    fun `unknown unsupported step throws`() {
        every { trustedActions.supports("no.such.step") } returns false
        assertFailsWith<IllegalArgumentException> {
            execute(WorkflowStepConfig("no.such.step"))
        }
    }

    @Test
    fun `executor without a trusted-action delegate rejects unknown steps`() {
        val standalone = WorkflowActionExecutor(emailService, slackService, discordService, renderer, null)
        assertFailsWith<IllegalArgumentException> {
            runBlocking { standalone.executeStep(orgId, WorkflowStepConfig("no.such.step"), emptyMap()) }
        }
    }

    // ──── test messages ────

    @Test
    fun `test message is skipped when the channel is disabled`() {
        val result =
            runBlocking {
                executor.sendTestMessageStep(
                    orgId,
                    WorkflowStepConfig(SLACK_STEP),
                    mapOf(ALERT_CHANNEL_SLACK_REFERENCE to "false")
                )
            }
        assertEquals("skipped", result.status)
    }

    @Test
    fun `test message reports sent on success`() {
        coEvery { slackService.sendWorkflowMessage(any(), any(), any()) } returns true
        val step = WorkflowStepConfig(SLACK_STEP, mapOf("message" to "hi"))
        val result = runBlocking { executor.sendTestMessageStep(orgId, step, emptyMap()) }
        assertEquals("sent", result.status)
    }

    @Test
    fun `test message reports failed when no recipients exist`() {
        val emptyOrgId =
            transaction {
                Organizations.insert {
                    it[name] = "Empty"
                    it[slug] = "empty"
                } get Organizations.id
            }
        val result =
            runBlocking {
                executor.sendTestMessageStep(emptyOrgId, WorkflowStepConfig(EMAIL_ORG_STEP), emptyMap())
            }
        assertEquals("failed", result.status)
    }

    @Test
    fun `sendOrganizationEmail counts only verified recipients`() {
        val count =
            transaction {
                executor.sendOrganizationEmail(orgId, mapOf("subject" to "S", "body" to "B"), emptyMap())
            }
        assertEquals(1, count)
    }
}
