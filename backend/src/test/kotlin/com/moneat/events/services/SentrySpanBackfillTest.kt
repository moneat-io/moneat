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

package com.moneat.events.services

import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import com.moneat.testsupport.withClickHouseMockServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SentrySpanBackfillTest {

    companion object {
        private var db: Database? = null
    }

    @BeforeTest
    fun setUp() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_sentry_span_backfill;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Organizations, Projects)
        ClickHouseClient.close()
    }

    @AfterTest
    fun tearDown() {
        ClickHouseClient.close()
    }

    @Test
    fun `run backfills spans using service id routing columns`() = runBlocking {
        val orgId = seedOrg()
        val checkoutServiceId = seedProject(orgId, "checkout")
        val workerServiceId = seedProject(orgId, "worker")
        val queries = CopyOnWriteArrayList<String>()

        withClickHouseMockServer(
            { exchange ->
                queries += exchange.requestBodyText()
                exchange.respond(200, "")
            },
            database = "test_db"
        ) {
            runBlocking { SentrySpanBackfill.run() }
        }

        val query = queries.single()
        assertTrue(query.contains("INSERT INTO `test_db`.apm_spans"))
        assertTrue(query.contains("organization_id, service_id, project_id"))
        assertTrue(query.contains("service_id,"))
        assertTrue(query.contains("project_id,"))
        assertTrue(query.contains("'sentry.project_id', toString(service_id)"))
        assertTrue(query.contains("WHERE service_id IN ("))
        assertTrue(query.contains(checkoutServiceId.toString()))
        assertTrue(query.contains(workerServiceId.toString()))
        assertTrue(query.contains("service_id = $checkoutServiceId, $orgId"))
        assertTrue(query.contains("service_id = $workerServiceId, $orgId"))
    }

    private fun seedOrg(): Int =
        transaction {
            Organizations.insert {
                it[name] = "Backfill Org"
                it[slug] = "backfill-org"
            } get Organizations.id
        }

    private fun seedProject(
        orgId: Int,
        slug: String
    ): Long =
        transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[name] = slug
                it[Projects.slug] = slug
            } get Projects.id
        }
}
