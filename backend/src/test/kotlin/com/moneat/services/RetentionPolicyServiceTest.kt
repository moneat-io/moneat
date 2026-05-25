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

import com.moneat.billing.models.PricingTierConfigs
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetentionPolicyServiceTest {
    private val service = RetentionPolicyService()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db =
                Database.connect(
                    url =
                    "jdbc:h2:mem:moneat_retention_policy;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver"
                )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Organizations, Projects, Subscriptions, PricingTierConfigs)
    }

    private fun seedOrg(name: String = "Test Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedFreeTier(
        retentionDays: Int = 3,
        logRetentionDays: Int = 3,
        apmTraceRetentionDays: Int = retentionDays
    ): Int =
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = "FREE"
                it[version] = 1
                it[monthly_unit_limit] = 5000
                it[monthly_error_limit] = 5000
                it[retention_days] = retentionDays
                it[log_retention_days] = logRetentionDays
                it[apm_trace_retention_days] = apmTraceRetentionDays
                it[max_systems] = 3
                it[monitor_interval_seconds] = 60
                it[monthly_price_cents] = 0
                it[yearly_price_cents] = 0
                it[is_current] = true
            } get PricingTierConfigs.id
        }

    private fun seedProTier(
        retentionDays: Int = 30,
        logRetentionDays: Int = 30,
        apmTraceRetentionDays: Int = retentionDays
    ): Int =
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = "PRO"
                it[version] = 1
                it[monthly_unit_limit] = 50_000
                it[monthly_error_limit] = 50_000
                it[retention_days] = retentionDays
                it[log_retention_days] = logRetentionDays
                it[apm_trace_retention_days] = apmTraceRetentionDays
                it[max_systems] = 10
                it[monitor_interval_seconds] = 30
                it[monthly_price_cents] = 2900
                it[yearly_price_cents] = 24900
                it[is_current] = true
            } get PricingTierConfigs.id
        }

    private fun seedProject(orgId: Int, name: String = "test-project"): Long =
        transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[Projects.name] = name
                it[Projects.slug] = name
            } get Projects.id
        }

    private fun seedSubscription(orgId: Int, tierId: Int, plan: String = "PRO"): Int =
        transaction {
            Subscriptions.insert {
                it[Subscriptions.organization_id] = orgId
                it[Subscriptions.plan] = plan
                it[Subscriptions.status] = "active"
                it[Subscriptions.pricing_tier_config_id] = tierId
            } get Subscriptions.id
        }

    @Test
    fun `getRetentionDaysForOrganization returns FREE tier retention when no subscription`() =
        runBlocking {
            seedFreeTier(retentionDays = 3)
            val orgId = seedOrg()

            val days = service.getRetentionDaysForOrganization(orgId)

            assertEquals(3, days)
        }

    @Test
    fun `getRetentionDaysForOrganization returns PRO tier retention when has subscription`() =
        runBlocking {
            val freeTierId = seedFreeTier()
            val proTierId = seedProTier(retentionDays = 30)
            val orgId = seedOrg()
            seedSubscription(orgId, proTierId, "PRO")

            val days = service.getRetentionDaysForOrganization(orgId)

            assertEquals(30, days)
        }

    @Test
    fun `getRetentionDaysForProject returns org retention when project exists`() =
        runBlocking {
            val proTierId = seedProTier(retentionDays = 14)
            val orgId = seedOrg()
            seedSubscription(orgId, proTierId)
            val projectId = seedProject(orgId)

            val days = service.getRetentionDaysForProject(projectId)

            assertEquals(14, days)
        }

    @Test
    fun `getRetentionDaysForProject returns null when project not found`() =
        runBlocking {
            val days = service.getRetentionDaysForProject(99_999L)

            assertNull(days)
        }

    @Test
    fun `getRetentionDaysByOrganization returns map of org to retention`() =
        runBlocking {
            seedFreeTier()
            val proTierId = seedProTier(retentionDays = 30)
            val org1 = seedOrg("Org 1")
            val org2 = seedOrg("Org 2")
            seedSubscription(org2, proTierId)

            val map = service.getRetentionDaysByOrganization()

            assertEquals(2, map.size)
            assertEquals(3, map[org1])
            assertEquals(30, map[org2])
        }

    @Test
    fun `getRetentionDaysByOrganization returns empty map when no orgs`() =
        runBlocking {
            seedFreeTier()

            val map = service.getRetentionDaysByOrganization()

            assertEquals(0, map.size)
        }

    @Test
    fun `getLogRetentionDaysForOrganization returns tier log retention`() =
        runBlocking {
            seedFreeTier(logRetentionDays = 7)
            val orgId = seedOrg()

            val days = service.getLogRetentionDaysForOrganization(orgId)

            assertEquals(7, days)
        }

    @Test
    fun `getLogRetentionDaysForProject returns org log retention when project exists`() =
        runBlocking {
            seedProTier(logRetentionDays = 14)
            val orgId = seedOrg()
            val proTierId = seedProTier(logRetentionDays = 14)
            seedSubscription(orgId, proTierId)
            val projectId = seedProject(orgId)

            val days = service.getLogRetentionDaysForProject(projectId)

            assertEquals(14, days)
        }

    @Test
    fun `getLogRetentionDaysByOrganization returns map of org to log retention`() =
        runBlocking {
            seedFreeTier(logRetentionDays = 3)
            val proTierId = seedProTier(logRetentionDays = 30)
            val org1 = seedOrg("Org 1")
            val org2 = seedOrg("Org 2")
            seedSubscription(org2, proTierId)

            val map = service.getLogRetentionDaysByOrganization()

            assertEquals(2, map.size)
            assertEquals(3, map[org1])
            assertEquals(30, map[org2])
        }

    @Test
    fun `getApmTraceRetentionDaysForOrganization returns tier APM trace retention`() =
        runBlocking {
            seedFreeTier(apmTraceRetentionDays = 7)
            val orgId = seedOrg()

            val days = service.getApmTraceRetentionDaysForOrganization(orgId)

            assertEquals(7, days)
        }

    @Test
    fun `getApmTraceRetentionDaysByOrganization returns map of org to APM trace retention`() =
        runBlocking {
            seedFreeTier(apmTraceRetentionDays = 3)
            val proTierId = seedProTier(retentionDays = 30, apmTraceRetentionDays = 45)
            val org1 = seedOrg("Org 1")
            val org2 = seedOrg("Org 2")
            seedSubscription(org2, proTierId)

            val map = service.getApmTraceRetentionDaysByOrganization()

            assertEquals(2, map.size)
            assertEquals(3, map[org1])
            assertEquals(45, map[org2])
        }
}
