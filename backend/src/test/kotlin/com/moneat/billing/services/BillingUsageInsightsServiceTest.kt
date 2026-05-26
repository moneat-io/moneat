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

package com.moneat.billing.services

import com.moneat.billing.models.ApmSpanUsageDebugResponse
import com.moneat.billing.models.BillingUsageResponse
import com.moneat.config.EnvConfig
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.UsageRecords
import com.moneat.shared.services.UsageTrackingService
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class BillingUsageInsightsServiceTest {
    companion object {
        private const val BYTES_PER_GB = 1_073_741_824L
        private const val APM_SPAN_LIMIT = 2_000_000L
        private var db: Database? = null
    }

    private lateinit var quotaService: BillingQuotaService
    private lateinit var usageTrackingService: UsageTrackingService

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:billing_usage_insights;" +
                    "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                    "DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Organizations, Projects, UsageRecords)
        quotaService = mockk()
        usageTrackingService = mockk()
        every { usageTrackingService.flushBuffer() } just Runs
        mockkObject(EnvConfig.SelfHost)
        every { EnvConfig.SelfHost.enabled } returns false
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(EnvConfig.SelfHost)
    }

    @Test
    fun `getUsageInsights builds forecasts contributors and debug data`() = runBlocking {
        val today = Clock.System.todayIn(TimeZone.UTC)
        val periodStart = today.plus(DatePeriod(days = -6))
        val periodEnd = today.plus(DatePeriod(days = 4))
        val orgId = seedOrg()
        val checkoutProjectId = seedProject(orgId, "Checkout API", "checkout-api")
        val workerProjectId = seedProject(orgId, "Billing Worker", "billing-worker")
        val usage = usageResponse(
            orgId = orgId,
            periodStart = periodStart,
            periodEnd = periodEnd
        )
        seedSevenDayHistory(
            orgId = orgId,
            checkoutProjectId = checkoutProjectId,
            workerProjectId = workerProjectId,
            today = today
        )
        stubUsageResponses(orgId, periodStart, periodEnd, usage)

        val service = BillingUsageInsightsService(
            quotaService = quotaService,
            usageTrackingService = usageTrackingService
        )
        val result = service.getUsageInsights(orgId)

        assertEquals("cloud", result.billingMode)
        assertEquals(5, result.dimensions.size)
        assertEquals(42, result.apmSpanDebug?.totalSpans)

        val ingestion = requireNotNull(result.dimensions.find { it.key == "ingestion" })
        assertEquals(8 * BYTES_PER_GB, ingestion.used)
        assertEquals(10 * BYTES_PER_GB, ingestion.baseLimit)
        assertEquals(12 * BYTES_PER_GB, ingestion.effectiveLimit)
        assertEquals("7d", ingestion.forecast.window)
        assertEquals("warning", ingestion.forecast.riskLevel)
        assertNotNull(ingestion.forecast.projectedBaseLimitHitDate)
        assertTrue(ingestion.daily.isNotEmpty())
        assertTrue(ingestion.contributors.any { it.label == "Logs" })
        assertTrue(ingestion.contributors.any { it.label == "Checkout API - Logs" })

        val apmSpans = requireNotNull(result.dimensions.find { it.key == "apm_span" })
        assertEquals(APM_SPAN_LIMIT, apmSpans.baseLimit)
        assertEquals("warning", apmSpans.forecast.riskLevel)
        assertEquals("high", apmSpans.forecast.confidence)
        assertEquals(today.toString(), apmSpans.forecast.projectedBaseLimitHitDate)
        assertTrue(apmSpans.contributors.any { it.label == "Checkout API - APM spans" })
    }

    @Test
    fun `getUsageInsights falls back when usage history is empty`() = runBlocking {
        val today = Clock.System.todayIn(TimeZone.UTC)
        val periodStart = today.plus(DatePeriod(days = -2))
        val periodEnd = today.plus(DatePeriod(days = 8))
        val orgId = seedOrg()
        val usage = usageResponse(
            orgId = orgId,
            periodStart = periodStart,
            periodEnd = periodEnd,
            usedBytes = 0,
            usedApmSpans = 0,
            usedCustomMetrics = 0,
            usedAnalyticsPageviews = 0
        )
        stubUsageResponses(orgId, periodStart, periodEnd, usage)

        val service = BillingUsageInsightsService(
            quotaService = quotaService,
            usageTrackingService = usageTrackingService
        )
        val result = service.getUsageInsights(orgId)
        val ingestion = requireNotNull(result.dimensions.find { it.key == "ingestion" })

        assertEquals("insufficient_data", ingestion.forecast.window)
        assertEquals("low", ingestion.forecast.confidence)
        assertEquals(0.0, ingestion.forecast.dailyRate)
        assertNull(ingestion.forecast.projectedBaseLimitHitDate)
        assertTrue(ingestion.daily.all { it.value == 0L })
    }

    @Test
    fun `getUsageInsightsSafely returns null when insights fail`() = runBlocking {
        val service = mockk<BillingUsageInsightsService>()
        coEvery { service.getUsageInsights(1) } throws IllegalStateException("boom")

        assertNull(service.getUsageInsightsSafely(1))
    }

    private fun seedOrg(): Int = transaction {
        Organizations.insert {
            it[name] = "Billing Org"
            it[slug] = "billing-org-${System.nanoTime()}"
        } get Organizations.id
    }

    private fun seedProject(
        orgId: Int,
        name: String,
        slug: String
    ): Long = transaction {
        Projects.insert {
            it[organization_id] = orgId
            it[Projects.name] = name
            it[Projects.slug] = slug
            it[framework] = "kotlin"
        } get Projects.id
    }

    private fun seedSevenDayHistory(
        orgId: Int,
        checkoutProjectId: Long,
        workerProjectId: Long,
        today: LocalDate
    ) {
        for (daysAgo in 0..6) {
            val date = today.plus(DatePeriod(days = -daysAgo))
            seedUsageRecord(orgId, checkoutProjectId, "log", 120_000, BYTES_PER_GB, date)
            seedUsageRecord(orgId, checkoutProjectId, "apm_span", 260_000, 80_000_000L, date)
            seedUsageRecord(orgId, workerProjectId, "custom_metric", 60_000, 0, date)
            seedUsageRecord(orgId, null, "analytics_pageview", 25_000, 0, date)
            seedUsageRecord(orgId, workerProjectId, "infra_metric", 20_000, 50_000_000L, date)
        }
    }

    private fun seedUsageRecord(
        orgId: Int,
        projectId: Long?,
        eventType: String,
        eventCount: Int,
        bytesIngested: Long,
        date: LocalDate
    ) {
        transaction {
            UsageRecords.insert {
                it[organization_id] = orgId
                it[project_id] = projectId?.toInt()
                it[event_type] = eventType
                it[event_count] = eventCount
                it[bytes_ingested] = bytesIngested
                it[recordDate] = date
            }
        }
    }

    private fun stubUsageResponses(
        orgId: Int,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        usage: BillingUsageResponse
    ) {
        every { quotaService.getUsageForOrganization(orgId) } returns usage
        coEvery {
            quotaService.getApmSpanUsageDebug(
                organizationId = orgId,
                periodStart = periodStart,
                periodEnd = periodEnd,
                limit = any()
            )
        } returns ApmSpanUsageDebugResponse(
            organizationId = orgId,
            periodStart = periodStart.toString(),
            periodEnd = periodEnd.toString(),
            totalSpans = 42,
            groups = emptyList()
        )
    }

    private fun usageResponse(
        orgId: Int,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        usedBytes: Long = 9 * BYTES_PER_GB,
        usedApmSpans: Long = 2_100_000,
        usedCustomMetrics: Long = 420_000,
        usedAnalyticsPageviews: Long = 175_000
    ) = BillingUsageResponse(
        organizationId = orgId,
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        retentionDays = 30,
        logRetentionDays = 30,
        replayRetentionDays = 14,
        llmRetentionDays = 30,
        apmTraceRetentionDays = 30,
        usedUnits = 9_000_000,
        usedErrors = 10_000,
        errorLimit = 100_000,
        usedTransactions = 30_000,
        transactionLimit = 250_000,
        usedReplays = 50,
        replayLimit = 500,
        usedFeedback = 20,
        feedbackLimit = 1_000,
        usedLlmEvents = 8_000,
        llmEventLimit = 50_000,
        usedLogs = 840_000,
        usedBytes = usedBytes,
        usedErrorBytes = BYTES_PER_GB,
        usedReplayBytes = 0,
        usedLogBytes = 7 * BYTES_PER_GB,
        usedLlmBytes = 0,
        usedProfilerBytes = 0,
        bytesLimit = 10 * BYTES_PER_GB,
        ingestionOverageCentsEstimate = 100,
        ingestionOverageRateCentsPerGb = 250,
        baseLimitUnits = 10_000_000,
        paygLimitUnits = 1_000_000,
        paygLimitBytes = BYTES_PER_GB,
        totalLimitUnits = 11_000_000,
        paygBudgetCents = 5_000,
        paygUsedUnits = 0,
        paygUsedCentsEstimate = 0,
        apmSpanOverageCentsEstimate = 50,
        customMetricOverageCentsEstimate = 0,
        totalOverageCentsEstimate = 150,
        apmSpanOverageRateCentsPer1m = 500,
        customMetricOverageRateCentsPer100k = 120,
        usedAnalyticsPageviews = usedAnalyticsPageviews,
        analyticsPageviewLimit = 250_000,
        analyticsPageviewOverageRateCentsPer100k = 100,
        usedApmSpans = usedApmSpans,
        usedApmSpanBytes = BYTES_PER_GB,
        apmSpanLimit = APM_SPAN_LIMIT,
        usedCustomMetrics = usedCustomMetrics,
        customMetricLimit = 500_000,
        usedInfraMetricSeriesHours = 140_000,
        usedInfraMetricBytes = 0,
        infraMetricSeriesHourLimit = 400_000,
        infraMetricOverageRateCentsPer100kSeriesHours = 150,
        plan = "TEAM",
        status = "active",
        withinQuota = false,
        bonusGbBytes = BYTES_PER_GB,
        bonusUnits = 1_000_000,
        bonusReason = "launch credit"
    )
}
