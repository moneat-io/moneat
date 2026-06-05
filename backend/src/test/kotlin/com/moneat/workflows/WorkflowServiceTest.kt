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

package com.moneat.workflows

import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertEpisodes
import com.moneat.alerts.models.AlertPriority
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.models.IncidentSeverity
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.security.signals.SignalOutcome
import com.moneat.security.signals.SignalSeverity
import com.moneat.security.signals.SignalSource
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.workflows.engine.WorkflowCatalog
import com.moneat.workflows.engine.temporal.WorkflowExecutionEngine
import com.moneat.workflows.engine.temporal.WorkflowStartRequest
import com.moneat.workflows.engine.temporal.WorkflowStartResult
import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.UpdateWorkflowRequest
import com.moneat.workflows.models.WorkflowAuditEvents
import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowPreviewRequest
import com.moneat.workflows.models.WorkflowRunInstanceRequest
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.WorkflowRunSteps
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.WorkflowUsageEvents
import com.moneat.workflows.models.WorkflowVersions
import com.moneat.workflows.models.Workflows
import com.moneat.workflows.models.typedWorkflowScope
import com.moneat.workflows.services.WorkflowService
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class WorkflowServiceTest {
    companion object {
        private var db: Database? = null
    }

    private val emailService = mockk<EmailService>(relaxed = true)
    private val slackService = mockk<SlackService>()
    private val discordService = mockk<DiscordService>()
    private lateinit var workflowEngine: FakeWorkflowExecutionEngine
    private lateinit var service: WorkflowService
    private var orgId: Int = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_workflows;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        resetWorkflowSchema()
        workflowEngine = FakeWorkflowExecutionEngine()
        clearMocks(emailService, slackService, discordService)
        every { emailService.sendEmail(any(), any(), any(), any(), any()) } just runs
        coEvery { slackService.sendWorkflowMessage(any(), any(), any()) } returns true
        coEvery { slackService.sendWorkflowAlertMessage(any(), any(), any()) } returns true
        coEvery { discordService.sendWorkflowMessage(any(), any(), any(), any()) } returns true
        coEvery { discordService.sendWorkflowAlertMessage(any(), any(), any()) } returns true
        service = WorkflowService(emailService, slackService, discordService, executionEngine = workflowEngine)
        orgId = seedOrganizationWithMembers()
    }

    private fun resetWorkflowSchema() {
        TestDatabaseHelper.dropAndPatchJsonb(
            Users,
            Organizations,
            Memberships,
            Workflows,
            WorkflowVersions,
            WorkflowRuns,
            WorkflowRunSteps,
            WorkflowAuditEvents,
            WorkflowUsageEvents,
            AlertEpisodes
        )
        transaction {
            SchemaUtils.create(Users, Organizations, Memberships, Workflows, AlertEpisodes)
            exec("DROP TABLE IF EXISTS workflow_runs")
            exec("DROP TABLE IF EXISTS workflow_versions")
            exec(
                """
                CREATE TABLE workflow_versions (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    workflow_id INT NOT NULL,
                    version INT NOT NULL,
                    conditions TEXT NOT NULL DEFAULT '[]',
                    steps TEXT NOT NULL DEFAULT '[]',
                    graph TEXT NOT NULL DEFAULT '{"nodes":[],"edges":[]}',
                    published BOOLEAN NOT NULL DEFAULT FALSE,
                    input_schema TEXT NOT NULL DEFAULT '{}',
                    tags TEXT NOT NULL DEFAULT '[]',
                    once_for_template TEXT NOT NULL DEFAULT '[]',
                    engine_config TEXT NOT NULL DEFAULT '{}',
                    most_recent BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_workflow_versions_workflow_id
                        FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            exec("CREATE UNIQUE INDEX idx_workflow_versions_version ON workflow_versions (workflow_id, version)")
            exec(
                """
                CREATE TABLE workflow_runs (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    workflow_id INT NOT NULL,
                    workflow_version_id INT NOT NULL,
                    organization_id INT NOT NULL,
                    trigger_name VARCHAR(120) NOT NULL,
                    once_for TEXT NOT NULL,
                    scope TEXT NOT NULL DEFAULT '{}',
                    status VARCHAR(32) NOT NULL DEFAULT 'pending',
                    active_once_for VARCHAR(4096) GENERATED ALWAYS AS (
                        CASEWHEN(
                            status = 'pending',
                            CAST(once_for AS VARCHAR(4096)),
                            CASEWHEN(status = 'running', CAST(once_for AS VARCHAR(4096)), NULL)
                        )
                    ),
                    progress TEXT NOT NULL DEFAULT '[]',
                    error_message TEXT,
                    temporal_workflow_id VARCHAR(255),
                    temporal_run_id VARCHAR(255),
                    created_at TIMESTAMP NOT NULL,
                    completed_at TIMESTAMP,
                    failed_at TIMESTAMP,
                    CONSTRAINT fk_workflow_runs_workflow_id
                        FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE,
                    CONSTRAINT fk_workflow_runs_workflow_version_id
                        FOREIGN KEY (workflow_version_id) REFERENCES workflow_versions(id),
                    CONSTRAINT fk_workflow_runs_organization_id
                        FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE UNIQUE INDEX idx_workflow_runs_idempotency_key
                    ON workflow_runs (workflow_id, active_once_for)
                """.trimIndent()
            )
            exec("CREATE INDEX idx_workflow_runs_workflow_created ON workflow_runs (workflow_id, created_at DESC)")
            exec(
                """
                CREATE TABLE workflow_run_steps (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    run_id INT NOT NULL,
                    node_id VARCHAR(120) NOT NULL,
                    type VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    started_at TIMESTAMP,
                    completed_at TIMESTAMP,
                    input TEXT NOT NULL DEFAULT '{}',
                    output TEXT NOT NULL DEFAULT '{}',
                    error_message TEXT,
                    attempt INT NOT NULL DEFAULT 1,
                    CONSTRAINT fk_workflow_run_steps_run_id
                        FOREIGN KEY (run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE UNIQUE INDEX idx_workflow_run_steps_attempt
                    ON workflow_run_steps (run_id, node_id, attempt)
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE workflow_audit_events (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    organization_id INT NOT NULL,
                    workflow_id INT,
                    run_id INT,
                    action VARCHAR(48) NOT NULL,
                    actor_user_id INT,
                    detail TEXT NOT NULL DEFAULT '{}',
                    created_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_workflow_audit_events_org
                        FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
                    CONSTRAINT fk_workflow_audit_events_workflow
                        FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE workflow_usage_events (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    organization_id INT NOT NULL,
                    workflow_id INT,
                    run_id INT NOT NULL,
                    period VARCHAR(7) NOT NULL,
                    outcome VARCHAR(16) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_workflow_usage_events_org
                        FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
                    CONSTRAINT uq_workflow_usage_events_run_outcome UNIQUE (run_id, outcome)
                )
                """.trimIndent()
            )
        }
    }

    @Test
    fun `catalog exposes alert triggers resources steps and lookup helpers`() {
        val response = service.catalog()

        assertEquals(10, response.resources.size)
        assertTrue(response.resources.any { it.type == "Boolean" })
        assertTrue(response.resources.any { it.type == "AlertPriority" })
        assertTrue(response.resources.any { it.type == "AlertStatus" })
        assertTrue(response.resources.any { it.type == "IncidentSeverity" })
        assertTrue(response.resources.any { it.type == "SecuritySeverity" })
        assertTrue(
            response.resources
                .first { it.type == "String" }
                .operations
                .any { it.name == "contains" && it.valueType == "String" }
        )
        assertEquals("alert.triggered", response.triggers.first().name)
        assertEquals(
            listOf("alert.episode_key", "alert.notification_sequence"),
            response.triggers.first().defaultOnceForTemplate
        )
        val resolvedTrigger = response.triggers.first { it.name == "alert.resolved" }
        assertEquals(
            listOf("alert.episode_key", "alert.status"),
            resolvedTrigger.defaultOnceForTemplate
        )
        assertTrue(response.triggers.any { it.name == "api" })
        assertTrue(response.triggers.any { it.name == "webhook" })
        assertTrue(response.triggers.any { it.name == "security.signal" })
        assertTrue(response.steps.any { it.name == "moneat.logs.search" })
        assertTrue(response.steps.any { it.name == "statuspage.incident.create" })
        assertTrue(response.steps.any { it.name == "oncall.incident.declare" })
        // http.request and transform.graaljs are hidden unless WORKFLOWS_EGRESS_ENABLED is set;
        // their visibility in both states is covered by WorkflowCatalogTest.
        assertFalse(response.steps.any { it.name == "http.request" })
        assertFalse(response.steps.any { it.name == "transform.graaljs" })
        assertEquals("Email organization members", WorkflowCatalog.step("notification.email_org")?.label)
        assertEquals("AlertPriority", WorkflowCatalog.scopeType("alert.triggered", "alert.priority"))
        assertEquals("Boolean", WorkflowCatalog.scopeType("alert.triggered", "alert.channels.email"))
        assertNull(WorkflowCatalog.trigger("missing.trigger"))
        assertNull(WorkflowCatalog.step("notification.unknown"))
        assertNull(WorkflowCatalog.scopeType("missing.trigger", "alert.priority"))
    }

    @Test
    fun `default workflows are seeded once for each organization`() {
        val secondOrgId = seedOrganization("Second Org")

        service.ensureDefaultWorkflowsForAllOrganizations()
        service.ensureDefaultWorkflowsForOrganization(orgId)

        val firstOrgWorkflows = service.listWorkflows(orgId)
        val secondOrgWorkflows = service.listWorkflows(secondOrgId)
        assertEquals(2, firstOrgWorkflows.size)
        assertEquals(2, secondOrgWorkflows.size)
        assertEquals(
            setOf("Send alert notifications", "Send recovery notifications"),
            firstOrgWorkflows.map { it.name }.toSet()
        )
        assertTrue(firstOrgWorkflows.all { it.enabled && it.version == 1 })
        assertTrue(firstOrgWorkflows.any { it.triggerName == "alert.triggered" })
        assertTrue(firstOrgWorkflows.any { it.triggerName == "alert.resolved" })
        assertEquals(
            setOf("default_alert_notifications", "default_recovery_notifications"),
            firstOrgWorkflows.mapNotNull { it.systemKey }.toSet()
        )
        val defaultAlertWorkflow = firstOrgWorkflows.first { it.triggerName == "alert.triggered" }
        assertEquals(3, defaultAlertWorkflow.steps.size)
        assertTrue(defaultAlertWorkflow.steps.all { it.params["format"] == "alert_lifecycle" })
        assertFailsWith<IllegalArgumentException> {
            service.updateWorkflow(orgId, firstOrgWorkflows.first().id, UpdateWorkflowRequest(name = "Edited default"))
        }
        assertFailsWith<IllegalArgumentException> {
            service.deleteWorkflow(orgId, firstOrgWorkflows.first().id)
        }
        assertEquals(2L, transaction { Workflows.selectAll().where { Workflows.organizationId eq orgId }.count() })
    }

    @Test
    fun `workflow preview renders rich alert lifecycle messages`() {
        val response =
            service.previewWorkflow(
                WorkflowPreviewRequest(
                    triggerName = "alert.resolved",
                    steps = listOf(
                        WorkflowStepConfig(
                            name = "notification.email_org",
                            params = mapOf("format" to "alert_lifecycle")
                        ),
                        WorkflowStepConfig(
                            name = "notification.slack",
                            params = mapOf("format" to "alert_lifecycle")
                        )
                    )
                )
            )

        assertEquals("RESOLVED", response.scope["alert.status"])
        assertEquals("P3", response.scope["alert.priority"])
        assertEquals(2, response.previews.size)
        val emailPreview = response.previews.first { it.channel == "email" }
        assertEquals("[Moneat] P3 Resolved: Worker failures detected", emailPreview.subject)
        assertEquals("#2EB67D", emailPreview.color)
        assertTrue(emailPreview.htmlBody.orEmpty().contains("View"))
        assertTrue(emailPreview.htmlBody.orEmpty().contains("/favicon.svg"))
        assertFalse(emailPreview.htmlBody.orEmpty().contains(">APP</span>"))
        assertTrue(emailPreview.fields.any { it.label == "Dashboard" })
        assertTrue(emailPreview.fields.any { it.label == "Priority" && it.value == "P3" })
        val slackPreview = response.previews.first { it.channel == "slack" }
        assertEquals("P3 Resolved: Worker failures detected", slackPreview.title)
        assertEquals("View", slackPreview.ctaLabel)
        assertTrue(slackPreview.textBody.contains("Status: Resolved"))
    }

    @Test
    fun `workflow preview renders freeform notification messages`() {
        val response =
            service.previewWorkflow(
                WorkflowPreviewRequest(
                    triggerName = "alert.triggered",
                    steps = listOf(emailStep(), slackStep(), discordStep())
                )
            )

        assertEquals(3, response.previews.size)
        val emailPreview = response.previews.first { it.channel == "email" }
        assertEquals("Workflow Dashboard Error: Worker failures detected", emailPreview.subject)
        assertTrue(emailPreview.htmlBody.orEmpty().contains("Worker failures [1h]"))
        assertTrue(emailPreview.fallbackText.contains("DASHBOARD_ALERT"))

        val slackPreview = response.previews.first { it.channel == "slack" }
        assertEquals("Moneat workflow", slackPreview.title)
        assertTrue(slackPreview.body.contains("https://moneat.io/dashboards/13"))

        val discordPreview = response.previews.first { it.channel == "discord" }
        assertEquals("Dashboard Error: Worker failures detected", discordPreview.title)
        assertEquals("Moneat workflow", discordPreview.footer)
        assertTrue(discordPreview.fallbackText.contains("DASHBOARD_ALERT"))
    }

    @Test
    fun `workflow preview rejects unknown triggers`() {
        assertFailsWith<IllegalArgumentException> {
            service.previewWorkflow(
                WorkflowPreviewRequest(
                    triggerName = "alert.missing",
                    steps = listOf(slackStep())
                )
            )
        }
    }

    @Test
    fun `testWorkflowMessage sends representative alert messages without creating a run`() =
        runBlocking {
            val request =
                WorkflowPreviewRequest(
                    triggerName = "alert.triggered",
                    steps = listOf(
                        WorkflowStepConfig(
                            name = "notification.email_org",
                            params = mapOf("format" to "alert_lifecycle")
                        ),
                        WorkflowStepConfig(
                            name = "notification.slack",
                            params = mapOf("format" to "alert_lifecycle")
                        ),
                        WorkflowStepConfig(
                            name = "notification.discord",
                            params = mapOf("format" to "alert_lifecycle")
                        )
                    )
                )

            val response = service.testWorkflowMessage(orgId, request)

            assertEquals(listOf("sent", "sent", "sent"), response.results.map { it.status })
            assertEquals(emptyList(), service.listRuns(orgId, workflowId = 1))
            verify(exactly = 1) {
                emailService.sendEmail(
                    "verified@moneat.io",
                    "[Moneat] P1 Worker failures detected",
                    match { it.contains("Added by Moneat") && it.contains("/favicon.svg") },
                    match { it.contains("View: https://moneat.io/dashboards/13") },
                    "workflow"
                )
            }
            verify(exactly = 0) {
                emailService.sendEmail("unverified@moneat.io", any(), any(), any(), any())
            }
            coVerify(exactly = 1) {
                slackService.sendWorkflowAlertMessage(
                    orgId,
                    match { it.ctaLabel == "View" && it.title.contains("P1") },
                    false
                )
            }
            coVerify(exactly = 1) {
                discordService.sendWorkflowAlertMessage(
                    orgId,
                    match { it.ctaLabel == "View" && it.title.contains("P1") },
                    false
                )
            }
        }

    @Test
    fun `testWorkflowMessage sends freeform Slack and Discord messages`() =
        runBlocking {
            val response =
                service.testWorkflowMessage(
                    orgId,
                    WorkflowPreviewRequest(
                        triggerName = "alert.triggered",
                        steps = listOf(slackStep(), discordStep())
                    )
                )

            assertEquals(listOf("sent", "sent"), response.results.map { it.status })
            coVerify(exactly = 1) {
                slackService.sendWorkflowMessage(
                    orgId,
                    match { it.contains("Worker failures detected") && it.contains("https://moneat.io/dashboards/13") },
                    false
                )
            }
            coVerify(exactly = 1) {
                discordService.sendWorkflowMessage(
                    orgId,
                    "Dashboard Error: Worker failures detected",
                    match { it.contains("DASHBOARD_ALERT") && it.contains("FIRING") },
                    false
                )
            }
        }

    @Test
    fun `testWorkflowMessage reports skipped and failed notification steps`() =
        runBlocking {
            coEvery { discordService.sendWorkflowAlertMessage(any(), any(), any()) } returns false

            val response =
                service.testWorkflowMessage(
                    orgId,
                    WorkflowPreviewRequest(
                        triggerName = "alert.triggered",
                        steps = listOf(
                            WorkflowStepConfig(
                                name = "notification.slack",
                                params = mapOf(
                                    "format" to "alert_lifecycle",
                                    "message" to "{{alert.description}}"
                                )
                            ),
                            WorkflowStepConfig(
                                name = "notification.discord",
                                params = mapOf(
                                    "format" to "alert_lifecycle",
                                    "title" to "{{alert.title}}",
                                    "message" to "{{alert.description}}"
                                )
                            )
                        ),
                        scope = mapOf("alert.channels.slack" to "false").typedWorkflowScope()
                    )
                )

            val resultsByChannel = response.results.associateBy { it.channel }
            assertEquals("skipped", resultsByChannel["slack"]?.status)
            assertEquals(
                "Notification channel is disabled for this sample",
                resultsByChannel["slack"]?.errorMessage
            )
            assertEquals("failed", resultsByChannel["discord"]?.status)
            assertEquals("Discord test message was not sent", resultsByChannel["discord"]?.errorMessage)
            coVerify(exactly = 0) { slackService.sendWorkflowAlertMessage(any(), any(), any()) }
            coVerify(exactly = 1) {
                discordService.sendWorkflowAlertMessage(
                    orgId,
                    match { it.title.contains("P1") },
                    false
                )
            }
        }

    @Test
    fun `create update list get and delete workflow validates the catalog contract`() {
        val created =
            service.createWorkflow(
                orgId,
                CreateWorkflowRequest(
                    name = "  Critical alert workflow  ",
                    triggerName = "alert.triggered",
                    conditions = listOf(
                        WorkflowConditionConfig("alert.title", "is_set"),
                        WorkflowConditionConfig("alert.description", "contains", "cpu"),
                        WorkflowConditionConfig("alert.source", "neq", "ERROR_ALERT"),
                        WorkflowConditionConfig("alert.priority", "at_least", "P1")
                    ),
                    steps = listOf(slackStep(), discordStep()),
                    onceForTemplate = listOf("alert.deduplication_key", "alert.status")
                )
            )

        assertEquals("Critical alert workflow", created.name)
        assertEquals(1, created.version)
        assertEquals(created.id, service.getWorkflow(orgId, created.id)?.id)
        assertEquals(listOf(created.id), service.listWorkflows(orgId).map { it.id })

        val renamed = service.updateWorkflow(orgId, created.id, UpdateWorkflowRequest(name = "  Renamed  "))
        assertEquals("Renamed", renamed?.name)
        assertEquals(1, renamed?.version)

        val updated =
            service.updateWorkflow(
                orgId,
                created.id,
                UpdateWorkflowRequest(
                    enabled = false,
                    conditions = listOf(WorkflowConditionConfig("alert.status", "eq", "FIRING")),
                    steps = listOf(emailStep()),
                    onceForTemplate = listOf("alert.deduplication_key")
                )
            )
        assertEquals(2, updated?.version)
        assertFalse(updated?.enabled ?: true)
        val thirdVersion =
            service.updateWorkflow(
                orgId,
                created.id,
                UpdateWorkflowRequest(
                    enabled = true,
                    conditions = listOf(WorkflowConditionConfig("alert.channels.email", "eq", "true")),
                    steps = listOf(slackStep())
                )
            )
        assertEquals(3, thirdVersion?.version)
        assertEquals(emptyList(), service.listRuns(orgId, created.id))
        assertNull(service.getWorkflow(orgId + 1, created.id))
        assertNull(service.updateWorkflow(orgId, created.id + 1000, UpdateWorkflowRequest(name = "missing")))
        assertFailsWith<IllegalArgumentException> {
            service.updateWorkflow(orgId, created.id, UpdateWorkflowRequest(name = " "))
        }
        assertFalse(service.deleteWorkflow(orgId, created.id + 1000))
        assertTrue(service.deleteWorkflow(orgId, created.id))
        assertNull(service.getWorkflow(orgId, created.id))

        assertFailsWith<IllegalArgumentException> {
            service.createWorkflow(orgId, validRequest(name = " "))
        }
        assertFailsWith<IllegalArgumentException> {
            service.createWorkflow(orgId, validRequest(triggerName = "alert.unknown"))
        }
        assertFailsWith<IllegalArgumentException> {
            service.createWorkflow(
                orgId,
                validRequest(conditions = listOf(WorkflowConditionConfig("alert.missing", "eq", "value")))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createWorkflow(
                orgId,
                validRequest(conditions = listOf(WorkflowConditionConfig("alert.title", "gt", "1")))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createWorkflow(
                orgId,
                validRequest(conditions = listOf(WorkflowConditionConfig("alert.title", "contains")))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.createWorkflow(orgId, validRequest(onceForTemplate = listOf("alert.missing")))
        }
        assertFailsWith<IllegalArgumentException> {
            service.createWorkflow(orgId, validRequest(steps = listOf(WorkflowStepConfig("notification.unknown"))))
        }
        assertFailsWith<IllegalArgumentException> {
            service.createWorkflow(
                orgId,
                validRequest(steps = listOf(WorkflowStepConfig("notification.email_org", mapOf("subject" to "x"))))
            )
        }
    }

    @Test
    fun `executing alert lifecycle workflows sends rich previews`() =
        runBlocking {
            val workflow =
                service.createWorkflow(
                    orgId,
                    CreateWorkflowRequest(
                        name = "Rich alert lifecycle",
                        triggerName = "alert.triggered",
                        steps = listOf(
                            WorkflowStepConfig(
                                name = "notification.slack",
                                params = mapOf(
                                    "format" to "alert_lifecycle",
                                    "message" to "{{alert.description}}"
                                )
                            ),
                            WorkflowStepConfig(
                                name = "notification.discord",
                                params = mapOf(
                                    "format" to "alert_lifecycle",
                                    "title" to "{{alert.title}}",
                                    "message" to "{{alert.description}}"
                                )
                            )
                        ),
                        onceForTemplate = listOf("alert.deduplication_key")
                    )
                )
            publish(workflow.id)

            service.publishAlertTriggered(alertEvent())
            val queuedRun = service.listRuns(orgId, workflow.id).single()

            service.executeRun(queuedRun.id)

            val completedRun = service.listRuns(orgId, workflow.id).single()
            assertEquals("complete", completedRun.status)
            coVerify(exactly = 1) {
                slackService.sendWorkflowAlertMessage(
                    orgId,
                    match { it.title == "P0 CPU saturation" && it.ctaLabel == "View" },
                    true
                )
            }
            coVerify(exactly = 1) {
                discordService.sendWorkflowAlertMessage(
                    orgId,
                    match { preview ->
                        preview.footer == "Host metric alert" &&
                            preview.fields.any { field -> field.label == "Priority" }
                    },
                    true
                )
            }
        }

    @Test
    fun `publishing and executing alert workflows records successful runs`() =
        runBlocking {
            val workflow =
                service.createWorkflow(
                    orgId,
                    CreateWorkflowRequest(
                        name = "Notify critical alerts",
                        triggerName = "alert.triggered",
                        conditions = listOf(
                            WorkflowConditionConfig("alert.title", "is_set"),
                            WorkflowConditionConfig("alert.description", "contains", "cpu"),
                            WorkflowConditionConfig("alert.source", "eq", "HOST_ALERT"),
                            WorkflowConditionConfig("alert.status", "neq", "RESOLVED"),
                            WorkflowConditionConfig("alert.priority", "at_least", "P1"),
                            WorkflowConditionConfig("alert.url", "not_contains", "localhost")
                        ),
                        steps = listOf(emailStep(), slackStep(), discordStep()),
                        onceForTemplate = listOf("alert.deduplication_key")
                    )
                )
            publish(workflow.id)

            service.publishAlertTriggered(alertEvent())
            service.publishAlertTriggered(alertEvent())
            service.publishTrigger(
                com.moneat.workflows.models.WorkflowTriggerEvent(
                    triggerName = "missing.trigger",
                    organizationId = orgId,
                    scope = emptyMap()
                )
            )

            val queuedRun = service.listRuns(orgId, workflow.id).single()
            assertEquals("pending", queuedRun.status)
            assertEquals("alert.deduplication_key=host-1", queuedRun.onceFor)
            val startRequest = workflowEngine.requests.single()
            val temporalIds = persistedTemporalIds(queuedRun.id)
            assertEquals(startRequest.temporalWorkflowId, temporalIds.workflowId)
            assertEquals("temporal-run-${queuedRun.id}", temporalIds.runId)

            service.executeRun(queuedRun.id)
            service.executeRun(queuedRun.id)
            service.executeRun(999_999)

            val completedRun = service.listRuns(orgId, workflow.id).single()
            assertEquals("complete", completedRun.status)
            assertEquals(4, completedRun.progress.size)
            assertTrue(completedRun.progress.all { it.status == "complete" })
            assertNotNull(service.getWorkflow(orgId, workflow.id)?.lastRunAt)
            assertEquals(1L, service.getWorkflow(orgId, workflow.id)?.runCount)
            verify(exactly = 1) {
                emailService.sendEmail(
                    "verified@moneat.io",
                    "Workflow CPU saturation",
                    match { it.contains("&lt;danger&gt;") },
                    match { it.contains("CPU is above 90%") },
                    "workflow"
                )
            }
            verify(exactly = 0) {
                emailService.sendEmail("unverified@moneat.io", any(), any(), any(), any())
            }
            coVerify(exactly = 1) {
                slackService.sendWorkflowMessage(
                    orgId,
                    match { it.contains("CPU saturation") && it.contains("https://moneat.io/hosts/1") },
                    true
                )
            }
            coVerify(exactly = 1) {
                discordService.sendWorkflowMessage(
                    orgId,
                    "CPU saturation",
                    match { it.contains("HOST_ALERT") && it.contains("FIRING") },
                    true
                )
            }

            service.publishAlertTriggered(alertEvent())
            val runsAfterRepeat = service.listRuns(orgId, workflow.id)
            assertEquals(1, runsAfterRepeat.size)
            assertEquals("complete", runsAfterRepeat.single().status)
            assertEquals(1L, service.getWorkflow(orgId, workflow.id)?.runCount)
        }

    @Test
    fun `default alert workflows run initially and once per daily episode reminder`() =
        runBlocking {
            val workflow =
                service.createWorkflow(
                    orgId,
                    CreateWorkflowRequest(
                        name = "Default episode notifications",
                        triggerName = "alert.triggered",
                        steps = emptyList()
                    )
                )
            publish(workflow.id)

            service.publishAlertTriggered(alertEvent())
            service.publishAlertTriggered(alertEvent())

            assertEquals(
                listOf("alert.episode_key=host-1#1|alert.notification_sequence=1"),
                service.listRuns(orgId, workflow.id).map { it.onceFor }
            )

            transaction {
                AlertEpisodes.update(where = { AlertEpisodes.deduplicationKey eq "host-1" }) {
                    it[AlertEpisodes.lastNotificationAt] = Clock.System.now() - 25.hours
                }
            }
            service.publishAlertTriggered(alertEvent())

            assertEquals(
                listOf(
                    "alert.episode_key=host-1#1|alert.notification_sequence=1",
                    "alert.episode_key=host-1#1|alert.notification_sequence=2"
                ),
                service.listRuns(orgId, workflow.id).map { it.onceFor }.sorted()
            )
        }

    @Test
    fun `re-fired alert episode is not blocked by historical workflow runs`() =
        runBlocking {
            val workflow =
                service.createWorkflow(
                    orgId,
                    CreateWorkflowRequest(
                        name = "Episode-scoped alert notifications",
                        triggerName = "alert.triggered",
                        steps = emptyList()
                    )
                )
            publish(workflow.id)

            service.publishAlertTriggered(alertEvent())
            service.publishAlertTriggered(alertEvent().copy(status = AlertStatus.RESOLVED))
            service.publishAlertTriggered(alertEvent())

            assertEquals(
                listOf(
                    "alert.episode_key=host-1#1|alert.notification_sequence=1",
                    "alert.episode_key=host-1#2|alert.notification_sequence=1"
                ),
                service.listRuns(orgId, workflow.id).map { it.onceFor }.sorted()
            )
        }

    @Test
    fun `source specific alert triggers preserve legacy alert workflow behavior`() =
        runBlocking {
            val alertWorkflow =
                service.createWorkflow(
                    orgId,
                    validRequest(
                        name = "Legacy alert trigger",
                        triggerName = "alert.triggered",
                        steps = emptyList()
                    )
                )
            val uptimeWorkflow =
                service.createWorkflow(
                    orgId,
                    validRequest(
                        name = "Uptime down trigger",
                        triggerName = "uptime.down",
                        steps = emptyList()
                    )
                )
            publish(alertWorkflow.id)
            publish(uptimeWorkflow.id)

            service.publishAlertTriggered(
                alertEvent().copy(
                    source = AlertSource.UPTIME_MONITOR,
                    deduplicationKey = "uptime-1"
                )
            )

            assertEquals("alert.triggered", service.listRuns(orgId, alertWorkflow.id).single().triggerName)
            assertEquals("uptime.down", service.listRuns(orgId, uptimeWorkflow.id).single().triggerName)
            assertEquals(listOf("alert.triggered", "uptime.down"), workflowEngine.requests.map { it.triggerName })
        }

    @Test
    fun `security signal trigger supports severity conditions`() =
        runBlocking {
            val workflow =
                service.createWorkflow(
                    orgId,
                    validRequest(
                        name = "Security enrichment",
                        triggerName = "security.signal",
                        conditions = listOf(WorkflowConditionConfig("security.severity", "at_least", "high")),
                        steps = emptyList(),
                        onceForTemplate = listOf("security.rule_id", "security.resource")
                    )
                )
            publish(workflow.id)

            service.publishSecuritySignals(
                orgId,
                listOf(
                    createdSignal("rule-low", "low"),
                    createdSignal("rule-high", "high")
                )
            )

            val run = service.listRuns(orgId, workflow.id).single()
            assertEquals("security.signal", run.triggerName)
            assertEquals("security.rule_id=rule-high|security.resource=/tmp/high", run.onceFor)
        }

    @Test
    fun `security signal trigger fires only for created and escalated signals`() =
        runBlocking {
            val workflow =
                service.createWorkflow(
                    orgId,
                    validRequest(
                        name = "Security all",
                        triggerName = "security.signal",
                        steps = emptyList(),
                        onceForTemplate = listOf("security.rule_id", "security.resource")
                    )
                )
            publish(workflow.id)

            service.publishSecuritySignals(
                orgId,
                listOf(
                    createdSignal("rule-a", "medium"),
                    escalatedSignal("rule-b", "high"),
                    updatedSignal("rule-c", "low")
                )
            )

            // The Updated (repeat-fold) signal must not produce a run; only created/escalated do.
            assertEquals(
                listOf(
                    "security.rule_id=rule-a|security.resource=/tmp/medium",
                    "security.rule_id=rule-b|security.resource=/tmp/high"
                ).sorted(),
                service.listRuns(orgId, workflow.id).map { it.onceFor }.sorted()
            )
        }

    @Test
    fun `API workflow instances and cancellation record Temporal identifiers`() =
        runBlocking {
            val workflow =
                service.createWorkflow(
                    orgId,
                    validRequest(
                        name = "API trigger",
                        triggerName = "api",
                        steps = emptyList(),
                        onceForTemplate = emptyList()
                    )
                )

            val run =
                service.createWorkflowInstance(
                    organizationId = orgId,
                    workflowId = workflow.id,
                    request = WorkflowRunInstanceRequest(
                        scope = mapOf("workflow.input.reason" to JsonPrimitive("operator"))
                    ),
                    callerUserId = 99
                )

            assertNotNull(run)
            assertEquals("api", run.triggerName)
            assertEquals("pending", run.status)
            assertEquals(workflowEngine.requests.single().temporalWorkflowId, run.temporalWorkflowId)
            assertEquals("temporal-run-${run.id}", run.temporalRunId)
            assertNotNull(service.getRun(orgId, workflow.id, run.id))

            val canceled = service.cancelRun(orgId, workflow.id, run.id)

            assertEquals("canceled", canceled?.status)
            assertEquals(listOf(run.temporalWorkflowId), workflowEngine.canceledWorkflowIds)
            assertEquals("canceled", service.getRun(orgId, workflow.id, run.id)?.status)
        }

    @Test
    fun `API workflow instances honor configured conditions`() =
        runBlocking {
            val request = WorkflowRunInstanceRequest(scope = mapOf("service" to JsonPrimitive("checkout")))
            val matching =
                service.createWorkflow(
                    orgId,
                    validRequest(
                        name = "Conditional API trigger",
                        triggerName = "api",
                        conditions = listOf(WorkflowConditionConfig("workflow.input", "contains", "checkout")),
                        steps = emptyList(),
                        onceForTemplate = emptyList()
                    )
                )

            val matched =
                service.createWorkflowInstance(
                    organizationId = orgId,
                    workflowId = matching.id,
                    request = request,
                    callerUserId = 7
                )

            assertNotNull(matched)
            assertEquals(1, workflowEngine.requests.size)

            val mismatch =
                service.createWorkflow(
                    orgId,
                    validRequest(
                        name = "Non-matching API trigger",
                        triggerName = "api",
                        conditions = listOf(WorkflowConditionConfig("workflow.input", "contains", "database")),
                        steps = emptyList(),
                        onceForTemplate = emptyList()
                    )
                )
            val skipped =
                service.createWorkflowInstance(
                    organizationId = orgId,
                    workflowId = mismatch.id,
                    request = request,
                    callerUserId = 7
                )

            assertNull(skipped)
            assertEquals(1, workflowEngine.requests.size)
            assertTrue(service.listRuns(orgId, mismatch.id).isEmpty())
        }

    @Test
    fun `signed webhook trigger requires valid signature and published webhook workflow`() =
        runBlocking {
            val previousSigningKey = System.getProperty("WORKFLOWS_SIGNING_KEY")
            System.setProperty("WORKFLOWS_SIGNING_KEY", "test-workflow-signing-key")
            try {
                val workflow =
                    service.createWorkflow(
                        orgId,
                        validRequest(
                            name = "Webhook trigger",
                            triggerName = "webhook",
                            steps = emptyList(),
                            onceForTemplate = listOf("webhook.event_id")
                        )
                    )
                publish(workflow.id)
                val payload = """{"event":"deploy"}"""
                val signing = service.webhookSigningInfo(orgId, workflow.id)
                val signature = "sha256=${hmacSha256(signing?.signingSecret.orEmpty(), payload)}"

                assertTrue(service.verifyWebhookSignature(workflow.id, payload, signature))
                assertFalse(service.verifyWebhookSignature(workflow.id, payload, "sha256=invalid"))

                val run =
                    service.createWebhookRun(
                        workflowId = workflow.id,
                        payload = payload,
                        eventId = "deploy-1"
                    )

                assertNotNull(run)
                assertEquals("webhook", run.triggerName)
                assertEquals("deploy-1", run.onceFor)
                assertEquals(1, workflowEngine.requests.size)
            } finally {
                if (previousSigningKey == null) {
                    System.clearProperty("WORKFLOWS_SIGNING_KEY")
                } else {
                    System.setProperty("WORKFLOWS_SIGNING_KEY", previousSigningKey)
                }
            }
        }

    @Test
    fun `dashboard alert channel metadata skips disabled notification steps`() =
        runBlocking {
            val workflow =
                service.createWorkflow(
                    orgId,
                    validRequest(
                        name = "Dashboard channel workflow",
                        steps = listOf(emailStep(), slackStep(), discordStep())
                    )
                )
            publish(workflow.id)
            val event =
                alertEvent().copy(
                    source = AlertSource.DASHBOARD_ALERT,
                    metadata = mapOf(
                        "alert.channels.email" to JsonPrimitive(false),
                        "alert.channels.slack" to JsonPrimitive(true),
                        "alert.channels.discord" to JsonPrimitive(false)
                    )
                )

            service.publishAlertTriggered(event)
            val queuedRun = service.listRuns(orgId, workflow.id).single()

            service.executeRun(queuedRun.id)

            val completedRun = service.listRuns(orgId, workflow.id).single()
            assertEquals("complete", completedRun.status)
            assertTrue(completedRun.progress.all { it.status == "complete" })
            verify(exactly = 0) { emailService.sendEmail(any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) {
                slackService.sendWorkflowMessage(
                    orgId,
                    match { it.contains("CPU saturation") },
                    true
                )
            }
            coVerify(exactly = 0) {
                discordService.sendWorkflowMessage(any(), any(), any(), any())
            }
        }

    @Test
    fun `failed notification steps do not block remaining workflow steps`() =
        runBlocking {
            every {
                emailService.sendEmail(any(), any(), any(), any(), any())
            } throws IllegalStateException("smtp down")
            val workflow =
                service.createWorkflow(
                    orgId,
                    validRequest(
                        name = "Parallel notification workflow",
                        steps = listOf(emailStep(), slackStep(), discordStep())
                    )
                )
            publish(workflow.id)

            service.publishAlertTriggered(alertEvent())
            val queuedRun = service.listRuns(orgId, workflow.id).single()

            service.executeRun(queuedRun.id)

            val failedRun = service.listRuns(orgId, workflow.id).single()
            val progressByStep = failedRun.progress.associateBy { it.step }
            assertEquals("failed", failedRun.status)
            assertEquals("smtp down", failedRun.errorMessage)
            assertEquals("failed", progressByStep["notification.email_org"]?.status)
            assertEquals("complete", progressByStep["notification.slack"]?.status)
            assertEquals("complete", progressByStep["notification.discord"]?.status)
            coVerify(exactly = 1) {
                slackService.sendWorkflowMessage(orgId, any(), true)
            }
            coVerify(exactly = 1) {
                discordService.sendWorkflowMessage(orgId, any(), any(), true)
            }
        }

    @Test
    fun `resolved workflows use resolved scope and can fail a step`() =
        runBlocking {
            coEvery { slackService.sendWorkflowMessage(any(), any(), any()) } returns false
            val workflow =
                service.createWorkflow(
                    orgId,
                    CreateWorkflowRequest(
                        name = "Resolved Slack",
                        triggerName = "alert.resolved",
                        conditions = listOf(
                            WorkflowConditionConfig("alert.url", "is_not_set"),
                            WorkflowConditionConfig("alert.status", "eq", "RESOLVED"),
                            WorkflowConditionConfig("alert.source", "eq", "UPTIME_MONITOR")
                        ),
                        steps = listOf(
                            WorkflowStepConfig(
                                name = "notification.slack",
                                params = mapOf(
                                    "message" to "Resolved {{alert.deduplication_key}}",
                                    "skip_if_unconfigured" to "false"
                                )
                            )
                        )
                    )
                )
            publish(workflow.id)

            service.publishAlertTriggered(
                alertEvent().copy(
                    source = AlertSource.UPTIME_MONITOR,
                    deduplicationKey = "uptime-1"
                )
            )
            service.publishAlertResolved(
                organizationId = orgId,
                source = AlertSource.UPTIME_MONITOR.name,
                deduplicationKey = "uptime-1"
            )

            val queuedRun = service.listRuns(orgId, workflow.id).single()
            assertEquals("alert.episode_key=uptime-1#1|alert.status=RESOLVED", queuedRun.onceFor)

            service.executeRun(queuedRun.id)

            val failedRun = service.listRuns(orgId, workflow.id).single()
            val actionProgress = failedRun.progress.filter { it.type == "action" }
            assertEquals("failed", failedRun.status)
            assertEquals("Slack workflow message was not sent", failedRun.errorMessage)
            assertEquals("failed", actionProgress.single().status)
            assertEquals("Slack workflow message was not sent", actionProgress.single().errorMessage)
            coVerify(exactly = 1) {
                slackService.sendWorkflowMessage(orgId, "Resolved uptime-1", false)
            }
        }

    @Test
    fun `alert workflows run once per episode notification and not historical dedup key`() =
        runBlocking {
            val workflow =
                service.createWorkflow(
                    orgId,
                    CreateWorkflowRequest(
                        name = "Lifecycle notifications",
                        triggerName = "alert.triggered",
                        steps = listOf(emailStep()),
                        onceForTemplate = listOf("alert.episode_key", "alert.notification_sequence")
                    )
                )
            publish(workflow.id)

            service.publishAlertTriggered(alertEvent())
            service.publishAlertTriggered(alertEvent())
            assertEquals(listOf("alert.episode_key=host-1#1|alert.notification_sequence=1"), runIdentities(workflow.id))

            transaction {
                AlertEpisodes.update(where = { AlertEpisodes.deduplicationKey eq "host-1" }) {
                    it[AlertEpisodes.lastNotificationAt] = Clock.System.now() - 25.hours
                }
            }
            service.publishAlertTriggered(alertEvent())
            assertEquals(
                listOf(
                    "alert.episode_key=host-1#1|alert.notification_sequence=1",
                    "alert.episode_key=host-1#1|alert.notification_sequence=2"
                ),
                runIdentities(workflow.id)
            )

            service.publishAlertResolved(
                organizationId = orgId,
                source = AlertSource.HOST_ALERT.name,
                deduplicationKey = "host-1"
            )
            service.publishAlertTriggered(alertEvent())

            assertEquals(
                listOf(
                    "alert.episode_key=host-1#1|alert.notification_sequence=1",
                    "alert.episode_key=host-1#1|alert.notification_sequence=2",
                    "alert.episode_key=host-1#2|alert.notification_sequence=1"
                ),
                runIdentities(workflow.id)
            )
        }

    @Test
    fun `incident workflows use incident identity`() =
        runBlocking {
            val createdWorkflow =
                service.createWorkflow(
                    orgId,
                    CreateWorkflowRequest(
                        name = "Incident created",
                        triggerName = "incident.created",
                        steps = emptyList()
                    )
                )
            val resolvedWorkflow =
                service.createWorkflow(
                    orgId,
                    CreateWorkflowRequest(
                        name = "Incident resolved",
                        triggerName = "incident.resolved",
                        steps = emptyList()
                    )
                )
            publish(createdWorkflow.id)
            publish(resolvedWorkflow.id)

            service.publishIncidentCreated(alertEvent())
            service.publishIncidentCreated(alertEvent())

            assertEquals(listOf("incident.id=host-1"), runIdentities(createdWorkflow.id))
            assertEquals(emptyList(), runIdentities(resolvedWorkflow.id))

            service.publishAlertTriggered(alertEvent())
            service.publishIncidentResolved(
                organizationId = orgId,
                source = AlertSource.HOST_ALERT,
                deduplicationKey = "host-1",
                title = "CPU restored"
            )

            assertEquals(
                listOf("incident.id=host-1|incident.status=resolved"),
                runIdentities(resolvedWorkflow.id)
            )
        }

    @Test
    fun `declared incident workflows use declared incident identity`() =
        runBlocking {
            val createdWorkflow =
                service.createWorkflow(
                    orgId,
                    CreateWorkflowRequest(
                        name = "Declared incident created",
                        triggerName = "incident.created",
                        steps = emptyList()
                    )
                )
            val resolvedWorkflow =
                service.createWorkflow(
                    orgId,
                    CreateWorkflowRequest(
                        name = "Declared incident resolved",
                        triggerName = "incident.resolved",
                        steps = emptyList()
                    )
                )
            publish(createdWorkflow.id)
            publish(resolvedWorkflow.id)

            service.publishDeclaredIncidentCreated(
                organizationId = orgId,
                incidentId = 42,
                title = "Checkout degraded",
                severity = IncidentSeverity.SEV1
            )
            service.publishDeclaredIncidentCreated(
                organizationId = orgId,
                incidentId = 42,
                title = "Checkout degraded",
                severity = IncidentSeverity.SEV1
            )

            assertEquals(listOf("incident.id=42"), runIdentities(createdWorkflow.id))
            assertEquals(emptyList(), runIdentities(resolvedWorkflow.id))

            service.publishDeclaredIncidentResolved(
                organizationId = orgId,
                incidentId = 42,
                title = "Checkout degraded",
                severity = IncidentSeverity.SEV1
            )

            assertEquals(
                listOf("incident.id=42|incident.status=resolved"),
                runIdentities(resolvedWorkflow.id)
            )
        }

    @Test
    fun `publishAlertResolved ignores unknown alert source`() =
        runBlocking {
            val workflow =
                service.createWorkflow(
                    orgId,
                    CreateWorkflowRequest(
                        name = "Resolved alerts",
                        triggerName = "alert.resolved",
                        steps = emptyList()
                    )
                )
            publish(workflow.id)

            service.publishAlertResolved(
                organizationId = orgId,
                source = "UNKNOWN_SOURCE",
                deduplicationKey = "host-1"
            )

            assertEquals(emptyList(), runIdentities(workflow.id))
        }

    @Test
    fun `non matching and disabled workflows do not create runs`() =
        runBlocking {
            val disabled =
                service.createWorkflow(
                    orgId,
                    validRequest(
                        name = "Disabled",
                        enabled = false,
                        conditions = listOf(WorkflowConditionConfig("alert.status", "eq", "FIRING"))
                    )
                )
            val nonMatching =
                service.createWorkflow(
                    orgId,
                    validRequest(
                        name = "Non matching",
                        conditions = listOf(WorkflowConditionConfig("alert.description", "not_contains", "cpu"))
                    )
                )
            publish(disabled.id)
            publish(nonMatching.id)

            service.publishAlertTriggered(alertEvent())

            assertEquals(emptyList(), service.listRuns(orgId, disabled.id))
            assertEquals(emptyList(), service.listRuns(orgId, nonMatching.id))
        }

    @Test
    fun `publish marks run failed when Temporal start fails`() =
        runBlocking {
            workflowEngine.error = IllegalStateException("temporal unavailable")
            val workflow =
                service.createWorkflow(
                    orgId,
                    validRequest(name = "Temporal unavailable workflow")
                )
            publish(workflow.id)

            service.publishAlertTriggered(alertEvent())

            val run = service.listRuns(orgId, workflow.id).single()
            assertEquals("failed", run.status)
            assertTrue(run.errorMessage?.contains("temporal unavailable") == true)
        }

    private fun seedOrganizationWithMembers(): Int {
        val organizationId = seedOrganization("Workflow Org")
        val verifiedUserId = seedUser("verified@moneat.io", verified = true)
        val unverifiedUserId = seedUser("unverified@moneat.io", verified = false)
        transaction {
            Memberships.insert {
                it[user_id] = verifiedUserId
                it[organization_id] = organizationId
                it[role] = "owner"
            }
            Memberships.insert {
                it[user_id] = unverifiedUserId
                it[organization_id] = organizationId
                it[role] = "member"
            }
        }
        return organizationId
    }

    private fun seedOrganization(name: String): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedUser(
        email: String,
        verified: Boolean
    ): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
                it[name] = email.substringBefore("@")
                it[email_verified] = verified
            } get Users.id
        }

    private fun validRequest(
        name: String = "Workflow",
        triggerName: String = "alert.triggered",
        enabled: Boolean = true,
        conditions: List<WorkflowConditionConfig> = emptyList(),
        steps: List<WorkflowStepConfig> = listOf(emailStep()),
        onceForTemplate: List<String> = listOf("alert.deduplication_key")
    ): CreateWorkflowRequest =
        CreateWorkflowRequest(
            name = name,
            triggerName = triggerName,
            enabled = enabled,
            conditions = conditions,
            steps = steps,
            onceForTemplate = onceForTemplate
        )

    private fun publish(workflowId: Int) {
        assertNotNull(service.publishWorkflow(orgId, workflowId))
    }

    private fun runIdentities(workflowId: Int): List<String> =
        service.listRuns(orgId, workflowId).map { it.onceFor }.sorted()

    private fun emailStep(): WorkflowStepConfig =
        WorkflowStepConfig(
            name = "notification.email_org",
            params = mapOf(
                "subject" to "Workflow {{alert.title}}",
                "body" to "{{alert.description}}\n{{alert.source}}\n{{alert.status}}\n<danger>"
            )
        )

    private fun slackStep(): WorkflowStepConfig =
        WorkflowStepConfig(
            name = "notification.slack",
            params = mapOf(
                "message" to "Slack {{alert.title}} {{alert.url}}",
                "skip_if_unconfigured" to "true"
            )
        )

    private fun discordStep(): WorkflowStepConfig =
        WorkflowStepConfig(
            name = "notification.discord",
            params = mapOf(
                "title" to "{{alert.title}}",
                "message" to "Discord {{alert.source}} {{alert.status}}",
                "skip_if_unconfigured" to "true"
            )
        )

    private fun alertEvent(): AlertLifecycleEvent =
        AlertLifecycleEvent(
            title = "CPU saturation",
            description = "CPU is above 90%",
            priority = AlertPriority.P0,
            status = AlertStatus.FIRING,
            source = AlertSource.HOST_ALERT,
            deduplicationKey = "host-1",
            organizationId = orgId,
            moneatUrl = "https://moneat.io/hosts/1"
        )

    private fun createdSignal(
        ruleId: String,
        severity: String,
        resource: String = "/tmp/$severity"
    ): SignalOutcome.Created =
        SignalOutcome.Created(
            signalId = ruleId.hashCode(),
            organizationId = orgId,
            source = SignalSource.AGENT_RUNTIME,
            ruleId = ruleId,
            ruleName = "Rule $ruleId",
            severity = SignalSeverity.fromWire(severity),
            dedupKey = "$ruleId|host|proc",
            entities = mapOf("resource" to resource)
        )

    private fun escalatedSignal(
        ruleId: String,
        severity: String,
        resource: String = "/tmp/$severity"
    ): SignalOutcome.Escalated =
        SignalOutcome.Escalated(
            signalId = ruleId.hashCode(),
            organizationId = orgId,
            source = SignalSource.AGENT_RUNTIME,
            ruleId = ruleId,
            ruleName = "Rule $ruleId",
            severity = SignalSeverity.fromWire(severity),
            dedupKey = "$ruleId|host|proc",
            entities = mapOf("resource" to resource)
        )

    private fun updatedSignal(
        ruleId: String,
        severity: String,
        resource: String = "/tmp/$severity"
    ): SignalOutcome.Updated =
        SignalOutcome.Updated(
            signalId = ruleId.hashCode(),
            organizationId = orgId,
            source = SignalSource.AGENT_RUNTIME,
            ruleId = ruleId,
            ruleName = "Rule $ruleId",
            severity = SignalSeverity.fromWire(severity),
            dedupKey = "$ruleId|host|proc",
            entities = mapOf("resource" to resource)
        )

    private fun persistedTemporalIds(runId: Int): PersistedTemporalIds =
        transaction {
            val run = WorkflowRuns.selectAll().where { WorkflowRuns.id eq runId }.single()
            PersistedTemporalIds(
                workflowId = run[WorkflowRuns.temporalWorkflowId],
                runId = run[WorkflowRuns.temporalRunId]
            )
        }
}

private fun hmacSha256(
    key: String,
    value: String
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.encodeToByteArray(), "HmacSHA256"))
    return mac.doFinal(value.encodeToByteArray()).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private data class PersistedTemporalIds(
    val workflowId: String?,
    val runId: String?
)

private class FakeWorkflowExecutionEngine : WorkflowExecutionEngine {
    val requests = mutableListOf<WorkflowStartRequest>()
    val canceledWorkflowIds = mutableListOf<String?>()
    var error: Throwable? = null

    override suspend fun start(request: WorkflowStartRequest): WorkflowStartResult {
        error?.let { throw it }
        requests += request
        return WorkflowStartResult(
            temporalWorkflowId = request.temporalWorkflowId,
            temporalRunId = "temporal-run-${request.runId}"
        )
    }

    override suspend fun cancel(temporalWorkflowId: String) {
        canceledWorkflowIds += temporalWorkflowId
    }
}
