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

import com.moneat.models.EmailsSent
import com.moneat.models.Memberships
import com.moneat.models.OrgUsageCounters
import com.moneat.models.Organizations
import com.moneat.models.PricingTierConfigs
import com.moneat.models.QuotaNotificationsSent
import com.moneat.models.Subscriptions
import com.moneat.models.Users
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BillingBackgroundServiceTest {
    private var testOrgId: Int = 0

    companion object {
        private var dbInitialized = false
    }

    @BeforeTest
    fun setupDatabase() {
        if (!dbInitialized) {
            Database.connect(
                url = "jdbc:h2:mem:moneat_billing_bg;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction {
                SchemaUtils.create(
                    Organizations,
                    Users,
                    Memberships,
                    PricingTierConfigs,
                    Subscriptions,
                    OrgUsageCounters,
                    QuotaNotificationsSent,
                    EmailsSent
                )
            }
            dbInitialized = true
        }

        transaction {
            QuotaNotificationsSent.deleteAll()
            OrgUsageCounters.deleteAll()
            Subscriptions.deleteAll()
            Memberships.deleteAll()
            EmailsSent.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
            PricingTierConfigs.deleteAll()
        }

        transaction {
            testOrgId = Organizations.insert {
                it[name] = "Billing Org"
                it[slug] = "billing-org"
            }[Organizations.id]

            val ownerId = Users.insert {
                it[email] = "owner@moneat.test"
                it[password_hash] = "hash"
                it[name] = "Owner"
            }[Users.id]

            Memberships.insert {
                it[user_id] = ownerId
                it[organization_id] = testOrgId
                it[role] = "owner"
            }

            val tierId = PricingTierConfigs.insert {
                it[tier_name] = "PRO"
                it[version] = 1
                it[monthly_unit_limit] = 1000
                it[monthly_error_limit] = 1000
                it[monthly_transaction_limit] = 0
                it[monthly_replay_limit] = 0
                it[monthly_feedback_limit] = 0
                it[monthly_llm_event_limit] = 0
                it[monthly_gb_limit] = 10
                it[retention_days] = 30
                it[log_retention_days] = 30
                it[replay_retention_days] = 30
                it[llm_retention_days] = 30
                it[status_pages_enabled] = true
                it[status_page_custom_domain_enabled] = true
                it[session_replay_enabled] = true
                it[slack_enabled] = false
                it[discord_enabled] = false
                it[incident_io_enabled] = false
                it[saml_enabled] = false
                it[oidc_enabled] = false
                it[priority_support_enabled] = false
                it[sla_enabled] = false
                it[custom_retention_enabled] = false
                it[max_projects] = 10
                it[max_systems] = 10
                it[monitor_interval_seconds] = 60
                it[monthly_price_cents] = 2900
                it[yearly_price_cents] = 28800
                it[trial_days] = 14
                it[payg_enabled] = true
                it[payg_rate_micros_per_unit] = 400_000
                it[overage_rate_cents_per_gb] = 40
                it[error_overage_rate_cents_per_1k] = 10
                it[replay_overage_rate_cents_per_gb] = 40
                it[llm_overage_rate_cents_per_1k] = 100
                it[oncall_per_user_monthly_cents] = 500
                it[oncall_per_user_yearly_cents] = 5000
                it[oncall_enabled] = true
                it[stripe_base_price_id] = null
                it[stripe_overage_price_id] = null
                it[stripe_yearly_base_price_id] = null
                it[stripe_yearly_overage_price_id] = null
                it[stripe_oncall_price_id] = null
                it[stripe_oncall_yearly_price_id] = null
                it[is_current] = true
            }[PricingTierConfigs.id]

            val now = Clock.System.now()
            val periodStart = now.toLocalDateTime(TimeZone.UTC).date
            val periodEnd = periodStart.plus(DatePeriod(months = 1, days = -1))

            Subscriptions.insert {
                it[organization_id] = testOrgId
                it[plan] = "PRO"
                it[status] = "active"
                it[billing_interval] = "monthly"
                it[current_period_start] = now
                it[current_period_end] = Instant.fromEpochSeconds(now.epochSeconds + 2_592_000)
                it[pricing_tier_config_id] = tierId
                it[payg_budget_cents] = 10_000
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
            }

            OrgUsageCounters.insert {
                it[organization_id] = testOrgId
                it[period_start] = periodStart
                it[period_end] = periodEnd
                it[used_units] = 1200
                it[used_errors] = 1200
                it[used_transactions] = 0
                it[used_replays] = 0
                it[used_feedback] = 0
                it[used_llm_events] = 0
                it[used_logs] = 0
                it[used_bytes] = 0
                it[used_error_bytes] = 0
                it[used_replay_bytes] = 0
                it[used_log_bytes] = 0
                it[used_llm_bytes] = 0
                it[updated_at] = now
            }
        }
    }

    @Test
    fun `process quota threshold notifications sends each threshold once per period`() {
        val service = BillingBackgroundService()

        invokeQuotaNotificationPass(service)
        invokeQuotaNotificationPass(service)

        transaction {
            val types = QuotaNotificationsSent.selectAll()
                .where { QuotaNotificationsSent.organization_id eq testOrgId }
                .map { it[QuotaNotificationsSent.notification_type] }
                .toSet()

            assertEquals(3, types.size)
            assertTrue("base_80" in types)
            assertTrue("base_100" in types)
            assertTrue("payg_80" in types)
        }
    }

    private fun invokeQuotaNotificationPass(service: BillingBackgroundService) {
        val method = BillingBackgroundService::class.java.getDeclaredMethod("processQuotaThresholdNotifications")
        method.isAccessible = true
        method.invoke(service)
    }
}
