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

import com.moneat.billing.models.NativeIncidentUsageCounters
import com.moneat.billing.models.NativeIncidentUsageEvents
import com.moneat.billing.models.NativeIncidentPlanEntitlements
import com.moneat.billing.models.PricingTierConfigResponse
import com.moneat.enterprise.NativeIncidentQuotaKey
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.TestDatabaseHelper
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BillingNativeIncidentEntitlementBridgeTest {
    private lateinit var service: BillingNativeIncidentEntitlementBridge
    private var organizationId: Int = 0

    @BeforeTest
    fun setup() {
        TransactionManager.defaultDatabase = Database.connect(
            url = "jdbc:h2:mem:native_incident_entitlements_${UUID.randomUUID()};" +
                "MODE=MYSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        TestDatabaseHelper.resetSchemaForH2WithJsonb(
            NativeIncidentPlanEntitlements,
            NativeIncidentUsageEvents,
            NativeIncidentUsageCounters,
        )
        organizationId = transaction {
            Organizations.insert {
                it[name] = "Incident quota test"
                it[slug] = "incident-quota-test"
            } get Organizations.id
        }
        val tier = mockk<PricingTierConfigResponse>()
        every { tier.tierName } returns "PRO"
        transaction {
            NativeIncidentPlanEntitlements.insert {
                it[tierName] = "PRO"
                it[enabled] = true
                it[quotas] = """{"native_incidents":2}"""
            }
        }
        val pricingTierService = mockk<PricingTierService>()
        every { pricingTierService.getEffectiveTierForOrganization(organizationId) } returns
            EffectiveTierContext(
                tier = tier,
                subscriptionId = null,
                subscriptionStatus = "active",
                paygBudgetCents = 0,
                paygUsedUnits = 0,
                paygUsedMicros = 0,
                pendingMeterUnits = 0,
                currentPeriodStart = null,
                currentPeriodEnd = null,
            )
        service = BillingNativeIncidentEntitlementBridge(pricingTierService)
    }

    @Test
    fun `usage admission is organization scoped idempotent and quota bounded`() {
        val first = service.consume(organizationId, NativeIncidentQuotaKey.NATIVE_INCIDENTS, 1, "declare-1")
        val replay = service.consume(organizationId, NativeIncidentQuotaKey.NATIVE_INCIDENTS, 1, "declare-1")
        val second = service.consume(organizationId, NativeIncidentQuotaKey.NATIVE_INCIDENTS, 1, "declare-2")
        val denied = service.consume(organizationId, NativeIncidentQuotaKey.NATIVE_INCIDENTS, 1, "declare-3")

        assertTrue(first.allowed)
        assertTrue(replay.allowed)
        assertTrue(replay.replayed)
        assertTrue(second.allowed)
        assertFalse(denied.allowed)
        assertEquals(2, denied.status.used)
        assertEquals(2, denied.status.limit)
        transaction {
            assertEquals(2, NativeIncidentUsageEvents.selectAll().count())
            assertEquals(2, NativeIncidentUsageCounters.selectAll().single()[NativeIncidentUsageCounters.used])
        }
    }

    @Test
    fun `reconciliation repairs usage and a previously denied retry can proceed`() {
        service.consume(organizationId, NativeIncidentQuotaKey.NATIVE_INCIDENTS, 1, "declare-1")
        service.consume(organizationId, NativeIncidentQuotaKey.NATIVE_INCIDENTS, 1, "declare-2")
        assertFalse(
            service.consume(
                organizationId,
                NativeIncidentQuotaKey.NATIVE_INCIDENTS,
                1,
                "declare-after-reconcile",
            ).allowed,
        )

        val reconciled = service.reconcile(
            organizationId,
            NativeIncidentQuotaKey.NATIVE_INCIDENTS,
            authoritativeUsage = 1,
            idempotencyKey = "daily-count",
        )
        val admitted = service.consume(
            organizationId,
            NativeIncidentQuotaKey.NATIVE_INCIDENTS,
            1,
            "declare-after-reconcile",
        )

        assertTrue(reconciled.allowed)
        assertEquals(1, reconciled.status.used)
        assertTrue(admitted.allowed)
        assertEquals(2, admitted.status.used)
        assertEquals(
            2,
            service.status(organizationId).quotas.getValue(NativeIncidentQuotaKey.NATIVE_INCIDENTS).used,
        )
    }

    @Test
    fun `reconciliation records authoritative overage without rejecting the repair`() {
        val reconciled = service.reconcile(
            organizationId,
            NativeIncidentQuotaKey.NATIVE_INCIDENTS,
            authoritativeUsage = 3,
            idempotencyKey = "over-limit-count",
        )
        val replay = service.reconcile(
            organizationId,
            NativeIncidentQuotaKey.NATIVE_INCIDENTS,
            authoritativeUsage = 3,
            idempotencyKey = "over-limit-count",
        )

        assertTrue(reconciled.allowed)
        assertTrue(reconciled.status.exhausted)
        assertEquals(3, reconciled.status.used)
        assertTrue(replay.allowed)
        assertTrue(replay.replayed)
    }

    @Test
    fun `reconciliation applies a newer authoritative value when the retry key is reused`() {
        val first = service.reconcile(
            organizationId,
            NativeIncidentQuotaKey.NATIVE_INCIDENTS,
            authoritativeUsage = 1,
            idempotencyKey = "periodic-count",
        )
        val updated = service.reconcile(
            organizationId,
            NativeIncidentQuotaKey.NATIVE_INCIDENTS,
            authoritativeUsage = 2,
            idempotencyKey = "periodic-count",
        )
        val replay = service.reconcile(
            organizationId,
            NativeIncidentQuotaKey.NATIVE_INCIDENTS,
            authoritativeUsage = 2,
            idempotencyKey = "periodic-count",
        )

        assertEquals(1, first.status.used)
        assertEquals(2, updated.status.used)
        assertFalse(updated.replayed)
        assertTrue(replay.replayed)
    }
}
