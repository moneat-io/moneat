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

package com.moneat.routes

import com.moneat.models.PricingTierConfigs
import com.moneat.services.PricingTierService
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicBillingRoutesTest {
    companion object {
        private var dbInitialized = false
    }
    
    @BeforeTest
    fun setupDatabase() {
        // Initialize DB connection and schema once per test class
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_public_billing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(PricingTierConfigs)
            }
            dbInitialized = true
        }
        
        // Clean up any existing test data from previous tests
        transaction {
            PricingTierConfigs.deleteAll()
        }

        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = "FREE"
                it[version] = 1
                it[monthly_unit_limit] = 10_000
                it[monthly_error_limit] = 10_000
                it[monthly_transaction_limit] = 0
                it[monthly_replay_limit] = 0
                it[monthly_feedback_limit] = 0
                it[monthly_gb_limit] = 1_073_741_824
                it[retention_days] = 3
                it[log_retention_days] = 3
                it[status_pages_enabled] = true
                it[status_page_custom_domain_enabled] = true
                it[session_replay_enabled] = true
                it[slack_enabled] = true
                it[incident_io_enabled] = true
                it[saml_enabled] = false
                it[oidc_enabled] = false
                it[priority_support_enabled] = false
                it[sla_enabled] = false
                it[custom_retention_enabled] = false
                it[max_projects] = 3
                it[max_systems] = 3
                it[monitor_interval_seconds] = 60
                it[monthly_price_cents] = 0
                it[yearly_price_cents] = 0
                it[trial_days] = 0
                it[payg_enabled] = false
                it[payg_rate_micros_per_unit] = 0
                it[overage_rate_cents_per_gb] = 0
                it[is_current] = true
            }
        }
    }

    @Test
    fun `billing plans endpoint returns feature flags`() {
        val pricingTierService = PricingTierService()
        val plans = pricingTierService.getCurrentPlans()
        
        assertEquals(1, plans.size)
        
        val plan = plans[0]
        assertEquals("FREE", plan.tier.tierName)
        assertTrue(plan.tier.statusPagesEnabled)
        assertTrue(plan.tier.statusPageCustomDomainEnabled)
        assertTrue(plan.tier.sessionReplayEnabled)
        assertTrue(plan.tier.slackEnabled)
        assertTrue(plan.tier.incidentIoEnabled)
        assertEquals(0, plan.trialDays)
    }
}
