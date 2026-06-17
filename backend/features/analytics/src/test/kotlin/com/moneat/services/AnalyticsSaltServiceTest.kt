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

import com.moneat.shared.services.AnalyticsSaltService
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AnalyticsSaltService returns a daily salt for analytics session ID generation.
 * When Redis is unavailable (typical in unit tests), it falls back to returning
 * the date string as the salt. This test verifies that fallback behavior.
 */
class AnalyticsSaltServiceTest {

    @Test
    fun `getDailySalt returns date string when Redis unavailable`() {
        // In unit tests Redis is typically not configured, so getDailySalt
        // catches the exception and returns LocalDate.now().toString()
        val salt = AnalyticsSaltService.getDailySalt()

        assertEquals(LocalDate.now().toString(), salt)
    }

    @Test
    fun `getDailySalt returns non-empty string`() {
        val salt = AnalyticsSaltService.getDailySalt()

        assertTrue(salt.isNotBlank())
    }

    @Test
    fun `getDailySalt returns valid date format when Redis unavailable`() {
        val salt = AnalyticsSaltService.getDailySalt()

        // Should match ISO date format YYYY-MM-DD
        val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        assertTrue(dateRegex.matches(salt), "Salt should be in date format YYYY-MM-DD, got: $salt")
    }
}
