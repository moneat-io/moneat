package com.moneat.routes

import com.moneat.models.PricingTierConfigs
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicBillingRoutesTest {
    @BeforeTest
    fun setupDatabase() {
        Database.connect(
            url = "jdbc:h2:mem:moneat_public_billing_${System.nanoTime()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )

        transaction {
            SchemaUtils.create(PricingTierConfigs)
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
                it[payg_enabled] = false
                it[payg_rate_micros_per_unit] = 0
                it[overage_rate_cents_per_gb] = 0
                it[is_current] = true
            }
        }
    }

    @Test
    fun `billing plans endpoint is public and returns feature flags`() = testApplication {
        application {
            routing {
                route("/v1") {
                    publicBillingRoutes()
                }
            }
        }

        val response = client.get("/v1/billing/plans")
        val body = response.bodyAsText()

        assertEquals(200, response.status.value)
        assertTrue(body.contains("\"plans\""))
        assertTrue(body.contains("\"statusPagesEnabled\""))
        assertTrue(body.contains("\"statusPageCustomDomainEnabled\""))
        assertTrue(body.contains("\"sessionReplayEnabled\""))
        assertTrue(body.contains("\"slackEnabled\""))
        assertTrue(body.contains("\"incidentIoEnabled\""))
    }
}
