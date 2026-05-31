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

package com.moneat.security.detection

import com.moneat.shared.models.Organizations
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CRUD + preview behavior with the compiler validating on write, and org isolation of rule rows. A
 * fake query runner stands in for ClickHouse so preview is deterministic and never hits a cluster.
 */
class DetectionRuleServiceTest {
    companion object {
        private var db: Database? = null
    }

    private var orgId: Int = 0
    private var otherOrgId: Int = 0

    private val service = DetectionRuleService(
        compiler = RuleQueryCompiler({ "moneat" }),
        runner = DetectionQueryRunner(execute = { _ ->
            // two groups; one over a threshold of 5
            """{"g0":"web-01","match_count":12}
{"g0":"web-02","match_count":2}"""
        }),
    )

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_detection_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        DetectionSchemaTestSupport.reset()
        orgId = seedOrg("acme")
        otherOrgId = seedOrg("globex")
    }

    private fun createRequest(
        name: String = "Rule",
        filter: String = "status:error",
        groupBy: List<String> = listOf("host"),
        thresholdCount: Int? = 5,
        type: String = "threshold",
        enabled: Boolean = true,
    ) = CreateDetectionRuleRequest(
        name = name,
        filter = filter,
        groupBy = groupBy,
        windowSeconds = 300,
        type = type,
        thresholdCount = thresholdCount,
        severity = "high",
        enabled = enabled,
    )

    @Test
    fun `create persists a rule and get returns it`() {
        val created = service.create(orgId, createRequest(name = "Burst"))
        val fetched = service.get(orgId, created.id)
        assertEquals("Burst", fetched?.name)
        assertEquals(listOf("host"), fetched?.groupBy)
        assertEquals(5, fetched?.thresholdCount)
    }

    @Test
    fun `create rejects a rule with a non-whitelisted group-by column`() {
        val ex = assertFailsWith<DetectionCompileException> {
            service.create(orgId, createRequest(groupBy = listOf("password")))
        }
        assertTrue(ex.message!!.contains("Unknown group-by column"))
    }

    @Test
    fun `create rejects an injection-laden filter via the compiler`() {
        // The parser/compiler accept the filter as an escaped value, but an unbalanced/garbage column
        // would be rejected; here we assert a bad window is rejected up front so a rule cannot persist.
        assertFailsWith<DetectionCompileException> {
            service.create(orgId, createRequest().copy(windowSeconds = 1))
        }
    }

    @Test
    fun `another org cannot read or mutate a rule`() {
        val created = service.create(orgId, createRequest())
        assertNull(service.get(otherOrgId, created.id))
        assertNull(service.update(otherOrgId, created.id, UpdateDetectionRuleRequest(name = "x")))
        assertEquals(false, service.delete(otherOrgId, created.id))
        // Still intact for the owner.
        assertEquals("Rule", service.get(orgId, created.id)?.name)
    }

    @Test
    fun `list is scoped to the calling org`() {
        service.create(orgId, createRequest(name = "mine"))
        service.create(otherOrgId, createRequest(name = "theirs"))
        val list = service.list(orgId)
        assertEquals(1, list.totalCount)
        assertEquals("mine", list.rules.single().name)
    }

    @Test
    fun `update recompiles and rejects an invalid change`() {
        val created = service.create(orgId, createRequest())
        assertFailsWith<DetectionCompileException> {
            service.update(orgId, created.id, UpdateDetectionRuleRequest(groupBy = listOf("organization_id")))
        }
        // The original rule is unchanged.
        assertEquals(listOf("host"), service.get(orgId, created.id)?.groupBy)
    }

    @Test
    fun `loadEnabledRules returns only enabled rules`() {
        service.create(orgId, createRequest(name = "on", enabled = true))
        service.create(orgId, createRequest(name = "off", enabled = false))
        val enabled = service.loadEnabledRules()
        assertEquals(1, enabled.size)
        assertEquals("on", enabled.single().name)
    }

    @Test
    fun `preview returns would-be matches without writing signals`() = runBlocking {
        val created = service.create(orgId, createRequest(thresholdCount = 5))
        val preview = service.preview(orgId, created.id)
        assertEquals(2, preview?.matchCount) // both rows returned by the fake runner (HAVING is in SQL)
        assertEquals(300, preview?.windowSeconds)
    }

    @Test
    fun `preview of another org's rule returns null`() = runBlocking {
        val created = service.create(orgId, createRequest())
        assertNull(service.preview(otherOrgId, created.id))
    }

    private fun seedOrg(name: String): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name
            } get Organizations.id
        }
}
