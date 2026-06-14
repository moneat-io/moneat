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
import com.moneat.config.ClickHouseQueryException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackageInventoryStoreTest {

    @BeforeTest
    fun setup() {
        mockkObject(ClickHouseClient)
        mockkStatic(HttpResponse::bodyAsText)
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
        unmockkStatic(HttpResponse::bodyAsText)
    }

    @Test
    fun `insert writes escaped inventory rows and skips empty batches`() = runBlocking {
        val queries = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            queries += firstArg<String>()
            okResponse("")
        }
        val store = ClickHousePackageInventoryStore { "security_db" }

        store.insert(emptyList())
        store.insert(
            listOf(
                inventoryRecord(
                    packageName = "o'hara",
                    licenses = listOf("MIT", "Apache-2.0"),
                    tags = mapOf("team" to "security", "scope" to "runtime"),
                )
            )
        )

        assertEquals(1, queries.size)
        val query = queries.single()
        assertTrue(query.contains("INSERT INTO `security_db`.security_package_inventory"))
        assertTrue(query.contains("'o\\'hara'"))
        assertTrue(query.contains("['MIT', 'Apache-2.0']"))
        assertTrue(query.contains("toUUID('"))
        assertTrue(query.contains("fromUnixTimestamp64Milli(1700000000000)"))
    }

    @Test
    fun `insert converts ClickHouse failures to query exception`() {
        runBlocking {
            coEvery { ClickHouseClient.execute(any()) } returns okResponse("Code: 62. Syntax error")
            val store = ClickHousePackageInventoryStore { "security_db" }

            assertFailsWith<ClickHouseQueryException> {
                store.insert(listOf(inventoryRecord(packageName = "lodash")))
            }
        }
    }

    @Test
    fun `list query selects and maps finding count`() = runBlocking {
        var countQuery = ""
        var listQuery = ""
        coEvery {
            ClickHouseClient.executeWithFormat(match { it.contains("SELECT count() AS total_count") }, "JSONEachRow")
        } coAnswers {
            countQuery = firstArg()
            "{\"total_count\":1}\n"
        }
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
        assertTrue(countQuery.contains("package_type, ecosystem, purl"))
        assertTrue(countQuery.contains("target_type, target_name, host, image_name, container_id"))
        assertEquals(2, response.inventory.single().findingCount)
        assertEquals(1, response.totalCount)
    }

    @Test
    fun `list applies package target and search filters while ignoring malformed rows`() = runBlocking {
        val queries = mutableListOf<String>()
        coEvery { ClickHouseClient.executeWithFormat(any(), "JSONEachRow") } coAnswers {
            val query = firstArg<String>()
            queries += query
            if (query.contains("SELECT count() AS total_count")) {
                ""
            } else {
                "not-json\n" + inventoryRow(findingCount = 0)
            }
        }

        val response = ClickHousePackageInventoryStore { "security_db" }.list(
            42,
            InventoryFilters(
                search = "lod'ash_%team",
                packageName = "lodash",
                target = "prod-web",
                limit = 25,
                offset = 5,
            ),
        )

        val listQuery = queries.last()
        assertTrue(listQuery.contains("organization_id = toUInt64(42)"))
        assertTrue(listQuery.contains("package_name = 'lodash'"))
        assertTrue(listQuery.contains("(target_name = 'prod-web' OR host = 'prod-web' OR image_name = 'prod-web')"))
        assertTrue(listQuery.contains("ILIKE '%lod\\'ash\\_\\%team%'"))
        assertTrue(listQuery.contains("LIMIT 25 OFFSET 5"))
        assertEquals(0, response.totalCount)
        assertEquals("lodash", response.inventory.single().packageName)
    }

    @Test
    fun `countPackages returns zero when ClickHouse returns no rows`() = runBlocking {
        coEvery { ClickHouseClient.executeWithFormat(any(), "JSONEachRow") } returns ""

        val count = ClickHousePackageInventoryStore { "security_db" }.countPackages(7)

        assertEquals(0, count)
    }

    private fun okResponse(body: String): HttpResponse {
        val response = mockk<HttpResponse>()
        every { response.status } returns HttpStatusCode.OK
        coEvery { response.bodyAsText(any()) } returns body
        return response
    }

    private fun inventoryRow(findingCount: Int): String =
        """{"package_name":"lodash","package_version":"4.17.11","package_type":"npm","ecosystem":"npm",""" +
            """"purl":"pkg:npm/lodash@4.17.11","target_type":"service","target_name":"checkout",""" +
            """"host":"","image_name":"","container_id":"","last_seen":"2026-05-31T00:00:00.000Z",""" +
            """"finding_count":$findingCount}"""

    private fun inventoryRecord(
        packageName: String,
        licenses: List<String> = emptyList(),
        tags: Map<String, String> = emptyMap(),
    ): SbomInventoryRecord =
        SbomInventoryRecord(
            uploadId = UUID.randomUUID(),
            organizationId = 1,
            source = SbomSource.AGENT,
            format = SbomFormat.AGENT,
            targetType = "container",
            targetName = "checkout",
            host = "prod-web",
            containerId = "container-1",
            imageName = "registry/app:latest",
            packageName = packageName,
            packageVersion = "4.17.11",
            packageType = "npm",
            ecosystem = "npm",
            purl = "pkg:npm/$packageName@4.17.11",
            licenses = licenses,
            supplier = "npm",
            bomRef = "pkg-$packageName",
            tags = tags,
            collectedAtMs = 1_700_000_000_000,
        )
}
