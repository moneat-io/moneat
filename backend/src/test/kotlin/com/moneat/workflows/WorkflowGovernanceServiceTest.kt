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
import com.moneat.alerts.models.AlertSeverity
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.workflows.engine.temporal.PersistRunActivityImpl
import com.moneat.workflows.engine.temporal.PersistRunProgressInput
import com.moneat.workflows.engine.temporal.WorkflowExecutionEngine
import com.moneat.workflows.engine.temporal.WorkflowStartRequest
import com.moneat.workflows.engine.temporal.WorkflowStartResult
import com.moneat.workflows.models.WorkflowAuditEvents
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowImportRequest
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.WorkflowRunSteps
import com.moneat.workflows.models.WorkflowUsageEvents
import com.moneat.workflows.models.WorkflowVersions
import com.moneat.workflows.models.Workflows
import com.moneat.workflows.services.WorkflowAudit
import com.moneat.workflows.services.WorkflowBlueprintCatalog
import com.moneat.workflows.services.WorkflowExport
import com.moneat.workflows.services.WorkflowGovernanceService
import com.moneat.workflows.services.WorkflowRateLimiter
import com.moneat.workflows.services.WorkflowService
import com.moneat.workflows.services.WorkflowUsage
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class WorkflowGovernanceServiceTest {
    companion object {
        private var db: Database? = null
        private const val QUOTA_ENV = "WORKFLOWS_MONTHLY_EXECUTION_LIMIT"
        private const val MIN_BLUEPRINTS = 15
        private const val MAX_AUDIT = 500
        private const val OUTCOME_COMPLETED = "completed"
        private const val OUTCOME_REFUSED = "refused"
    }

    private val emailService = mockk<EmailService>(relaxed = true)
    private val slackService = mockk<SlackService>()
    private val discordService = mockk<DiscordService>()
    private lateinit var workflowEngine: FakeGovernanceEngine
    private lateinit var service: WorkflowService
    private lateinit var governance: WorkflowGovernanceService
    private val runPersistence = PersistRunActivityImpl()
    private var orgId: Int = 0
    private var previousQuota: String? = null
    private lateinit var usagePeriod: String

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_workflow_governance;MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        previousQuota = System.getProperty(QUOTA_ENV)
        System.clearProperty(QUOTA_ENV)
        resetSchema()
        workflowEngine = FakeGovernanceEngine()
        clearMocks(emailService, slackService, discordService)
        every { emailService.sendEmail(any(), any(), any(), any(), any()) } just runs
        coEvery { slackService.sendWorkflowMessage(any(), any(), any()) } returns true
        coEvery { slackService.sendWorkflowAlertMessage(any(), any(), any()) } returns true
        coEvery { discordService.sendWorkflowMessage(any(), any(), any(), any()) } returns true
        coEvery { discordService.sendWorkflowAlertMessage(any(), any(), any()) } returns true
        service = WorkflowService(emailService, slackService, discordService, executionEngine = workflowEngine)
        governance = WorkflowGovernanceService(service)
        orgId = seedOrganizationWithMember()
        usagePeriod = WorkflowUsage.period(Clock.System.now())
    }

    @AfterTest
    fun tearDown() {
        if (previousQuota == null) {
            System.clearProperty(QUOTA_ENV)
        } else {
            System.setProperty(QUOTA_ENV, previousQuota!!)
        }
    }

    @Test
    fun `every blueprint instantiates to a valid persisted draft workflow`() {
        val keys = WorkflowBlueprintCatalog.list().map { it.key }
        assertTrue(keys.size >= MIN_BLUEPRINTS, "expected at least $MIN_BLUEPRINTS blueprints, got ${keys.size}")
        assertEquals(keys.size, keys.toSet().size, "blueprint keys must be unique")

        keys.forEach { key ->
            val blueprint = assertNotNull(WorkflowBlueprintCatalog.get(key))
            val detail = WorkflowBlueprintCatalog.detail(blueprint)
            assertTrue(detail.graph.nodes.isNotEmpty(), "blueprint $key produced an empty graph")

            val workflow = governance.instantiateBlueprint(orgId, key, name = null, actorUserId = 1)
            assertEquals(blueprint.name, workflow.name)
            assertEquals(blueprint.triggerName, workflow.triggerName)
            assertFalse(workflow.enabled, "blueprint $key should instantiate as a disabled draft")
            assertEquals(1, workflow.version)
            assertEquals(workflow.id, service.getWorkflow(orgId, workflow.id)?.id)
        }

        val instantiatedAudits =
            governance.listAudit(orgId, workflowId = null, limit = MAX_AUDIT)
                .filter { it.action == WorkflowAudit.ACTION_INSTANTIATED_BLUEPRINT }
        assertEquals(keys.size, instantiatedAudits.size)
        assertTrue(instantiatedAudits.all { it.detail["blueprint"] in keys })
    }

    @Test
    fun `blueprint list and get expose summaries and detail with missing key handling`() {
        val summaries = WorkflowBlueprintCatalog.list()
        assertTrue(summaries.any { it.category == "incident" })
        assertTrue(summaries.any { it.category == "security" })
        assertNull(WorkflowBlueprintCatalog.get("does-not-exist"))
        assertFailsWith<IllegalArgumentException> {
            governance.instantiateBlueprint(orgId, "does-not-exist", name = null, actorUserId = 1)
        }

        val custom = governance.instantiateBlueprint(orgId, summaries.first().key, name = "My copy", actorUserId = 7)
        assertEquals("My copy", custom.name)
    }

    @Test
    fun `lifecycle actions record audit events`() {
        val workflow = service.createWorkflow(orgId, validRequest(name = "Audited workflow"))
        service.updateWorkflow(
            orgId,
            workflow.id,
            com.moneat.workflows.models.UpdateWorkflowRequest(name = "Audited renamed")
        )
        service.publishWorkflow(orgId, workflow.id)
        service.unpublishWorkflow(orgId, workflow.id)

        val actions = governance.listAudit(orgId, workflow.id, MAX_AUDIT).map { it.action }.toSet()
        assertTrue(actions.containsAll(setOf("created", "updated", "published", "unpublished")))

        assertTrue(service.deleteWorkflow(orgId, workflow.id))
        val deleted =
            governance.listAudit(orgId, workflowId = null, limit = MAX_AUDIT)
                .firstOrNull { it.action == "deleted" }
        assertNotNull(deleted)
        assertEquals(workflow.id.toString(), deleted.detail["workflow_id"])
    }

    @Test
    fun `run lifecycle records started and completed audit and usage`() =
        runBlocking {
            val workflow = service.createWorkflow(orgId, validRequest(name = "Lifecycle"))
            service.publishWorkflow(orgId, workflow.id)
            service.publishAlertTriggered(alertEvent())
            val run = service.listRuns(orgId, workflow.id).single()

            service.executeRun(run.id)

            val actions = governance.listAudit(orgId, workflow.id, MAX_AUDIT).map { it.action }
            assertTrue(actions.contains("run_started"))
            assertTrue(actions.contains("run_completed"))

            assertEquals(1L, WorkflowUsage.completedCount(orgId, usagePeriod))
        }

    @Test
    fun `recordCompleted is idempotent across repeated terminal transitions`() {
        val runId = seedCompletedRun()

        assertTrue(WorkflowUsage.recordCompleted(runId))
        assertFalse(WorkflowUsage.recordCompleted(runId))

        runPersistence.markComplete(PersistRunProgressInput(runId = runId, status = "complete"))
        runPersistence.markComplete(PersistRunProgressInput(runId = runId, status = "complete"))

        assertEquals(1L, WorkflowUsage.completedCount(orgId, usagePeriod))
        assertEquals(
            1L,
            transaction {
                WorkflowUsageEvents
                    .selectAll()
                    .where { (WorkflowUsageEvents.runId eq runId) and (WorkflowUsageEvents.outcome eq "completed") }
                    .count()
            }
        )
    }

    @Test
    fun `over quota refuses run records refused usage event and under quota allows`() =
        runBlocking {
            val workflow = service.createWorkflow(orgId, validRequest(name = "Quota", steps = emptyList()))
            service.publishWorkflow(orgId, workflow.id)

            // Two completed executions already counted this period.
            seedUsage(workflow.id, OUTCOME_COMPLETED, "run-a")
            seedUsage(workflow.id, OUTCOME_COMPLETED, "run-b")
            System.setProperty(QUOTA_ENV, "2")

            val refusedRun = service.runWorkflow(orgId, workflow.id)
            assertNull(refusedRun, "run should be refused when over quota")
            assertTrue(service.listRuns(orgId, workflow.id).isEmpty())

            val refusedAudit =
                governance.listAudit(orgId, workflow.id, MAX_AUDIT).firstOrNull { it.action == "run_refused" }
            assertNotNull(refusedAudit)
            assertEquals(
                1L,
                transaction {
                    WorkflowUsageEvents
                        .selectAll()
                        .where { WorkflowUsageEvents.outcome eq OUTCOME_REFUSED }
                        .count()
                }
            )

            // Raising the ceiling lets the next run through.
            System.setProperty(QUOTA_ENV, "5")
            val allowed = service.runWorkflow(orgId, workflow.id)
            assertNotNull(allowed)
            assertEquals(1, service.listRuns(orgId, workflow.id).size)
        }

    @Test
    fun `rate limited workflows skip run creation`() =
        runBlocking {
            val graph = rateLimitedGraph(count = 1, intervalSeconds = 3600)
            val workflow =
                service.createWorkflow(
                    orgId,
                    com.moneat.workflows.models.CreateWorkflowRequest(
                        name = "Rate limited",
                        triggerName = "alert.triggered",
                        graph = graph,
                        onceForTemplate = listOf("alert.deduplication_key")
                    )
                )
            service.publishWorkflow(orgId, workflow.id)

            service.publishAlertTriggered(alertEvent().copy(deduplicationKey = "rate-1"))
            assertEquals(1, service.listRuns(orgId, workflow.id).size)

            // Second distinct event would create a run, but the per-workflow limit blocks it.
            service.publishAlertTriggered(alertEvent().copy(deduplicationKey = "rate-2"))
            assertEquals(1, service.listRuns(orgId, workflow.id).size)

            assertTrue(WorkflowRateLimiter.isLimited(workflow.id, graph))
        }

    @Test
    fun `export and import round trip is stable and idempotent`() {
        val workflow =
            service.createWorkflow(
                orgId,
                validRequest(name = "Exportable", steps = listOf(slackStep()))
            )

        val export = governance.export(orgId, workflow.id, actorUserId = 1)
        assertNotNull(export)
        assertEquals(WorkflowExport.WORKFLOW_SCHEMA_VERSION, export.schemaVersion)
        assertTrue(export.terraform.contains("""resource "moneat_workflow" "exportable""""))
        assertTrue(export.terraform.contains("jsonencode("))

        val importRequest =
            WorkflowImportRequest(
                name = export.resource.name,
                triggerName = export.resource.triggerName,
                graph = export.resource.graph,
                enabled = export.resource.enabled,
                onceForTemplate = export.resource.onceForTemplate
            )

        val firstImport = governance.import(orgId, importRequest, actorUserId = 1)
        assertEquals(workflow.id, firstImport.id, "import should upsert the existing workflow by name")
        assertEquals(workflow.version, firstImport.version, "identical import must not bump the version")

        val secondImport = governance.import(orgId, importRequest, actorUserId = 1)
        assertEquals(workflow.version, secondImport.version, "repeat import must remain a no-op")
        assertEquals(1, service.listWorkflows(orgId).size, "import must not create a duplicate workflow")

        val reExport = governance.export(orgId, firstImport.id, actorUserId = 1)
        assertEquals(export.resource, reExport?.resource, "round-trip resource must be identical")
    }

    @Test
    fun `import updates the existing workflow when the graph changes`() {
        val workflow = service.createWorkflow(orgId, validRequest(name = "Changeable", steps = listOf(slackStep())))
        val changedGraph =
            WorkflowExport.toExport(
                service.createWorkflow(orgId, validRequest(name = "Source", steps = listOf(emailStep())))
            ).resource.graph

        val updated =
            governance.import(
                orgId,
                WorkflowImportRequest(
                    name = "Changeable",
                    triggerName = workflow.triggerName,
                    graph = changedGraph,
                    enabled = true
                ),
                actorUserId = 1
            )

        assertEquals(workflow.id, updated.id)
        assertEquals(workflow.version + 1, updated.version)
        assertTrue(updated.enabled)
    }

    @Test
    fun `usage summary reflects unlimited and bounded quotas`() {
        val unlimited = governance.usage(orgId)
        assertTrue(unlimited.unlimited)
        assertNull(unlimited.limit)
        assertNull(unlimited.remaining)

        System.setProperty(QUOTA_ENV, "10")
        seedUsage(workflowId = null, outcome = OUTCOME_COMPLETED, runKey = "u-1")
        seedUsage(workflowId = null, outcome = OUTCOME_COMPLETED, runKey = "u-2")
        seedUsage(workflowId = null, outcome = OUTCOME_REFUSED, runKey = "u-3")

        val bounded = governance.usage(orgId)
        assertFalse(bounded.unlimited)
        assertEquals(10, bounded.limit)
        assertEquals(2L, bounded.used)
        assertEquals(8, bounded.remaining)
    }

    @Test
    fun `overview summarizes workflow counts runs and success rate`() =
        runBlocking {
            val workflow = service.createWorkflow(orgId, validRequest(name = "Overview", steps = listOf(slackStep())))
            service.publishWorkflow(orgId, workflow.id)
            service.publishAlertTriggered(alertEvent())
            val run = service.listRuns(orgId, workflow.id).single()
            service.executeRun(run.id)

            val overview = governance.overview(orgId)
            assertEquals(1L, overview.totalWorkflows)
            assertEquals(1L, overview.publishedWorkflows)
            assertEquals(1L, overview.runsLast30d)
            assertEquals(0L, overview.failedLast30d)
            assertEquals(1.0, overview.successRate)
            assertEquals(workflow.id, overview.topWorkflows.single().workflowId)
        }

    private fun rateLimitedGraph(
        count: Int,
        intervalSeconds: Int
    ): WorkflowGraphConfig {
        val base = WorkflowBlueprintCatalog.get("alert_notify_slack")!!.graph()
        val triggerNode = base.nodes.first { it.type == "trigger" }
        val withLimits =
            triggerNode.copy(
                params = mapOf(
                    WorkflowRateLimiter.RATE_LIMIT_COUNT_PARAM to JsonPrimitive(count.toString()),
                    WorkflowRateLimiter.RATE_LIMIT_INTERVAL_SECONDS_PARAM to JsonPrimitive(intervalSeconds.toString())
                )
            )
        return base.copy(nodes = listOf(withLimits) + base.nodes.filterNot { it === triggerNode })
    }

    private fun seedUsage(
        workflowId: Int?,
        outcome: String,
        runKey: String
    ) {
        transaction {
            WorkflowUsageEvents.insert {
                it[WorkflowUsageEvents.organizationId] = orgId
                it[WorkflowUsageEvents.workflowId] = workflowId
                it[WorkflowUsageEvents.runId] = runKey.hashCode()
                it[WorkflowUsageEvents.period] = usagePeriod
                it[WorkflowUsageEvents.outcome] = outcome
                it[createdAt] = Clock.System.now()
            }
        }
    }

    private fun seedCompletedRun(): Int {
        val workflow = service.createWorkflow(orgId, validRequest(name = "Completed run host", steps = emptyList()))
        val versionId =
            transaction {
                WorkflowVersions
                    .selectAll()
                    .where { WorkflowVersions.workflowId eq workflow.id }
                    .first()[WorkflowVersions.id]
                    .value
            }
        return transaction {
            WorkflowRuns.insert {
                it[WorkflowRuns.workflowId] = workflow.id
                it[workflowVersionId] = versionId
                it[organizationId] = orgId
                it[triggerName] = "alert.triggered"
                it[onceFor] = "completed-${System.nanoTime()}"
                it[scope] = "{}"
                it[status] = "running"
                it[progress] = "[]"
                it[createdAt] = Clock.System.now()
            } get WorkflowRuns.id
        }.value
    }

    private fun seedOrganizationWithMember(): Int {
        val organizationId =
            transaction {
                Organizations.insert {
                    it[name] = "Governance Org"
                    it[slug] = "governance-org"
                } get Organizations.id
            }
        val userId =
            transaction {
                Users.insert {
                    it[email] = "owner@moneat.io"
                    it[password_hash] = "hash"
                    it[name] = "owner"
                    it[email_verified] = true
                } get Users.id
            }
        transaction {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = organizationId
                it[role] = "owner"
            }
        }
        return organizationId
    }

    private fun validRequest(
        name: String = "Workflow",
        steps: List<com.moneat.workflows.models.WorkflowStepConfig> = listOf(emailStep())
    ): com.moneat.workflows.models.CreateWorkflowRequest =
        com.moneat.workflows.models.CreateWorkflowRequest(
            name = name,
            triggerName = "alert.triggered",
            steps = steps,
            onceForTemplate = listOf("alert.deduplication_key")
        )

    private fun emailStep(): com.moneat.workflows.models.WorkflowStepConfig =
        com.moneat.workflows.models.WorkflowStepConfig(
            name = "notification.email_org",
            params = mapOf("subject" to "S {{alert.title}}", "body" to "B {{alert.description}}")
        )

    private fun slackStep(): com.moneat.workflows.models.WorkflowStepConfig =
        com.moneat.workflows.models.WorkflowStepConfig(
            name = "notification.slack",
            params = mapOf("message" to "M {{alert.title}}", "skip_if_unconfigured" to "true")
        )

    private fun alertEvent(): AlertLifecycleEvent =
        AlertLifecycleEvent(
            title = "CPU saturation",
            description = "CPU is above 90%",
            severity = AlertSeverity.CRITICAL,
            status = AlertStatus.FIRING,
            source = AlertSource.HOST_ALERT,
            deduplicationKey = "host-1",
            organizationId = orgId,
            moneatUrl = "https://moneat.io/hosts/1"
        )

    private fun resetSchema() {
        TestDatabaseHelper.dropAndPatchJsonb(
            Users,
            Organizations,
            Memberships,
            Workflows,
            WorkflowVersions,
            WorkflowRuns,
            WorkflowRunSteps,
            WorkflowAuditEvents,
            WorkflowUsageEvents
        )
        transaction {
            // Drop child-to-parent so foreign keys never block a re-create between tests.
            listOf(
                "workflow_run_steps",
                "workflow_runs",
                "workflow_versions",
                "workflow_audit_events",
                "workflow_usage_events"
            ).forEach { table -> exec("DROP TABLE IF EXISTS $table") }
            SchemaUtils.create(Users, Organizations, Memberships, Workflows)
            createWorkflowVersionTable()
            createWorkflowRunTable()
            createWorkflowRunStepTable()
            createGovernanceTables()
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.createGovernanceTables() {
        // Manual DDL: H2 cannot parse Exposed's generated JSONB `DEFAULT {}` clause.
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
                CONSTRAINT fk_governance_audit_org
                    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
                CONSTRAINT fk_governance_audit_workflow
                    FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        exec(
            "CREATE INDEX idx_governance_audit_org_created ON workflow_audit_events (organization_id, created_at DESC)"
        )
        exec(
            """
            CREATE TABLE workflow_usage_events (
                id INT AUTO_INCREMENT PRIMARY KEY,
                organization_id INT NOT NULL,
                workflow_id INT,
                run_id INT,
                period VARCHAR(7) NOT NULL,
                outcome VARCHAR(16) NOT NULL,
                created_at TIMESTAMP NOT NULL,
                CONSTRAINT fk_governance_usage_org
                    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
                CONSTRAINT uq_governance_usage_run_outcome UNIQUE (run_id, outcome)
            )
            """.trimIndent()
        )
        exec("CREATE INDEX idx_governance_usage_org_period ON workflow_usage_events (organization_id, period)")
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.createWorkflowVersionTable() {
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
                CONSTRAINT fk_governance_versions_workflow_id
                    FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        exec("CREATE UNIQUE INDEX idx_governance_versions_version ON workflow_versions (workflow_id, version)")
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.createWorkflowRunTable() {
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
                CONSTRAINT fk_governance_runs_workflow_id
                    FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE,
                CONSTRAINT fk_governance_runs_version_id
                    FOREIGN KEY (workflow_version_id) REFERENCES workflow_versions(id),
                CONSTRAINT fk_governance_runs_organization_id
                    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        exec(
            "CREATE UNIQUE INDEX idx_governance_runs_idempotency ON workflow_runs (workflow_id, active_once_for)"
        )
        exec("CREATE INDEX idx_governance_runs_created ON workflow_runs (workflow_id, created_at DESC)")
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.createWorkflowRunStepTable() {
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
                CONSTRAINT fk_governance_steps_run_id
                    FOREIGN KEY (run_id) REFERENCES workflow_runs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        exec(
            "CREATE UNIQUE INDEX idx_governance_steps_attempt ON workflow_run_steps (run_id, node_id, attempt)"
        )
    }
}

private class FakeGovernanceEngine : WorkflowExecutionEngine {
    val requests = mutableListOf<WorkflowStartRequest>()

    override suspend fun start(request: WorkflowStartRequest): WorkflowStartResult {
        requests += request
        return WorkflowStartResult(
            temporalWorkflowId = request.temporalWorkflowId,
            temporalRunId = "temporal-run-${request.runId}"
        )
    }

    override suspend fun cancel(temporalWorkflowId: String) = Unit
}
