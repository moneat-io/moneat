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

import com.moneat.logs.repositories.LogRepository
import com.moneat.logs.services.LogService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogServiceBuildTagConditionTest {
    private companion object {
        private const val US_EAST = "us-east"
        private const val HAS_TAGS_REGION = "has(tags, 'region')"
    }

    private fun newService(): LogService = LogService(FakeLogRepository())

    @Test
    fun `buildTagCondition returns empty for blank key`() {
        val service = newService()
        assertEquals("", service.buildTagCondition("", "value"))
    }

    @Test
    fun `buildTagCondition handles top-level field`() {
        val service = newService()
        val result = service.buildTagCondition("service", "api")
        assertEquals("service = 'api'", result)
    }

    @Test
    fun `buildTagCondition handles top-level field with exclude`() {
        val service = newService()
        val result = service.buildTagCondition("service", "api", exclude = true)
        assertEquals("service != 'api'", result)
    }

    @Test
    fun `buildTagCondition maps status to level with toString`() {
        val service = newService()
        val result = service.buildTagCondition("status", "error")
        assertEquals("toString(level) = 'error'", result)
    }

    @Test
    fun `buildTagCondition handles actual tag key`() {
        val service = newService()
        val result = service.buildTagCondition("region", US_EAST)
        assertTrue(result.contains(HAS_TAGS_REGION))
        assertTrue(result.contains("tags['region'] = '$US_EAST'"))
    }

    @Test
    fun `buildTagCondition handles actual tag key with exclude`() {
        val service = newService()
        val result = service.buildTagCondition("region", US_EAST, exclude = true)
        assertTrue(result.startsWith("NOT"))
        assertTrue(result.contains(HAS_TAGS_REGION))
    }

    @Test
    fun `buildTagCondition handles enum field source with toString`() {
        val service = newService()
        val result = service.buildTagCondition("source", "sdk")
        assertEquals("toString(source) = 'sdk'", result)
    }

    @Test
    fun `buildTagCondition parses malformed tag with boolean operators`() {
        val service = newService()
        val result = service.buildTagCondition("service", "api OR worker")
        assertTrue(result.isNotBlank())
    }

    @Test
    fun `buildTagCondition parses tag key starting with dash as query`() {
        val service = newService()
        val result = service.buildTagCondition("-service", "api")
        assertTrue(result.isNotBlank())
    }

    private class FakeLogRepository : LogRepository {
        override suspend fun executeClickHouseInsert(sql: String): Boolean = true
        override suspend fun executeClickHouseQuery(sql: String): String = ""
        override suspend fun executeClickHouseQuery(
            sql: String,
            queryParameters: Map<String, String>
        ): String = executeClickHouseQuery(sql)
    }
}
