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

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildTagConditionTest {

    @Test
    fun `buildTagCondition should handle top-level field 'service'`() {
        val service = LogService()
        val condition = service.buildTagCondition("service", "auth-service")

        assertEquals("service = 'auth-service'", condition)
        assertTrue(!condition.contains("has("))
        assertTrue(!condition.contains("tags["))
    }

    @Test
    fun `buildTagCondition should handle top-level field 'host'`() {
        val service = LogService()
        val condition = service.buildTagCondition("host", "api-prod-3")

        assertEquals("host = 'api-prod-3'", condition)
        assertTrue(!condition.contains("has("))
        assertTrue(!condition.contains("tags["))
    }

    @Test
    fun `buildTagCondition should handle top-level field 'environment'`() {
        val service = LogService()
        val condition = service.buildTagCondition("environment", "production")

        assertEquals("environment = 'production'", condition)
        assertTrue(!condition.contains("has("))
        assertTrue(!condition.contains("tags["))
    }

    @Test
    fun `buildTagCondition should map 'status' to 'level' field`() {
        val service = LogService()
        val condition = service.buildTagCondition("status", "error")

        assertEquals("toString(level) = 'error'", condition)
        assertTrue(!condition.contains("has("))
        assertTrue(!condition.contains("tags["))
    }

    @Test
    fun `buildTagCondition should handle custom tag (not top-level field)`() {
        val service = LogService()
        val condition = service.buildTagCondition("custom_tag", "custom_value")

        assertTrue(condition.contains("has(tags, 'custom_tag')"))
        assertTrue(condition.contains("tags['custom_tag'] = 'custom_value'"))
    }

    @Test
    fun `buildTagCondition should handle SQL injection in top-level field value`() {
        val service = LogService()
        val condition = service.buildTagCondition("service", "auth'; DROP TABLE users; --")

        // Should escape the single quote
        assertTrue(condition.contains("auth\\'; DROP TABLE users; --"))
        assertEquals("service = 'auth\\'; DROP TABLE users; --'", condition)
    }

    @Test
    fun `buildTagCondition should handle SQL injection in custom tag key`() {
        val service = LogService()
        val condition = service.buildTagCondition("tag'; DROP TABLE users; --", "value")

        // Should escape the single quote in the key
        assertTrue(condition.contains("tag\\'; DROP TABLE users; --"))
    }

    @Test
    fun `buildTagCondition should handle SQL injection in custom tag value`() {
        val service = LogService()
        val condition = service.buildTagCondition("custom_tag", "value'; DROP TABLE users; --")

        // Should escape the single quote in the value
        assertTrue(condition.contains("value\\'; DROP TABLE users; --"))
    }

    @Test
    fun `buildTagCondition should handle empty key`() {
        val service = LogService()
        val condition = service.buildTagCondition("", "value")

        assertEquals("", condition)
    }

    @Test
    fun `buildTagCondition should handle blank key`() {
        val service = LogService()
        val condition = service.buildTagCondition("  ", "value")

        assertEquals("", condition)
    }

    @Test
    fun `buildTagCondition should handle all top-level fields`() {
        val service = LogService()
        val enumFields = setOf("level", "source")
        val topLevelFields = listOf(
            "service", "environment", "host", "source", "level", "message", "body",
            "container_name", "container_id", "container_image", "trace_id", "span_id"
        )

        for (field in topLevelFields) {
            val condition = service.buildTagCondition(field, "test_value")

            // Enum fields should use toString()
            val expected = if (field in enumFields) {
                "toString($field) = 'test_value'"
            } else {
                "$field = 'test_value'"
            }
            assertEquals(expected, condition)

            // Should NOT use has() or tags[]
            assertTrue(!condition.contains("has("))
            assertTrue(!condition.contains("tags["))
        }
    }

    @Test
    fun `buildTagCondition should handle special characters in field names`() {
        val service = LogService()
        val condition = service.buildTagCondition("http.status_code", "200")

        // Not a top-level field, should use tags
        assertTrue(condition.contains("has(tags, 'http.status_code')"))
        assertTrue(condition.contains("tags['http.status_code'] = '200'"))
    }
}
