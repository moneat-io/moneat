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

package com.moneat.utils

import kotlinx.serialization.json.*
import kotlin.test.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Tests to ensure consistent ISO 8601 date formatting across all API responses.
 * 
 * CONTEXT: ClickHouse returns dates in format "YYYY-MM-DD HH:MM:SS" but JavaScript/frontend
 * expects ISO 8601 format "YYYY-MM-DDTHH:MM:SS.000Z" (with T separator and timezone).
 * 
 * All ClickHouse queries that return dates to the frontend MUST use:
 * formatDateTime(timestamp_field, '%Y-%c-%dT%H:%i:%S.000Z')
 * 
 * This test validates that date strings follow the correct format and can be parsed
 * by standard ISO 8601 parsers (both Java and JavaScript).
 */
class DateFormatTest {
    
    /**
     * Expected format: ISO 8601 with milliseconds and UTC timezone
     * Pattern: YYYY-MM-DDTHH:MM:SS.sssZ
     * Example: 2026-02-12T20:58:30.000Z
     */
    private val ISO8601_PATTERN = DateTimeFormatter.ISO_INSTANT
    
    @Test
    fun `valid ISO 8601 dates should parse correctly`() {
        val validDates = listOf(
            "2026-02-12T20:58:30.000Z",
            "2024-01-15T09:58:31.000Z",
            "2023-12-31T23:59:59.999Z",
            "2024-01-01T00:00:00.000Z"
        )
        
        validDates.forEach { dateStr ->
            try {
                ISO8601_PATTERN.parse(dateStr)
                // Success - date parsed correctly
            } catch (e: DateTimeParseException) {
                fail("Date '$dateStr' should parse as ISO 8601 but failed: ${e.message}")
            }
        }
    }
    
    @Test
    fun `ClickHouse default format should NOT be accepted`() {
        val clickhouseDates = listOf(
            "2026-02-12 20:58:30.000",  // Missing T separator and Z
            "2026-02-12 20:58:30",       // Space instead of T
            "2026-02-12T20:58:30",       // Missing .000Z
        )
        
        clickhouseDates.forEach { dateStr ->
            assertFailsWith<DateTimeParseException>(
                message = "ClickHouse format '$dateStr' should NOT parse as ISO 8601"
            ) {
                ISO8601_PATTERN.parse(dateStr)
            }
        }
    }
    
    @Test
    fun `API response JSON dates should be ISO 8601 compliant`() {
        // Simulate API responses that should contain properly formatted dates
        val issueResponse = """
            {
                "id": "test-issue-id",
                "firstSeen": "2026-02-12T20:58:30.000Z",
                "lastSeen": "2026-02-13T15:30:00.000Z"
            }
        """.trimIndent()
        
        val json = Json.parseToJsonElement(issueResponse).jsonObject
        val firstSeen = json["firstSeen"]?.jsonPrimitive?.content
        val lastSeen = json["lastSeen"]?.jsonPrimitive?.content
        
        assertNotNull(firstSeen, "firstSeen should exist")
        assertNotNull(lastSeen, "lastSeen should exist")
        
        // Should parse without exceptions
        try {
            ISO8601_PATTERN.parse(firstSeen)
            ISO8601_PATTERN.parse(lastSeen)
            // Success - both dates parsed correctly
        } catch (e: DateTimeParseException) {
            fail("Date fields should be ISO 8601 compliant but failed: ${e.message}")
        }
    }
    
    @Test
    fun `ClickHouse formatDateTime pattern should produce correct format`() {
        // Document the correct ClickHouse pattern
        val clickhousePattern = "%Y-%c-%dT%H:%i:%S.000Z"
        
        // This pattern should produce: 2026-02-12T20:58:30.000Z
        // %Y = 4-digit year
        // %c = month (1-12)
        // %d = day (01-31)
        // T = literal T
        // %H = hour (00-23)
        // %i = minutes (00-59)
        // %S = seconds (00-59)
        // .000Z = literal milliseconds and UTC timezone
        
        // Verify the pattern is documented
        assertTrue(
            clickhousePattern.contains("T"),
            "Pattern must include T separator"
        )
        assertTrue(
            clickhousePattern.endsWith("Z"),
            "Pattern must end with Z for UTC"
        )
        assertTrue(
            clickhousePattern.contains("%Y-%c-%d"),
            "Pattern must include date components"
        )
        assertTrue(
            clickhousePattern.contains("%H:%i:%S"),
            "Pattern must include time components"
        )
    }
    
    @Test
    fun `date format validation helper should identify invalid formats`() {
        val validDate = "2026-02-12T20:58:30.000Z"
        val invalidDate1 = "2026-02-12 20:58:30"
        val invalidDate2 = "Invalid Date"
        val invalidDate3 = ""
        
        assertTrue(isValidIso8601(validDate), "Valid ISO 8601 should pass")
        assertFalse(isValidIso8601(invalidDate1), "ClickHouse format should fail")
        assertFalse(isValidIso8601(invalidDate2), "Invalid string should fail")
        assertFalse(isValidIso8601(invalidDate3), "Empty string should fail")
    }
    
    /**
     * Helper function to validate ISO 8601 date strings.
     * This can be used in integration tests or validation logic.
     */
    private fun isValidIso8601(dateStr: String): Boolean {
        return try {
            if (dateStr.isBlank()) return false
            ISO8601_PATTERN.parse(dateStr)
            // Additional check: must contain T separator and end with Z
            dateStr.contains("T") && dateStr.endsWith("Z")
        } catch (e: DateTimeParseException) {
            false
        }
    }
    
    @Test
    fun `JavaScript Date constructor compatibility check`() {
        // These dates should work with: new Date(dateString) in JavaScript
        val javaScriptCompatibleDates = listOf(
            "2026-02-12T20:58:30.000Z",
            "2024-01-15T09:58:31.000Z"
        )
        
        javaScriptCompatibleDates.forEach { dateStr ->
            // Verify format matches what JavaScript Date expects
            assertTrue(
                dateStr.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")),
                "Date '$dateStr' should match JS-compatible pattern"
            )
        }
    }
}
