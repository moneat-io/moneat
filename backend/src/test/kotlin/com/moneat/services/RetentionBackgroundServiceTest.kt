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

import com.moneat.config.ClickHouseClient
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.services.RetentionBackgroundService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.MockHttpServer
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.requestBodyText
import com.moneat.testsupport.respond
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetentionBackgroundServiceTest {

    private val retentionPolicyService = mockk<RetentionPolicyService>()
    private val service = RetentionBackgroundService(retentionPolicyService)

    companion object {
        private var db: Database? = null
        private const val CORE_RETENTION = 30
        private const val LOG_RETENTION = 7
        private const val REPLAY_RETENTION = 14
        private const val LLM_RETENTION = 21
        private const val ANALYTICS_RETENTION = 90
    }

    @BeforeTest
    fun setUp() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_retention_bg;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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

    private fun seedOrg(name: String = "Test Org"): Int = transaction {
        Organizations.insert {
            it[Organizations.name] = name
            it[slug] = name.lowercase().replace(" ", "-")
        }[Organizations.id]
    }

    private fun seedProject(
        orgId: Int,
        name: String = "project"
    ): Long = transaction {
        Projects.insert {
            it[organization_id] = orgId
            it[Projects.name] = name
            it[slug] = name
        }[Projects.id]
    }

    private fun mockRetention(
        core: Map<Int, Int> = emptyMap(),
        log: Map<Int, Int> = emptyMap(),
        replay: Map<Int, Int> = emptyMap(),
        llm: Map<Int, Int> = emptyMap(),
        analytics: Map<Int, Int> = emptyMap()
    ) {
        coEvery {
            retentionPolicyService.getRetentionDaysByOrganization()
        } returns core
        coEvery {
            retentionPolicyService.getLogRetentionDaysByOrganization()
        } returns log
        coEvery {
            retentionPolicyService.getReplayRetentionDaysByOrganization()
        } returns replay
        coEvery {
            retentionPolicyService.getLlmRetentionDaysByOrganization()
        } returns llm
        coEvery {
            retentionPolicyService.getAnalyticsRetentionDaysByOrganization()
        } returns analytics
    }

    private suspend fun invokeRunSweep(
        svc: RetentionBackgroundService = service
    ) {
        val method = svc.javaClass.getDeclaredMethod(
            "runSweep",
            Continuation::class.java
        )
        method.isAccessible = true
        suspendCoroutine<Unit> { cont ->
            val result = method.invoke(svc, cont)
            if (result !== COROUTINE_SUSPENDED) {
                cont.resumeWith(Result.success(Unit))
            }
        }
    }

    @Test
    fun `runSweep skips when ClickHouse is not initialized`() =
        runBlocking {
            val orgId = seedOrg()
            seedProject(orgId)
            mockRetention(core = mapOf(orgId to CORE_RETENTION))

            invokeRunSweep()
        }

    @Test
    fun `runSweep skips when no organizations found`() = runBlocking {
        val queries = CopyOnWriteArrayList<String>()
        mockRetention()

        MockHttpServer { exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.init(
                server.baseUrl,
                "testdb",
                "default",
                ""
            )
            invokeRunSweep()
            assertTrue(queries.isEmpty(), "No queries expected")
        }
    }

    @Test
    fun `runSweep submits core deletes for project-scoped tables`() =
        runBlocking {
            val queries = CopyOnWriteArrayList<String>()
            val orgId = seedOrg()
            val projectId = seedProject(orgId)
            mockRetention(
                core = mapOf(orgId to CORE_RETENTION),
                log = mapOf(orgId to LOG_RETENTION),
                replay = mapOf(orgId to REPLAY_RETENTION),
                llm = mapOf(orgId to LLM_RETENTION),
                analytics = mapOf(orgId to ANALYTICS_RETENTION)
            )

            MockHttpServer { exchange ->
                queries.add(exchange.requestBodyText())
                exchange.respond(200, "")
            }.use { server ->
                ClickHouseClient.init(
                    server.baseUrl,
                    "testdb",
                    "default",
                    ""
                )
                invokeRunSweep()

                val tables = listOf(
                    "events",
                    "spans",
                    "sessions",
                    "user_feedback",
                    "issues"
                )
                for (table in tables) {
                    assertTrue(
                        queries.any {
                            it.contains("`testdb`.`$table`") &&
                                it.contains(
                                    "project_id IN ($projectId)"
                                ) &&
                                it.contains(
                                    "INTERVAL $CORE_RETENTION DAY"
                                )
                        },
                        "Missing DELETE for $table"
                    )
                }
            }
        }

    @Test
    fun `runSweep submits org-scoped deletes`() = runBlocking {
        val queries = CopyOnWriteArrayList<String>()
        val orgId = seedOrg()
        seedProject(orgId)
        mockRetention(
            core = mapOf(orgId to CORE_RETENTION),
            log = mapOf(orgId to LOG_RETENTION),
            replay = mapOf(orgId to REPLAY_RETENTION),
            llm = mapOf(orgId to LLM_RETENTION),
            analytics = mapOf(orgId to ANALYTICS_RETENTION)
        )

        MockHttpServer { exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.init(
                server.baseUrl,
                "testdb",
                "default",
                ""
            )
            invokeRunSweep()

            for (table in listOf("metrics", "containers")) {
                assertTrue(
                    queries.any {
                        it.contains("`testdb`.`$table`") &&
                            it.contains(
                                "organization_id IN ($orgId)"
                            ) &&
                            it.contains("now64(3)")
                    },
                    "Missing org-scoped DELETE for $table"
                )
            }
        }
    }

    @Test
    fun `runSweep submits log deletes with separate retention`() =
        runBlocking {
            val queries = CopyOnWriteArrayList<String>()
            val orgId = seedOrg()
            val projectId = seedProject(orgId)
            mockRetention(
                core = mapOf(orgId to CORE_RETENTION),
                log = mapOf(orgId to LOG_RETENTION),
                replay = mapOf(orgId to REPLAY_RETENTION),
                llm = mapOf(orgId to LLM_RETENTION),
                analytics = mapOf(orgId to ANALYTICS_RETENTION)
            )

            MockHttpServer { exchange ->
                queries.add(exchange.requestBodyText())
                exchange.respond(200, "")
            }.use { server ->
                ClickHouseClient.init(
                    server.baseUrl,
                    "testdb",
                    "default",
                    ""
                )
                invokeRunSweep()

                val logQ = queries.filter {
                    it.contains("`testdb`.logs")
                }
                assertEquals(
                    1,
                    logQ.size,
                    "Exactly 1 log DELETE"
                )
                assertTrue(
                    logQ[0].contains(
                        "INTERVAL $LOG_RETENTION DAY"
                    ),
                    "Log query uses log retention"
                )
                assertTrue(
                    logQ[0].contains(
                        "project_id IN ($projectId)"
                    ),
                    "Log query scoped to project"
                )
            }
        }

    @Test
    fun `runSweep submits replay deletes`() = runBlocking {
        val queries = CopyOnWriteArrayList<String>()
        val orgId = seedOrg()
        seedProject(orgId)
        mockRetention(
            core = mapOf(orgId to CORE_RETENTION),
            log = mapOf(orgId to LOG_RETENTION),
            replay = mapOf(orgId to REPLAY_RETENTION),
            llm = mapOf(orgId to LLM_RETENTION),
            analytics = mapOf(orgId to ANALYTICS_RETENTION)
        )

        MockHttpServer { exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.init(
                server.baseUrl,
                "testdb",
                "default",
                ""
            )
            invokeRunSweep()

            val tables = listOf(
                "replay_events",
                "replay_segments"
            )
            for (t in tables) {
                assertTrue(
                    queries.any {
                        it.contains("`testdb`.`$t`") &&
                            it.contains(
                                "INTERVAL $REPLAY_RETENTION DAY"
                            )
                    },
                    "Missing replay DELETE for $t"
                )
            }
        }
    }

    @Test
    fun `runSweep submits LLM deletes`() = runBlocking {
        val queries = CopyOnWriteArrayList<String>()
        val orgId = seedOrg()
        seedProject(orgId)
        mockRetention(
            core = mapOf(orgId to CORE_RETENTION),
            log = mapOf(orgId to LOG_RETENTION),
            replay = mapOf(orgId to REPLAY_RETENTION),
            llm = mapOf(orgId to LLM_RETENTION),
            analytics = mapOf(orgId to ANALYTICS_RETENTION)
        )

        MockHttpServer { exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.init(
                server.baseUrl,
                "testdb",
                "default",
                ""
            )
            invokeRunSweep()

            val tables = listOf(
                "llm_generations",
                "llm_generations_hourly_mv"
            )
            for (t in tables) {
                assertTrue(
                    queries.any {
                        it.contains("`testdb`.`$t`") &&
                            it.contains(
                                "INTERVAL $LLM_RETENTION DAY"
                            )
                    },
                    "Missing LLM DELETE for $t"
                )
            }
        }
    }

    @Test
    fun `runSweep submits analytics deletes`() = runBlocking {
        val queries = CopyOnWriteArrayList<String>()
        val orgId = seedOrg()
        seedProject(orgId)
        mockRetention(
            core = mapOf(orgId to CORE_RETENTION),
            log = mapOf(orgId to LOG_RETENTION),
            replay = mapOf(orgId to REPLAY_RETENTION),
            llm = mapOf(orgId to LLM_RETENTION),
            analytics = mapOf(orgId to ANALYTICS_RETENTION)
        )

        MockHttpServer { exchange ->
            queries.add(exchange.requestBodyText())
            exchange.respond(200, "")
        }.use { server ->
            ClickHouseClient.init(
                server.baseUrl,
                "testdb",
                "default",
                ""
            )
            invokeRunSweep()

            val tables = listOf(
                "analytics_events",
                "analytics_sessions_hourly"
            )
            for (t in tables) {
                assertTrue(
                    queries.any {
                        it.contains("`testdb`.`$t`") &&
                            it.contains(
                                "INTERVAL $ANALYTICS_RETENTION DAY"
                            )
                    },
                    "Missing analytics DELETE for $t"
                )
            }
        }
    }

    @Test
    fun `runSweep handles ClickHouse error without throwing`() =
        runBlocking {
            val orgId = seedOrg()
            seedProject(orgId)
            mockRetention(
                core = mapOf(orgId to CORE_RETENTION),
                log = mapOf(orgId to LOG_RETENTION),
                replay = mapOf(orgId to REPLAY_RETENTION),
                llm = mapOf(orgId to LLM_RETENTION),
                analytics = mapOf(orgId to ANALYTICS_RETENTION)
            )

            MockHttpServer { exchange ->
                exchange.respond(500, "DB error")
            }.use { server ->
                ClickHouseClient.init(
                    server.baseUrl,
                    "testdb",
                    "default",
                    ""
                )
                invokeRunSweep()
            }
        }

    @Test
    fun `runSweep groups orgs with same retention into batch`() =
        runBlocking {
            val queries = CopyOnWriteArrayList<String>()
            val org1 = seedOrg("Org 1")
            val org2 = seedOrg("Org 2")
            val p1 = seedProject(org1, "proj-1")
            val p2 = seedProject(org2, "proj-2")

            mockRetention(
                core = mapOf(
                    org1 to CORE_RETENTION,
                    org2 to CORE_RETENTION
                ),
                log = mapOf(
                    org1 to LOG_RETENTION,
                    org2 to LOG_RETENTION
                ),
                replay = mapOf(
                    org1 to REPLAY_RETENTION,
                    org2 to REPLAY_RETENTION
                ),
                llm = mapOf(
                    org1 to LLM_RETENTION,
                    org2 to LLM_RETENTION
                ),
                analytics = mapOf(
                    org1 to ANALYTICS_RETENTION,
                    org2 to ANALYTICS_RETENTION
                )
            )

            MockHttpServer { exchange ->
                queries.add(exchange.requestBodyText())
                exchange.respond(200, "")
            }.use { server ->
                ClickHouseClient.init(
                    server.baseUrl,
                    "testdb",
                    "default",
                    ""
                )
                invokeRunSweep()

                val eventsQ = queries.first {
                    it.contains("`testdb`.`events`")
                }
                assertTrue(
                    eventsQ.contains("$p1") &&
                        eventsQ.contains("$p2"),
                    "Both projects in one batch"
                )
            }
        }

    @Test
    fun `runSweep skips project deletes when org has no projects`() =
        runBlocking {
            val queries = CopyOnWriteArrayList<String>()
            val orgId = seedOrg()

            mockRetention(
                core = mapOf(orgId to CORE_RETENTION),
                log = mapOf(orgId to LOG_RETENTION),
                replay = mapOf(orgId to REPLAY_RETENTION),
                llm = mapOf(orgId to LLM_RETENTION),
                analytics = mapOf(orgId to ANALYTICS_RETENTION)
            )

            MockHttpServer { exchange ->
                queries.add(exchange.requestBodyText())
                exchange.respond(200, "")
            }.use { server ->
                ClickHouseClient.init(
                    server.baseUrl,
                    "testdb",
                    "default",
                    ""
                )
                invokeRunSweep()

                val projQ = queries.filter {
                    it.contains("project_id")
                }
                assertTrue(
                    projQ.isEmpty(),
                    "No project-scoped queries expected"
                )
                val orgQ = queries.filter {
                    it.contains("organization_id")
                }
                assertEquals(
                    2,
                    orgQ.size,
                    "Org-scoped queries for metrics + containers"
                )
            }
        }

    @Test
    fun `start does not launch sweep when disabled by config`() =
        runBlocking {
            val scope = CoroutineScope(coroutineContext)
            service.start(scope)

            val field = service.javaClass
                .getDeclaredField("sweepJob")
            field.isAccessible = true
            assertNull(
                field.get(service) as? Job,
                "sweepJob should be null when disabled"
            )
        }

    @Test
    fun `stop is safe when no sweep is running`() {
        service.stop()
    }
}
