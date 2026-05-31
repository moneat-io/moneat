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

package com.moneat.security.posture

import com.moneat.security.signals.SignalSeverity
import com.moneat.security.signals.SignalSource
import com.moneat.shared.models.Organizations
import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostureRegressionAnalyzerTest {
    companion object {
        private var db: Database? = null
    }

    private var orgId: Int = 0
    private var otherOrgId: Int = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_posture_regression;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.dropAndPatchJsonb(Organizations, SecurityComplianceFindingStates)
        transaction {
            SchemaUtils.create(Organizations, SecurityComplianceFindingStates)
            orgId = seedOrg("acme")
            otherOrgId = seedOrg("other")
        }
    }

    @Test
    fun `failed first sight does not emit regression signal`() {
        val specs = PostureRegressionAnalyzer.analyze(orgId, listOf(finding(status = "failed")))

        assertTrue(specs.isEmpty())
    }

    @Test
    fun `passed to failed emits one compliance regression signal`() {
        PostureRegressionAnalyzer.analyze(orgId, listOf(finding(status = "passed")))

        val specs = PostureRegressionAnalyzer.analyze(orgId, listOf(finding(status = "failed")))

        assertEquals(1, specs.size)
        val spec = specs.single()
        assertEquals(SignalSource.AGENT_COMPLIANCE, spec.source)
        assertEquals(SignalSeverity.HIGH, spec.severity)
        assertEquals("cis-1.1", spec.ruleId)
        assertEquals("cis-aws|cis-1.1|aws_account|acct-123", spec.dedupKey)
        assertEquals("acct-123", spec.entities["resource_id"])
        assertTrue(spec.evidenceReference.contains("previous=passed"))
    }

    @Test
    fun `repeated failed finding updates state without emitting`() {
        PostureRegressionAnalyzer.analyze(orgId, listOf(finding(status = "passed")))
        PostureRegressionAnalyzer.analyze(orgId, listOf(finding(status = "failed")))

        val repeat = PostureRegressionAnalyzer.analyze(orgId, listOf(finding(status = "failed")))

        assertTrue(repeat.isEmpty())
    }

    @Test
    fun `state is isolated by organization`() {
        PostureRegressionAnalyzer.analyze(orgId, listOf(finding(status = "passed")))

        val otherOrgSpecs = PostureRegressionAnalyzer.analyze(otherOrgId, listOf(finding(status = "failed")))
        val originalOrgSpecs = PostureRegressionAnalyzer.analyze(orgId, listOf(finding(status = "failed")))

        assertTrue(otherOrgSpecs.isEmpty())
        assertEquals(1, originalOrgSpecs.size)
    }

    private fun finding(status: String): ComplianceFindingInput =
        ComplianceFindingInput(
            framework = "cis-aws",
            ruleId = "cis-1.1",
            ruleName = "Root MFA",
            status = status,
            resourceType = "aws_account",
            resourceId = "acct-123",
            resourceName = "prod",
            timestampMs = 1_700_000_000_000,
        )

    private fun seedOrg(name: String): Int =
        Organizations.insert {
            it[Organizations.name] = name
            it[slug] = name
        } get Organizations.id
}
