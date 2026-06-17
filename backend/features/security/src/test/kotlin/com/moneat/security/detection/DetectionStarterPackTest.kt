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
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The starter pack as installable templates. Installing each one goes through the same
 * [DetectionRuleService.create] (compiler-gated) path and persists a disabled rule — proving the pack is
 * not a bypass and that every Moneat-authored rule actually compiles end-to-end.
 */
class DetectionStarterPackTest {
    companion object {
        private var db: Database? = null
    }

    private var orgId: Int = 0

    private val ruleService = DetectionRuleService(
        compiler = RuleQueryCompiler({ "moneat" }),
        runner = DetectionQueryRunner(execute = { _ -> """{"g0":"web-01","match_count":1}""" }),
    )
    private val importService = DetectionImportService(ruleService)

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_starter_pack;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        DetectionSchemaTestSupport.reset()
        orgId = seedOrg("acme")
    }

    @Test
    fun `the pack lists six templates with stable ids`() {
        val ids = DetectionStarterPack.templates.map { it.id }
        assertEquals(6, ids.size)
        assertEquals(ids.size, ids.toSet().size, "template ids must be unique")
        assertTrue(ids.contains("failed-auth-brute-force"))
        assertTrue(ids.contains("new-source-login"))
        assertTrue(ids.contains("outbound-to-suspicious"))
    }

    @Test
    fun `every template is authored disabled`() {
        DetectionStarterPack.templates.forEach { assertFalse(it.request.enabled, "${it.id} must be disabled") }
    }

    @Test
    fun `installing every template through the compiler-gated path persists a disabled rule`() {
        DetectionStarterPack.templates.forEach { template ->
            val created = importService.installTemplate(orgId, template.id)
            requireNotNull(created) { "install returned null for ${template.id}" }
            assertFalse(created.enabled, "${template.id} installed enabled")
            assertEquals(template.request.name, created.name)
        }
        // All six now exist for the org.
        assertEquals(6, ruleService.list(orgId).totalCount)
    }

    @Test
    fun `installing an unknown template returns null`() {
        assertNull(importService.installTemplate(orgId, "nope"))
    }

    @Test
    fun `the new-value template keeps its type and a user group key`() {
        val installed = importService.installTemplate(orgId, "new-source-login")
        requireNotNull(installed)
        assertEquals(DetectionRuleType.NEW_VALUE.wire, installed.type)
        assertTrue(installed.groupBy.contains("tags['user']"), installed.groupBy.toString())
    }

    @Test
    fun `sigma import goes through the compiler and rejects unmappable docs per item`() {
        val good = """
            title: ok
            logsource:
              category: process_creation
            detection:
              selection:
                Image: /bin/sh
              condition: selection
        """.trimIndent()
        val unmappable = "title: bad\ndetection:\n  s:\n    Image|re: '.*'\n  condition: s"
        val result = importService.importSigma(orgId, listOf(good, unmappable))
        assertEquals(1, result.createdCount)
        assertEquals(1, result.errorCount)
        // The created one persisted disabled; the bad one did not.
        assertEquals(1, ruleService.list(orgId).totalCount)
        val createdItem = result.results.single { it.ruleId != null }
        assertEquals("ok", createdItem.title)
        val errorItem = result.results.single { it.error != null }
        assertTrue(errorItem.error!!.isNotBlank())
    }

    private fun seedOrg(name: String): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[slug] = name
        } get Organizations.id
    }
}
