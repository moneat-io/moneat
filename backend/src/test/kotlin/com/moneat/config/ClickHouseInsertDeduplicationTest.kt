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

package com.moneat.config

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ClickHouseInsertDeduplicationTest {

    @Test
    fun `token is scoped to ingestion seed and normalized query`() = runBlocking {
        assertNull(ClickHouseInsertDeduplication.tokenForQuery("INSERT INTO logs VALUES (1)"))

        val first =
            ClickHouseInsertDeduplication.withTokenSeed("logs:1-0") {
                ClickHouseInsertDeduplication.tokenForQuery(" INSERT INTO logs VALUES (1) ")
            }
        val second =
            ClickHouseInsertDeduplication.withTokenSeed("logs:1-0") {
                ClickHouseInsertDeduplication.tokenForQuery("INSERT INTO logs VALUES (1)")
            }
        val differentSeed =
            ClickHouseInsertDeduplication.withTokenSeed("logs:2-0") {
                ClickHouseInsertDeduplication.tokenForQuery("INSERT INTO logs VALUES (1)")
            }
        val differentQuery =
            ClickHouseInsertDeduplication.withTokenSeed("logs:1-0") {
                ClickHouseInsertDeduplication.tokenForQuery("INSERT INTO logs VALUES (2)")
            }

        assertEquals(first, second)
        assertNotEquals(first, differentSeed)
        assertNotEquals(first, differentQuery)
    }
}
