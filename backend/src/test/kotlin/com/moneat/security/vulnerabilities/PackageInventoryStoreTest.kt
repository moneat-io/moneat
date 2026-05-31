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

package com.moneat.security.vulnerabilities

import com.moneat.config.ClickHouseClient
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackageInventoryStoreTest {

    @BeforeTest
    fun setup() {
        mockkObject(ClickHouseClient)
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
    }

    @Test
    fun `list query selects and maps finding count`() = runBlocking {
        var listQuery = ""
        coEvery {
            ClickHouseClient.executeWithFormat(match { it.contains("SELECT count() AS total_count") }, "JSONEachRow")
        } returns "{\"total_count\":1}\n"
        coEvery {
            ClickHouseClient.executeWithFormat(
                match {
                    it.contains("security_package_inventory") &&
                        !it.contains("SELECT count() AS total_count")
                },
                "JSONEachRow",
            )
        } coAnswers {
            listQuery = firstArg()
            inventoryRow(findingCount = 2)
        }

        val response = ClickHousePackageInventoryStore { "test_db" }.list(1, InventoryFilters())

        assertTrue(listQuery.contains("AS finding_count"))
        assertEquals(2, response.inventory.single().findingCount)
        assertEquals(1, response.totalCount)
    }

    private fun inventoryRow(findingCount: Int): String =
        """{"package_name":"lodash","package_version":"4.17.11","package_type":"npm","ecosystem":"npm",""" +
            """"purl":"pkg:npm/lodash@4.17.11","target_type":"service","target_name":"checkout",""" +
            """"host":"","image_name":"","container_id":"","last_seen":"2026-05-31T00:00:00.000Z",""" +
            """"finding_count":$findingCount}"""
}
