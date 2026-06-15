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

import com.moneat.billing.repositories.SubscriptionRepository
import com.moneat.billing.repositories.SubscriptionRepositoryImpl
import com.moneat.billing.services.StripeService
import com.moneat.shared.repositories.OrganizationRepository
import com.moneat.shared.repositories.OrganizationRepositoryImpl
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Customer-facing Stripe API entry points ([StripeService]) call [StripeService.ensureEnabled] before
 * hitting the Stripe SDK. Test resources keep `billing.stripeEnabled = false` and an empty secret key,
 * so integration is off: we assert that contract and safe read-only helpers.
 */
class StripeServiceExtendedTest {

    private val stripeService =
        StripeService(
            subscriptionRepository = SubscriptionRepositoryImpl(),
            organizationRepository = OrganizationRepositoryImpl()
        )

    @Test
    fun `isStripeEnabled is false when billing disabled or secret key blank`() {
        assertFalse(stripeService.isStripeEnabled())
    }

    @Test
    fun `getPublishableKey returns configured value or null`() {
        val key = stripeService.getPublishableKey()
        assertEquals("", key)
    }

    @Test
    fun `createCheckoutSession throws when Stripe disabled`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                stripeService.createCheckoutSession(
                    organizationId = 1,
                    tierName = "PRO",
                    billingInterval = "monthly",
                    successUrl = "https://example.com/success",
                    cancelUrl = "https://example.com/cancel",
                    oncallSeats = 0
                )
            }
        assertEquals("Stripe integration is disabled", ex.message)
    }

    @Test
    fun `listInvoices throws when Stripe disabled`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                stripeService.listInvoices(organizationId = 1)
            }
        assertEquals("Stripe integration is disabled", ex.message)
    }

    @Test
    fun `getPaymentMethod throws when Stripe disabled`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                stripeService.getPaymentMethod(organizationId = 1)
            }
        assertEquals("Stripe integration is disabled", ex.message)
    }

    @Test
    fun `createSetupIntent throws when Stripe disabled`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                stripeService.createSetupIntent(organizationId = 1)
            }
        assertEquals("Stripe integration is disabled", ex.message)
    }

    @Test
    fun `confirmSetupIntentAndUpdatePaymentMethod throws when Stripe disabled`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                stripeService.confirmSetupIntentAndUpdatePaymentMethod(
                    organizationId = 1,
                    setupIntentId = "seti_test"
                )
            }
        assertEquals("Stripe integration is disabled", ex.message)
    }

    @Test
    fun `cancelSubscription by organization throws when Stripe disabled`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                stripeService.cancelSubscription(organizationId = 1)
            }
        assertEquals("Stripe integration is disabled", ex.message)
    }

    @Test
    fun `cancelSubscription by stripe id throws when Stripe disabled`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                stripeService.cancelSubscription("sub_test_123")
            }
        assertEquals("Stripe integration is disabled", ex.message)
    }

    @Test
    fun `updateOnCallSeats throws when Stripe disabled`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                stripeService.updateOnCallSeats(organizationId = 1, seats = 2)
            }
        assertEquals("Stripe integration is disabled", ex.message)
    }

    @Test
    fun `verifyAndParseEvent throws when Stripe disabled`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                stripeService.verifyAndParseEvent("{}", "t=1,v1=ab")
            }
        assertEquals("Stripe integration is disabled", ex.message)
    }

    @Test
    fun `flushPendingMeteredUsage returns zero when Stripe integration is disabled`() {
        assertEquals(0, stripeService.flushPendingMeteredUsage())
    }

    @Test
    fun `ensureEnabled runs before repository access when Stripe disabled`() {
        val subscriptionRepository = mockk<SubscriptionRepository>(relaxed = true)
        val organizationRepository = mockk<OrganizationRepository>(relaxed = true)
        val svc = StripeService(subscriptionRepository, organizationRepository)
        assertFailsWith<IllegalStateException> { svc.listInvoices(organizationId = 42) }
        verify(exactly = 0) { subscriptionRepository.findStripeCustomerIdByOrganizationId(any()) }
    }
}
