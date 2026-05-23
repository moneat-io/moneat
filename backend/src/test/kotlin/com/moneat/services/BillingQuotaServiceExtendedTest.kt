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

import com.moneat.billing.models.OrgUsageCounters
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.billing.services.BillingQuotaService
import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import com.moneat.shared.models.OnCallParticipants
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Additional coverage for [com.moneat.billing.services.BillingQuotaService] focusing on
 * [com.moneat.billing.services.BillingQuotaService.refundUnits] and
 * [com.moneat.billing.services.BillingQuotaService.isEnforcementEnabled].
 */
class BillingQuotaServiceExtendedTest {

    companion object {
        private var db: Database? = null
    }

    private lateinit var service: BillingQuotaService
    private var testOrgId: Int = 0
    private var testTierId: Int = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db =
                Database.connect(
                    url = "jdbc:h2:mem:moneat_billing_quota_ext;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Subscriptions,
            OrgUsageCounters,
            PricingTierConfigs,
            Projects,
            OnCallSchedules,
            OnCallParticipants,
        )
        service = BillingQuotaService()
        transaction {
            testOrgId =
                Organizations.insert {
                    it[name] = "Quota Ext Org"
                    it[slug] = "quota-ext-org"
                } get Organizations.id
            testTierId =
                PricingTierConfigs.insert {
                    it[tier_name] = "PRO"
                    it[version] = 1
                    it[monthly_unit_limit] = 1000
                    it[monthly_error_limit] = 500
                    it[monthly_transaction_limit] = 300
                    it[monthly_replay_limit] = 100
                    it[monthly_feedback_limit] = 100
                    it[monthly_gb_limit] = 10
                    it[retention_days] = 30
                    it[log_retention_days] = 30
                    it[status_pages_enabled] = true
                    it[status_page_custom_domain_enabled] = true
                    it[session_replay_enabled] = true
                    it[slack_enabled] = false
                    it[incident_io_enabled] = false
                    it[saml_enabled] = false
                    it[oidc_enabled] = false
                    it[priority_support_enabled] = false
                    it[sla_enabled] = false
                    it[custom_retention_enabled] = false
                    it[max_projects] = null
                    it[max_systems] = 5
                    it[monitor_interval_seconds] = 60
                    it[monthly_price_cents] = 2900
                    it[yearly_price_cents] = 28800
                    it[trial_days] = 14
                    it[payg_enabled] = true
                    it[payg_rate_micros_per_unit] = 400_000L
                    it[overage_rate_cents_per_gb] = 40
                    it[error_overage_rate_cents_per_1k] = 10
                    it[replay_overage_rate_cents_per_gb] = 40
                    it[llm_overage_rate_cents_per_1k] = 100
                    it[monthly_apm_span_limit] = 10_000_000L
                    it[apm_span_overage_rate_cents_per_1m] = 30
                    it[monthly_custom_metric_limit] = 1_000_000L
                    it[custom_metric_overage_rate_cents_per_100k] = 50
                    it[is_current] = true
                    it[profiling_enabled] = true
                    it[network_monitoring_enabled] = true
                    it[dbm_enabled] = true
                    it[debugger_enabled] = true
                    it[k8s_monitoring_enabled] = true
                    it[data_streams_enabled] = true
                    it[sbom_enabled] = true
                    it[synthetics_enabled] = true
                } get PricingTierConfigs.id
            val billingAnchor = Clock.System.now()
            Subscriptions.insert {
                it[organization_id] = testOrgId
                it[plan] = "PRO"
                it[status] = "active"
                it[billing_interval] = "monthly"
                it[current_period_start] = billingAnchor
                it[current_period_end] = billingAnchor + 30.days
                it[pricing_tier_config_id] = testTierId
                it[payg_budget_cents] = 10_000
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
                it[pending_meter_batch_id] = null
                it[pending_meter_batch_units] = 0
            }
            val periodStart = billingAnchor.toLocalDateTime(TimeZone.UTC).date
            val periodEnd = periodStart.plus(DatePeriod(months = 1, days = -1))
            OrgUsageCounters.insert {
                it[organization_id] = testOrgId
                it[period_start] = periodStart
                it[period_end] = periodEnd
                it[used_units] = 200
                it[used_errors] = 200
                it[used_transactions] = 0
                it[used_replays] = 0
                it[used_feedback] = 0
                it[used_bytes] = 0
                it[updated_at] = billingAnchor
            }
        }
    }

    private fun jsonRow(vararg fields: String): String = fields.joinToString(
        separator = ",",
        prefix = "{",
        postfix = "}"
    )

    @Test
    fun `refundUnits decrements error counters after reservation`() {
        val before = service.getUsageForOrganization(testOrgId)
        assertEquals(200, before.usedErrors)

        service.refundUnits(organizationId = testOrgId, units = 50, eventType = "error")

        val after = service.getUsageForOrganization(testOrgId)
        assertEquals(150, after.usedErrors)
    }

    @Test
    fun `refundUnits with zero units is a no-op`() {
        val before = service.getUsageForOrganization(testOrgId)
        service.refundUnits(testOrgId, units = 0, eventType = "error")
        val after = service.getUsageForOrganization(testOrgId)
        assertEquals(before.usedErrors, after.usedErrors)
    }

    @Test
    fun `incrementUsageCounters creates current period row before incrementing visibility counters`() {
        transaction {
            OrgUsageCounters.deleteWhere { organization_id eq testOrgId }
        }

        service.incrementUsageCounters(
            organizationId = testOrgId,
            syntheticRuns = 2,
            uptimeChecks = 3,
            aiTokens = 100
        )

        val row = transaction {
            OrgUsageCounters
                .selectAll()
                .where { OrgUsageCounters.organization_id eq testOrgId }
                .first()
        }

        assertEquals(2L, row[OrgUsageCounters.used_synthetic_runs])
        assertEquals(3L, row[OrgUsageCounters.used_uptime_checks])
        assertEquals(100L, row[OrgUsageCounters.used_ai_tokens])
    }

    @Test
    fun `getApmSpanUsageDebug groups span rows and enriches project labels`() = runBlocking {
        val projectId = transaction {
            Projects.insert {
                it[organization_id] = testOrgId
                it[name] = "Checkout API"
                it[slug] = "checkout-api"
            } get Projects.id
        }
        val groupRows = listOf(
            jsonRow(
                "\"source_value\":\"otlp\"",
                "\"service\":\"checkout\"",
                "\"operation\":\"GET /orders\"",
                "\"resource\":\"GET /orders\"",
                "\"span_type\":\"web\"",
                "\"env\":\"prod\"",
                "\"kind\":\"SERVER\"",
                "\"scope_name\":\"ktor\"",
                "\"scope_version\":\"1.0.0\"",
                "\"project_id\":$projectId",
                "\"span_count\":10",
                "\"trace_count\":4",
                "\"error_count\":1",
                "\"avg_duration_ms\":12.5",
                "\"max_duration_ms\":80.0",
                "\"sample_trace_id\":\"trace-a\"",
                "\"latest_span_at\":\"2026-04-15 12:00:00\""
            ),
            jsonRow(
                "\"service\":\"worker\"",
                "\"operation\":\"queue.process\"",
                "\"resource\":\"order-created\"",
                "\"span_type\":\"\"",
                "\"env\":\"\"",
                "\"kind\":\"CONSUMER\"",
                "\"scope_name\":\"\"",
                "\"scope_version\":\"\"",
                "\"span_count\":15",
                "\"trace_count\":5",
                "\"error_count\":0",
                "\"avg_duration_ms\":2.0",
                "\"max_duration_ms\":8.0",
                "\"sample_trace_id\":\"\"",
                "\"latest_span_at\":\"\""
            )
        ).joinToString("\n")
        var groupedQuery = ""

        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat_test"
            coEvery {
                ClickHouseClient.executeWithFormat(
                    match { it.contains("SELECT count()") },
                    match { it == "TabSeparated" }
                )
            } returns "25"
            coEvery {
                ClickHouseClient.executeWithFormat(
                    match { it.contains("GROUP BY") },
                    match { it == "" }
                )
            } coAnswers {
                groupedQuery = invocation.args[0] as String
                groupRows
            }

            val result = service.getApmSpanUsageDebug(
                organizationId = testOrgId,
                periodStart = LocalDate.parse("2026-04-01"),
                periodEnd = LocalDate.parse("2026-04-30"),
                limit = 500
            )

            assertEquals(testOrgId, result.organizationId)
            assertEquals("2026-04-01", result.periodStart)
            assertEquals("2026-04-30", result.periodEnd)
            assertEquals(25, result.totalSpans)
            assertEquals(2, result.groups.size)
            assertTrue(groupedQuery.contains("LIMIT 100"))
            assertTrue(groupedQuery.contains("2026-05-01 00:00:00"))

            val checkout = result.groups.first()
            assertEquals("otlp", checkout.source)
            assertEquals("checkout", checkout.service)
            assertEquals("GET /orders", checkout.operation)
            assertEquals(projectId, checkout.projectId)
            assertEquals("Checkout API", checkout.projectName)
            assertEquals("checkout-api", checkout.projectSlug)
            assertEquals(10, checkout.spanCount)
            assertEquals(4, checkout.traceCount)
            assertEquals(1, checkout.errorCount)
            assertEquals(40.0, checkout.percentage)

            val worker = result.groups.last()
            assertEquals("datadog", worker.source)
            assertNull(worker.projectName)
            assertEquals(60.0, worker.percentage)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getApmSpanUsageDebug returns empty groups without querying grouped rows when total is zero`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat_test"
            coEvery {
                ClickHouseClient.executeWithFormat(
                    match { it.contains("SELECT count()") },
                    match { it == "TabSeparated" }
                )
            } returns "0"

            val result = service.getApmSpanUsageDebug(
                organizationId = testOrgId,
                periodStart = LocalDate.parse("2026-04-01"),
                periodEnd = LocalDate.parse("2026-04-30"),
                limit = 20
            )

            assertEquals(0, result.totalSpans)
            assertTrue(result.groups.isEmpty())
            coVerify(exactly = 0) {
                ClickHouseClient.executeWithFormat(
                    match { it.contains("GROUP BY") },
                    match { it == "" }
                )
            }
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getApmSpanUsageDebug treats non numeric totals as zero`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat_test"
            coEvery {
                ClickHouseClient.executeWithFormat(
                    match { it.contains("SELECT count()") },
                    match { it == "TabSeparated" }
                )
            } returns "not-a-number"

            val result = service.getApmSpanUsageDebug(
                organizationId = testOrgId,
                periodStart = LocalDate.parse("2026-04-01"),
                periodEnd = LocalDate.parse("2026-04-30"),
                limit = 20
            )

            assertEquals(0, result.totalSpans)
            assertTrue(result.groups.isEmpty())
            coVerify(exactly = 0) {
                ClickHouseClient.executeWithFormat(
                    match { it.contains("GROUP BY") },
                    match { it == "" }
                )
            }
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getApmSpanUsageDebug skips malformed rows and defaults missing fields`() = runBlocking {
        val groupRows = listOf(
            "not-json",
            "",
            jsonRow("\"span_count\":2")
        ).joinToString("\n")

        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat_test"
            coEvery {
                ClickHouseClient.executeWithFormat(
                    match { it.contains("SELECT count()") },
                    match { it == "TabSeparated" }
                )
            } returns "5"
            coEvery {
                ClickHouseClient.executeWithFormat(
                    match { it.contains("GROUP BY") },
                    match { it == "" }
                )
            } returns groupRows

            val result = service.getApmSpanUsageDebug(
                organizationId = testOrgId,
                periodStart = LocalDate.parse("2026-04-01"),
                periodEnd = LocalDate.parse("2026-04-30"),
                limit = 20
            )

            assertEquals(5, result.totalSpans)
            assertEquals(1, result.groups.size)
            val group = result.groups.single()
            assertEquals("datadog", group.source)
            assertEquals("", group.service)
            assertEquals("", group.operation)
            assertEquals("", group.resource)
            assertEquals("", group.spanType)
            assertEquals("", group.env)
            assertEquals("", group.kind)
            assertEquals("", group.scopeName)
            assertEquals("", group.scopeVersion)
            assertEquals(2, group.spanCount)
            assertEquals(0, group.traceCount)
            assertEquals(0, group.errorCount)
            assertEquals(0.0, group.avgDurationMs)
            assertEquals(0.0, group.maxDurationMs)
            assertEquals("", group.sampleTraceId)
            assertEquals("", group.latestSpanAt)
            assertEquals(40.0, group.percentage)
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `getApmSpanUsageDebug falls back to empty response on ClickHouse failure`() = runBlocking {
        mockkObject(ClickHouseClient)
        try {
            every { ClickHouseClient.getDatabase() } returns "moneat_test"
            coEvery {
                ClickHouseClient.executeWithFormat(
                    match { it.contains("SELECT count()") },
                    match { it == "TabSeparated" }
                )
            } throws IllegalStateException("ClickHouse unavailable")

            val result = service.getApmSpanUsageDebug(
                organizationId = testOrgId,
                periodStart = LocalDate.parse("2026-04-01"),
                periodEnd = LocalDate.parse("2026-04-30"),
                limit = 20
            )

            assertEquals(testOrgId, result.organizationId)
            assertEquals(0, result.totalSpans)
            assertTrue(result.groups.isEmpty())
        } finally {
            unmockkObject(ClickHouseClient)
        }
    }

    @Test
    fun `isEnforcementEnabled is false when self-hosted`() {
        mockkObject(EnvConfig.SelfHost)
        try {
            every { EnvConfig.SelfHost.enabled } returns true
            assertFalse(service.isEnforcementEnabled())
        } finally {
            unmockkObject(EnvConfig.SelfHost)
        }
    }

    @Test
    fun `isEnforcementEnabled is true when not self-hosted`() {
        mockkObject(EnvConfig.SelfHost)
        try {
            every { EnvConfig.SelfHost.enabled } returns false
            assertTrue(service.isEnforcementEnabled())
        } finally {
            unmockkObject(EnvConfig.SelfHost)
        }
    }
}
