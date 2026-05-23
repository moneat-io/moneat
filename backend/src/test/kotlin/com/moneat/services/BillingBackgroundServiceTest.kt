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
import com.moneat.billing.models.QuotaNotificationsSent
import com.moneat.billing.services.BillingBackgroundService
import com.moneat.shared.models.EmailsSent
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class BillingBackgroundServiceTest {
    private var testOrgId: Int = 0

    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_billing_bg;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;MODE=MYSQL",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            Subscriptions,
            EmailsSent,
            PricingTierConfigs,
            OrgUsageCounters,
            QuotaNotificationsSent
        )

        transaction {
            testOrgId =
                Organizations.insert {
                    it[name] = "Billing Org"
                    it[slug] = "billing-org"
                }[Organizations.id]

            val ownerId =
                Users.insert {
                    it[email] = "owner@moneat.test"
                    it[password_hash] = "hash"
                    it[name] = "Owner"
                }[Users.id]

            Memberships.insert {
                it[user_id] = ownerId
                it[organization_id] = testOrgId
                it[role] = "owner"
            }

            val tierId =
                PricingTierConfigs.insert {
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
            val types =
                QuotaNotificationsSent
                    .selectAll()
                    .where { QuotaNotificationsSent.organization_id eq testOrgId }
                    .map { it[QuotaNotificationsSent.notification_type] }
                    .toSet()

            assertEquals(4, types.size)
            assertTrue("base_80" in types)
            assertTrue("base_90" in types)
            assertTrue("base_100" in types)
            assertTrue("payg_80" in types)
        }
    }

    @Test
    fun `bytes-only tier triggers notifications when unit limit is zero`() {
        transaction {
            TestDatabaseHelper.resetSchema(
                Users,
                Organizations,
                Memberships,
                Subscriptions,
                EmailsSent,
                PricingTierConfigs,
                OrgUsageCounters,
                QuotaNotificationsSent
            )

            val orgId =
                Organizations.insert {
                    it[name] = "Bytes Org"
                    it[slug] = "bytes-org"
                }[Organizations.id]

            val ownerId =
                Users.insert {
                    it[email] = "bytes-owner@moneat.test"
                    it[password_hash] = "hash"
                    it[name] = "Bytes Owner"
                }[Users.id]

            Memberships.insert {
                it[user_id] = ownerId
                it[organization_id] = orgId
                it[role] = "owner"
            }

            val gbLimit = 1_073_741_824L // 1 GB
            val tierId =
                PricingTierConfigs.insert {
                    it[tier_name] = "PRO_GB"
                    it[version] = 1
                    it[monthly_unit_limit] = 0
                    it[monthly_error_limit] = 0
                    it[monthly_transaction_limit] = 0
                    it[monthly_replay_limit] = 0
                    it[monthly_feedback_limit] = 0
                    it[monthly_llm_event_limit] = 0
                    it[monthly_gb_limit] = gbLimit
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
                    it[payg_enabled] = false
                    it[payg_rate_micros_per_unit] = 0
                    it[overage_rate_cents_per_gb] = 0
                    it[error_overage_rate_cents_per_1k] = 0
                    it[replay_overage_rate_cents_per_gb] = 0
                    it[llm_overage_rate_cents_per_1k] = 0
                    it[oncall_per_user_monthly_cents] = 0
                    it[oncall_per_user_yearly_cents] = 0
                    it[oncall_enabled] = false
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
                it[organization_id] = orgId
                it[plan] = "PRO_GB"
                it[status] = "active"
                it[billing_interval] = "monthly"
                it[current_period_start] = now
                it[current_period_end] = Instant.fromEpochSeconds(now.epochSeconds + 2_592_000)
                it[pricing_tier_config_id] = tierId
                it[payg_budget_cents] = 0
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
            }

            OrgUsageCounters.insert {
                it[organization_id] = orgId
                it[period_start] = periodStart
                it[period_end] = periodEnd
                it[used_units] = 0
                it[used_errors] = 0
                it[used_transactions] = 0
                it[used_replays] = 0
                it[used_feedback] = 0
                it[used_llm_events] = 0
                it[used_logs] = 0
                it[used_bytes] = gbLimit + 100_000
                it[used_error_bytes] = gbLimit + 100_000
                it[used_replay_bytes] = 0
                it[used_log_bytes] = 0
                it[used_llm_bytes] = 0
                it[updated_at] = now
            }

            testOrgId = orgId
        }

        val service = BillingBackgroundService()
        invokeQuotaNotificationPass(service)

        transaction {
            val types =
                QuotaNotificationsSent
                    .selectAll()
                    .where { QuotaNotificationsSent.organization_id eq testOrgId }
                    .map { it[QuotaNotificationsSent.notification_type] }
                    .toSet()

            assertTrue("base_80" in types, "Expected base_80 notification for bytes-only tier")
            assertTrue("base_90" in types, "Expected base_90 notification for bytes-only tier")
            assertTrue("base_100" in types, "Expected base_100 notification for bytes-only tier")
        }
    }

    @Test
    fun `apm span bytes excluded from GB-eligible bytes for base notifications`() {
        transaction {
            TestDatabaseHelper.resetSchema(
                Users,
                Organizations,
                Memberships,
                Subscriptions,
                EmailsSent,
                PricingTierConfigs,
                OrgUsageCounters,
                QuotaNotificationsSent
            )

            val orgId =
                Organizations.insert {
                    it[name] = "APM Org"
                    it[slug] = "apm-org"
                }[Organizations.id]

            val ownerId =
                Users.insert {
                    it[email] = "apm-owner@moneat.test"
                    it[password_hash] = "hash"
                    it[name] = "APM Owner"
                }[Users.id]

            Memberships.insert {
                it[user_id] = ownerId
                it[organization_id] = orgId
                it[role] = "owner"
            }

            val gbLimit = 1_073_741_824L // 1 GB
            val tierId =
                PricingTierConfigs.insert {
                    it[tier_name] = "PRO_APM"
                    it[version] = 1
                    it[monthly_unit_limit] = 0
                    it[monthly_error_limit] = 0
                    it[monthly_transaction_limit] = 0
                    it[monthly_replay_limit] = 0
                    it[monthly_feedback_limit] = 0
                    it[monthly_llm_event_limit] = 0
                    it[monthly_gb_limit] = gbLimit
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
                    it[payg_enabled] = false
                    it[payg_rate_micros_per_unit] = 0
                    it[overage_rate_cents_per_gb] = 0
                    it[error_overage_rate_cents_per_1k] = 0
                    it[replay_overage_rate_cents_per_gb] = 0
                    it[llm_overage_rate_cents_per_1k] = 0
                    it[apm_span_overage_rate_cents_per_1m] = 50
                    it[oncall_per_user_monthly_cents] = 0
                    it[oncall_per_user_yearly_cents] = 0
                    it[oncall_enabled] = false
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
                it[organization_id] = orgId
                it[plan] = "PRO_APM"
                it[status] = "active"
                it[billing_interval] = "monthly"
                it[current_period_start] = now
                it[current_period_end] = Instant.fromEpochSeconds(now.epochSeconds + 2_592_000)
                it[pricing_tier_config_id] = tierId
                it[payg_budget_cents] = 0
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
            }

            // Total bytes exceed limit, but most are APM spans.
            // GB-eligible bytes = 1.2 GB - 900 MB = ~300 MB (< 80% of 1 GB).
            val apmSpanBytes = 900_000_000L
            val errorBytes = (gbLimit * 3) / 10 // ~300 MB
            OrgUsageCounters.insert {
                it[organization_id] = orgId
                it[period_start] = periodStart
                it[period_end] = periodEnd
                it[used_units] = 0
                it[used_errors] = 0
                it[used_transactions] = 0
                it[used_replays] = 0
                it[used_feedback] = 0
                it[used_llm_events] = 0
                it[used_logs] = 0
                it[used_bytes] = errorBytes + apmSpanBytes
                it[used_apm_span_bytes] = apmSpanBytes
                it[used_error_bytes] = errorBytes
                it[used_replay_bytes] = 0
                it[used_log_bytes] = 0
                it[used_llm_bytes] = 0
                it[updated_at] = now
            }

            testOrgId = orgId
        }

        val service = BillingBackgroundService()
        invokeQuotaNotificationPass(service)

        transaction {
            val types =
                QuotaNotificationsSent
                    .selectAll()
                    .where { QuotaNotificationsSent.organization_id eq testOrgId }
                    .map { it[QuotaNotificationsSent.notification_type] }
                    .toSet()

            assertTrue(types.isEmpty(), "No base notifications expected when GB-eligible bytes are under 80%")
        }
    }

    @Test
    fun `byte-based PAYG triggers payg_80 for GB-primary tier`() {
        transaction {
            TestDatabaseHelper.resetSchema(
                Users,
                Organizations,
                Memberships,
                Subscriptions,
                EmailsSent,
                PricingTierConfigs,
                OrgUsageCounters,
                QuotaNotificationsSent
            )

            val orgId =
                Organizations.insert {
                    it[name] = "PAYG GB Org"
                    it[slug] = "payg-gb-org"
                }[Organizations.id]

            val ownerId =
                Users.insert {
                    it[email] = "payg-gb-owner@moneat.test"
                    it[password_hash] = "hash"
                    it[name] = "PAYG Owner"
                }[Users.id]

            Memberships.insert {
                it[user_id] = ownerId
                it[organization_id] = orgId
                it[role] = "owner"
            }

            val gbLimit = 1_073_741_824L // 1 GB
            val overageRateCentsPerGb = 40
            val paygBudgetCents = 4000 // $40 = 100 GB of PAYG capacity
            val tierId =
                PricingTierConfigs.insert {
                    it[tier_name] = "PRO_PAYG_GB"
                    it[version] = 1
                    it[monthly_unit_limit] = 0
                    it[monthly_error_limit] = 0
                    it[monthly_transaction_limit] = 0
                    it[monthly_replay_limit] = 0
                    it[monthly_feedback_limit] = 0
                    it[monthly_llm_event_limit] = 0
                    it[monthly_gb_limit] = gbLimit
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
                    it[payg_rate_micros_per_unit] = 0
                    it[PricingTierConfigs.overage_rate_cents_per_gb] = overageRateCentsPerGb
                    it[error_overage_rate_cents_per_1k] = 0
                    it[replay_overage_rate_cents_per_gb] = 0
                    it[llm_overage_rate_cents_per_1k] = 0
                    it[oncall_per_user_monthly_cents] = 0
                    it[oncall_per_user_yearly_cents] = 0
                    it[oncall_enabled] = false
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
                it[organization_id] = orgId
                it[plan] = "PRO_PAYG_GB"
                it[status] = "active"
                it[billing_interval] = "monthly"
                it[current_period_start] = now
                it[current_period_end] = Instant.fromEpochSeconds(now.epochSeconds + 2_592_000)
                it[pricing_tier_config_id] = tierId
                it[Subscriptions.payg_budget_cents] = paygBudgetCents
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
            }

            // paygLimitBytes = (4000 * 1_073_741_824) / 40 = 107_374_182_400 (~100 GB)
            // GB-eligible overage = usedBytes - bytesLimit = 90 GB over base
            // 90 GB / 100 GB PAYG capacity = 90% > 80% threshold
            val usedBytes = gbLimit + 90L * gbLimit
            OrgUsageCounters.insert {
                it[organization_id] = orgId
                it[period_start] = periodStart
                it[period_end] = periodEnd
                it[used_units] = 0
                it[used_errors] = 0
                it[used_transactions] = 0
                it[used_replays] = 0
                it[used_feedback] = 0
                it[used_llm_events] = 0
                it[used_logs] = 0
                it[used_bytes] = usedBytes
                it[used_error_bytes] = usedBytes
                it[used_replay_bytes] = 0
                it[used_log_bytes] = 0
                it[used_llm_bytes] = 0
                it[updated_at] = now
            }

            testOrgId = orgId
        }

        val service = BillingBackgroundService()
        invokeQuotaNotificationPass(service)

        transaction {
            val types =
                QuotaNotificationsSent
                    .selectAll()
                    .where { QuotaNotificationsSent.organization_id eq testOrgId }
                    .map { it[QuotaNotificationsSent.notification_type] }
                    .toSet()

            assertTrue("base_80" in types, "Expected base_80 (base limit exceeded)")
            assertTrue("base_90" in types, "Expected base_90 (base limit exceeded)")
            assertTrue("base_100" in types, "Expected base_100 (base limit exceeded)")
            assertTrue("payg_80" in types, "Expected payg_80 for byte-based PAYG budget")
        }
    }

    @Test
    fun `unified tier with Long MAX_VALUE unit limit triggers notifications from bytes`() {
        transaction {
            TestDatabaseHelper.resetSchema(
                Users,
                Organizations,
                Memberships,
                Subscriptions,
                EmailsSent,
                PricingTierConfigs,
                OrgUsageCounters,
                QuotaNotificationsSent
            )

            val orgId =
                Organizations.insert {
                    it[name] = "Unified Org"
                    it[slug] = "unified-org"
                }[Organizations.id]

            val ownerId =
                Users.insert {
                    it[email] = "unified-owner@moneat.test"
                    it[password_hash] = "hash"
                    it[name] = "Unified Owner"
                }[Users.id]

            Memberships.insert {
                it[user_id] = ownerId
                it[organization_id] = orgId
                it[role] = "owner"
            }

            val gbLimit = 53_687_091_200L // 50 GB
            val tierId =
                PricingTierConfigs.insert {
                    it[tier_name] = "PRO"
                    it[version] = 3
                    it[monthly_unit_limit] = Long.MAX_VALUE
                    it[monthly_error_limit] = Long.MAX_VALUE
                    it[monthly_transaction_limit] = Long.MAX_VALUE
                    it[monthly_replay_limit] = Long.MAX_VALUE
                    it[monthly_feedback_limit] = Long.MAX_VALUE
                    it[monthly_llm_event_limit] = Long.MAX_VALUE
                    it[monthly_gb_limit] = gbLimit
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
                    it[payg_enabled] = false
                    it[payg_rate_micros_per_unit] = 0
                    it[overage_rate_cents_per_gb] = 0
                    it[error_overage_rate_cents_per_1k] = 0
                    it[replay_overage_rate_cents_per_gb] = 0
                    it[llm_overage_rate_cents_per_1k] = 0
                    it[oncall_per_user_monthly_cents] = 0
                    it[oncall_per_user_yearly_cents] = 0
                    it[oncall_enabled] = false
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
                it[organization_id] = orgId
                it[plan] = "PRO"
                it[status] = "active"
                it[billing_interval] = "monthly"
                it[current_period_start] = now
                it[current_period_end] = Instant.fromEpochSeconds(now.epochSeconds + 2_592_000)
                it[pricing_tier_config_id] = tierId
                it[payg_budget_cents] = 0
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
            }

            // ~382 GB used against a 50 GB limit (mirrors production scenario)
            val usedBytes = 410_572_431_360L
            OrgUsageCounters.insert {
                it[organization_id] = orgId
                it[period_start] = periodStart
                it[period_end] = periodEnd
                it[used_units] = 122_082
                it[used_errors] = 50_000
                it[used_transactions] = 72_082
                it[used_replays] = 0
                it[used_feedback] = 0
                it[used_llm_events] = 0
                it[used_logs] = 0
                it[used_bytes] = usedBytes
                it[used_error_bytes] = usedBytes / 2
                it[used_replay_bytes] = 0
                it[used_log_bytes] = 0
                it[used_llm_bytes] = 0
                it[updated_at] = now
            }

            testOrgId = orgId
        }

        val service = BillingBackgroundService()
        invokeQuotaNotificationPass(service)

        transaction {
            val types =
                QuotaNotificationsSent
                    .selectAll()
                    .where { QuotaNotificationsSent.organization_id eq testOrgId }
                    .map { it[QuotaNotificationsSent.notification_type] }
                    .toSet()

            assertTrue(
                "base_80" in types,
                "Expected base_80 notification when bytes exceed 80% of GB limit (unit limit is Long.MAX_VALUE)"
            )
            assertTrue(
                "base_90" in types,
                "Expected base_90 notification when bytes exceed 90% of GB limit (unit limit is Long.MAX_VALUE)"
            )
            assertTrue(
                "base_100" in types,
                "Expected base_100 notification when bytes exceed GB limit (unit limit is Long.MAX_VALUE)"
            )
        }
    }

    @Test
    fun `only base_80 fires when base usage is exactly 80 percent`() {
        transaction {
            TestDatabaseHelper.resetSchema(
                Users,
                Organizations,
                Memberships,
                Subscriptions,
                EmailsSent,
                PricingTierConfigs,
                OrgUsageCounters,
                QuotaNotificationsSent
            )

            val orgId =
                Organizations.insert {
                    it[name] = "80pct Org"
                    it[slug] = "80pct-org"
                }[Organizations.id]

            val ownerId =
                Users.insert {
                    it[email] = "owner-80@moneat.test"
                    it[password_hash] = "hash"
                    it[name] = "Owner 80"
                }[Users.id]

            Memberships.insert {
                it[user_id] = ownerId
                it[organization_id] = orgId
                it[role] = "owner"
            }

            val tierId =
                PricingTierConfigs.insert {
                    it[tier_name] = "PRO_80"
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
                    it[payg_enabled] = false
                    it[payg_rate_micros_per_unit] = 0
                    it[overage_rate_cents_per_gb] = 0
                    it[error_overage_rate_cents_per_1k] = 0
                    it[replay_overage_rate_cents_per_gb] = 0
                    it[llm_overage_rate_cents_per_1k] = 0
                    it[oncall_per_user_monthly_cents] = 0
                    it[oncall_per_user_yearly_cents] = 0
                    it[oncall_enabled] = false
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
                it[organization_id] = orgId
                it[plan] = "PRO_80"
                it[status] = "active"
                it[billing_interval] = "monthly"
                it[current_period_start] = now
                it[current_period_end] = Instant.fromEpochSeconds(now.epochSeconds + 2_592_000)
                it[pricing_tier_config_id] = tierId
                it[payg_budget_cents] = 0
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
            }

            // used_units = 800 is exactly 80% of 1000 limit
            OrgUsageCounters.insert {
                it[organization_id] = orgId
                it[period_start] = periodStart
                it[period_end] = periodEnd
                it[used_units] = 800
                it[used_errors] = 800
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

            testOrgId = orgId
        }

        val service = BillingBackgroundService()
        invokeQuotaNotificationPass(service)

        transaction {
            val types =
                QuotaNotificationsSent
                    .selectAll()
                    .where { QuotaNotificationsSent.organization_id eq testOrgId }
                    .map { it[QuotaNotificationsSent.notification_type] }
                    .toSet()

            assertTrue("base_80" in types, "Expected base_80 to fire at exactly 80% usage")
            assertTrue("base_90" !in types, "Expected base_90 NOT to fire at exactly 80% usage")
            assertTrue("base_100" !in types, "Expected base_100 NOT to fire at exactly 80% usage")
        }
    }

    @Test
    fun `base_80 and base_90 both fire when base usage is exactly 90 percent`() {
        transaction {
            TestDatabaseHelper.resetSchema(
                Users,
                Organizations,
                Memberships,
                Subscriptions,
                EmailsSent,
                PricingTierConfigs,
                OrgUsageCounters,
                QuotaNotificationsSent
            )

            val orgId =
                Organizations.insert {
                    it[name] = "90pct Org"
                    it[slug] = "90pct-org"
                }[Organizations.id]

            val ownerId =
                Users.insert {
                    it[email] = "owner-90@moneat.test"
                    it[password_hash] = "hash"
                    it[name] = "Owner 90"
                }[Users.id]

            Memberships.insert {
                it[user_id] = ownerId
                it[organization_id] = orgId
                it[role] = "owner"
            }

            val tierId =
                PricingTierConfigs.insert {
                    it[tier_name] = "PRO_90"
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
                    it[payg_enabled] = false
                    it[payg_rate_micros_per_unit] = 0
                    it[overage_rate_cents_per_gb] = 0
                    it[error_overage_rate_cents_per_1k] = 0
                    it[replay_overage_rate_cents_per_gb] = 0
                    it[llm_overage_rate_cents_per_1k] = 0
                    it[oncall_per_user_monthly_cents] = 0
                    it[oncall_per_user_yearly_cents] = 0
                    it[oncall_enabled] = false
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
                it[organization_id] = orgId
                it[plan] = "PRO_90"
                it[status] = "active"
                it[billing_interval] = "monthly"
                it[current_period_start] = now
                it[current_period_end] = Instant.fromEpochSeconds(now.epochSeconds + 2_592_000)
                it[pricing_tier_config_id] = tierId
                it[payg_budget_cents] = 0
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
            }

            // used_units = 900 is exactly 90% of 1000 limit
            OrgUsageCounters.insert {
                it[organization_id] = orgId
                it[period_start] = periodStart
                it[period_end] = periodEnd
                it[used_units] = 900
                it[used_errors] = 900
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

            testOrgId = orgId
        }

        val service = BillingBackgroundService()
        invokeQuotaNotificationPass(service)

        transaction {
            val types =
                QuotaNotificationsSent
                    .selectAll()
                    .where { QuotaNotificationsSent.organization_id eq testOrgId }
                    .map { it[QuotaNotificationsSent.notification_type] }
                    .toSet()

            // At 90%: both base_80 and base_90 fire because each threshold is checked independently
            assertTrue("base_80" in types, "Expected base_80 to fire at exactly 90% usage")
            assertTrue("base_90" in types, "Expected base_90 to fire at exactly 90% usage")
            assertTrue("base_100" !in types, "Expected base_100 NOT to fire at exactly 90% usage")
        }
    }

    @Test
    fun `no base notifications fire when base usage is below 80 percent`() {
        transaction {
            TestDatabaseHelper.resetSchema(
                Users,
                Organizations,
                Memberships,
                Subscriptions,
                EmailsSent,
                PricingTierConfigs,
                OrgUsageCounters,
                QuotaNotificationsSent
            )

            val orgId =
                Organizations.insert {
                    it[name] = "Low Usage Org"
                    it[slug] = "low-usage-org"
                }[Organizations.id]

            val ownerId =
                Users.insert {
                    it[email] = "owner-low@moneat.test"
                    it[password_hash] = "hash"
                    it[name] = "Owner Low"
                }[Users.id]

            Memberships.insert {
                it[user_id] = ownerId
                it[organization_id] = orgId
                it[role] = "owner"
            }

            val tierId =
                PricingTierConfigs.insert {
                    it[tier_name] = "PRO_LOW"
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
                    it[payg_enabled] = false
                    it[payg_rate_micros_per_unit] = 0
                    it[overage_rate_cents_per_gb] = 0
                    it[error_overage_rate_cents_per_1k] = 0
                    it[replay_overage_rate_cents_per_gb] = 0
                    it[llm_overage_rate_cents_per_1k] = 0
                    it[oncall_per_user_monthly_cents] = 0
                    it[oncall_per_user_yearly_cents] = 0
                    it[oncall_enabled] = false
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
                it[organization_id] = orgId
                it[plan] = "PRO_LOW"
                it[status] = "active"
                it[billing_interval] = "monthly"
                it[current_period_start] = now
                it[current_period_end] = Instant.fromEpochSeconds(now.epochSeconds + 2_592_000)
                it[pricing_tier_config_id] = tierId
                it[payg_budget_cents] = 0
                it[payg_used_units] = 0
                it[payg_used_micros] = 0
                it[pending_meter_units] = 0
            }

            // used_units = 500 is 50% of 1000 — below the 80% warning threshold
            OrgUsageCounters.insert {
                it[organization_id] = orgId
                it[period_start] = periodStart
                it[period_end] = periodEnd
                it[used_units] = 500
                it[used_errors] = 500
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

            testOrgId = orgId
        }

        val service = BillingBackgroundService()
        invokeQuotaNotificationPass(service)

        transaction {
            val types =
                QuotaNotificationsSent
                    .selectAll()
                    .where { QuotaNotificationsSent.organization_id eq testOrgId }
                    .map { it[QuotaNotificationsSent.notification_type] }
                    .toSet()

            assertTrue(
                types.none { it.startsWith("base_") },
                "Expected no base notifications when usage is below 80%, but got: $types"
            )
        }
    }

    private fun invokeQuotaNotificationPass(service: BillingBackgroundService) {
        val method = BillingBackgroundService::class.java.getDeclaredMethod("processQuotaThresholdNotifications")
        method.isAccessible = true
        method.invoke(service)
    }
}
