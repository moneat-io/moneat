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

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BillingPeriodResolverTest {
    // ──── Billing Period Resolver Tests ────
    @Test
    fun `missing stored start falls back to current calendar month`() {
        val period =
            resolveCurrentBillingPeriod(
                storedStart = null,
                storedEnd = null,
                billingInterval = "monthly",
                today = LocalDate(2026, 5, 23),
            )

        assertEquals(LocalDate(2026, 5, 1), period.start)
        assertEquals(LocalDate(2026, 5, 31), period.end)
    }

    @Test
    fun `stale monthly period advances to period containing today`() {
        val period =
            resolveCurrentBillingPeriod(
                storedStart = LocalDate(2026, 3, 14),
                storedEnd = LocalDate(2026, 4, 13),
                billingInterval = "monthly",
                today = LocalDate(2026, 5, 23),
            )

        assertEquals(LocalDate(2026, 5, 14), period.start)
        assertEquals(LocalDate(2026, 6, 13), period.end)
    }

    @Test
    fun `current stored period is preserved`() {
        val period =
            resolveCurrentBillingPeriod(
                storedStart = LocalDate(2026, 5, 14),
                storedEnd = LocalDate(2026, 6, 13),
                billingInterval = "monthly",
                today = LocalDate(2026, 5, 23),
            )

        assertEquals(LocalDate(2026, 5, 14), period.start)
        assertEquals(LocalDate(2026, 6, 13), period.end)
    }

    @Test
    fun `missing stored end derives period end from start`() {
        val period =
            resolveCurrentBillingPeriod(
                storedStart = LocalDate(2026, 3, 14),
                storedEnd = null,
                billingInterval = "monthly",
                today = LocalDate(2026, 3, 20),
            )

        assertEquals(LocalDate(2026, 3, 14), period.start)
        assertEquals(LocalDate(2026, 4, 13), period.end)
    }

    @Test
    fun `invalid stored end derives period end from start`() {
        val period =
            resolveCurrentBillingPeriod(
                storedStart = LocalDate(2026, 3, 14),
                storedEnd = LocalDate(2026, 3, 1),
                billingInterval = "monthly",
                today = LocalDate(2026, 3, 20),
            )

        assertEquals(LocalDate(2026, 3, 14), period.start)
        assertEquals(LocalDate(2026, 4, 13), period.end)
    }

    @Test
    fun `future stored period rewinds to period containing today`() {
        val period =
            resolveCurrentBillingPeriod(
                storedStart = LocalDate(2026, 5, 14),
                storedEnd = LocalDate(2026, 6, 13),
                billingInterval = "monthly",
                today = LocalDate(2026, 4, 20),
            )

        assertEquals(LocalDate(2026, 4, 14), period.start)
        assertEquals(LocalDate(2026, 5, 13), period.end)
    }

    @Test
    fun `month end anchor clamps to last day of shorter month`() {
        val period =
            resolveCurrentBillingPeriod(
                storedStart = LocalDate(2026, 1, 31),
                storedEnd = LocalDate(2026, 2, 27),
                billingInterval = "monthly",
                today = LocalDate(2026, 3, 1),
            )

        assertEquals(LocalDate(2026, 2, 28), period.start)
        assertEquals(LocalDate(2026, 3, 30), period.end)
    }

    @Test
    fun `yearly period advances by yearly interval`() {
        val period =
            resolveCurrentBillingPeriod(
                storedStart = LocalDate(2024, 3, 14),
                storedEnd = LocalDate(2025, 3, 13),
                billingInterval = "yearly",
                today = LocalDate(2026, 5, 23),
            )

        assertEquals(LocalDate(2026, 3, 14), period.start)
        assertEquals(LocalDate(2027, 3, 13), period.end)
    }
}
