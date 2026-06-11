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

import com.moneat.billing.models.PricingTierConfigResponse
import com.moneat.billing.services.EffectiveTierContext
import com.moneat.billing.services.EntitlementService
import com.moneat.billing.services.FeatureNotAvailableException
import com.moneat.billing.services.PricingTierService
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntitlementServiceTest {
    @Test
    fun `isFeatureEnabled returns tier feature flag`() {
        assertTrue(entitlementService(slackEnabled = true).isFeatureEnabled(1) { it.slackEnabled })
        assertFalse(entitlementService(slackEnabled = false).isFeatureEnabled(1) { it.slackEnabled })
    }

    @Test
    fun `unavailableFeatureMessage returns null when feature is enabled`() {
        val message = entitlementService(slackEnabled = true).unavailableFeatureMessage(
            organizationId = 1,
            featureCheck = { it.slackEnabled },
            featureName = "Slack integration"
        )

        assertNull(message)
    }

    @Test
    fun `unavailableFeatureMessage returns plan message when feature is disabled`() {
        val message = entitlementService(slackEnabled = false).unavailableFeatureMessage(
            organizationId = 1,
            featureCheck = { it.slackEnabled },
            featureName = "Slack integration"
        )

        assertEquals("Slack integration is not available on your current plan", message)
    }

    @Test
    fun `requireFeatureEnabled throws when feature is disabled`() {
        val service = entitlementService(slackEnabled = false)

        val error = assertFailsWith<FeatureNotAvailableException> {
            service.requireFeatureEnabled(1, { it.slackEnabled }, "Slack integration")
        }

        assertEquals("Slack integration is not available on your current plan", error.message)
    }

    private fun entitlementService(slackEnabled: Boolean): EntitlementService {
        val pricingTierService = mockk<PricingTierService>()
        every { pricingTierService.getEffectiveTierForOrganization(1) } returns EffectiveTierContext(
            tier = tier(slackEnabled),
            subscriptionId = null,
            subscriptionStatus = "active",
            paygBudgetCents = 0,
            paygUsedUnits = 0,
            paygUsedMicros = 0,
            pendingMeterUnits = 0,
            currentPeriodStart = null,
            currentPeriodEnd = null
        )
        return EntitlementService(pricingTierService)
    }

    private fun tier(slackEnabled: Boolean): PricingTierConfigResponse {
        return PricingTierConfigResponse(
            id = "00000000-0000-0000-0000-000000000001",
            tierName = "TEST",
            version = 1,
            monthlyUnitLimit = 1_000,
            monthlyErrorLimit = 1_000,
            monthlyTransactionLimit = 1_000,
            monthlyReplayLimit = 1_000,
            monthlyFeedbackLimit = 1_000,
            monthlyLlmEventLimit = 1_000,
            monthlyGbLimit = 1,
            retentionDays = 30,
            logRetentionDays = 30,
            replayRetentionDays = 30,
            llmRetentionDays = 30,
            apmTraceRetentionDays = 30,
            statusPagesEnabled = true,
            statusPageCustomDomainEnabled = true,
            sessionReplayEnabled = true,
            slackEnabled = slackEnabled,
            discordEnabled = true,
            incidentIoEnabled = true,
            samlEnabled = false,
            oidcEnabled = false,
            prioritySupportEnabled = false,
            slaEnabled = false,
            customRetentionEnabled = false,
            maxProjects = null,
            maxSystems = 10,
            monitorIntervalSeconds = 60,
            monthlyPriceCents = 0,
            yearlyPriceCents = 0,
            trialDays = 14,
            paygEnabled = false,
            paygRateMicrosPerUnit = 0,
            overageRateCentsPerGb = 0,
            isCurrent = true
        )
    }
}
