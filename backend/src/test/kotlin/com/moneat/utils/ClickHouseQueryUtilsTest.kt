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

import kotlin.test.Test
import kotlin.test.assertEquals

class ClickHouseQueryUtilsTest {

    // projectIdClause tests

    @Test
    fun `projectIdClause returns direct comparison for positive ID`() {
        val clause = ClickHouseQueryUtils.projectIdClause(123L)
        assertEquals("project_id = 123", clause)
    }

    @Test
    fun `projectIdClause returns toInt64 cast for negative ID`() {
        val clause = ClickHouseQueryUtils.projectIdClause(-1L)
        assertEquals("toInt64(project_id) = -1", clause)
    }

    @Test
    fun `projectIdClause returns direct comparison for zero ID`() {
        val clause = ClickHouseQueryUtils.projectIdClause(0L)
        assertEquals("project_id = 0", clause)
    }

    @Test
    fun `projectIdClause uses custom column name`() {
        val clause = ClickHouseQueryUtils.projectIdClause(42L, "p_id")
        assertEquals("p_id = 42", clause)
    }

    @Test
    fun `projectIdClause uses custom column name for negative ID`() {
        val clause = ClickHouseQueryUtils.projectIdClause(-2L, "p_id")
        assertEquals("toInt64(p_id) = -2", clause)
    }

    @Test
    fun `projectIdClause handles large positive ID`() {
        val clause = ClickHouseQueryUtils.projectIdClause(999999999L)
        assertEquals("project_id = 999999999", clause)
    }

    // timestampRetentionClause tests

    @Test
    fun `timestampRetentionClause uses now() when no demo epoch`() {
        val clause = ClickHouseQueryUtils.timestampRetentionClause("timestamp", 90)
        assertEquals("timestamp >= now() - INTERVAL 90 DAY", clause)
    }

    @Test
    fun `timestampRetentionClause uses demo epoch when provided`() {
        val demoEpoch = 1700000000000L // in milliseconds
        val clause = ClickHouseQueryUtils.timestampRetentionClause("timestamp", 90, demoEpoch)
        assertEquals("timestamp >= toDateTime64(1.7E9, 3) - INTERVAL 90 DAY", clause)
    }

    @Test
    fun `timestampRetentionClause with custom column name`() {
        val clause = ClickHouseQueryUtils.timestampRetentionClause("created_at", 30)
        assertEquals("created_at >= now() - INTERVAL 30 DAY", clause)
    }

    @Test
    fun `timestampRetentionClause with 1 day retention`() {
        val clause = ClickHouseQueryUtils.timestampRetentionClause("timestamp", 1)
        assertEquals("timestamp >= now() - INTERVAL 1 DAY", clause)
    }

    @Test
    fun `timestampRetentionClause with null demo epoch uses now()`() {
        val clause = ClickHouseQueryUtils.timestampRetentionClause("timestamp", 90, null)
        assertEquals("timestamp >= now() - INTERVAL 90 DAY", clause)
    }
}
