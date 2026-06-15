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

package com.moneat.monitor.services

import com.moneat.config.ClickHouseClient
import com.moneat.security.signals.SecuritySignals
import com.moneat.security.signals.SignalSchemaTestSupport
import com.moneat.security.signals.SignalSource
import com.moneat.security.signals.SignalStatus
import com.moneat.shared.models.Organizations
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
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ResourceSecurityReaderTest {
    private companion object {
        private var db: Database? = null
        private const val HOST_KEY = "checkout-host-01"
        private const val SERVICE_KEY = "checkout-api"
        private const val IMAGE_KEY = "ghcr.io/moneat/checkout:v42"
        private val observedAt = Instant.parse("2026-06-07T12:00:00Z")
    }

    private val reader = DefaultResourceSecurityReader()
    private var orgId = 0
    private var otherOrgId = 0

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_resource_security_reader;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        }
        TransactionManager.defaultDatabase = db
        SignalSchemaTestSupport.reset()
        orgId = seedOrg("reader")
        otherOrgId = seedOrg("other-reader")

        mockkObject(ClickHouseClient)
        mockkStatic(HttpResponse::bodyAsText)
        every { ClickHouseClient.getDatabase() } returns "security_test"
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient)
        unmockkStatic(HttpResponse::bodyAsText)
    }

    @Test
    fun `noop reader and empty organization reads return empty snapshots`() = runBlocking {
        assertTrue(NoopResourceSecurityReader.read(listOf(orgId)).isEmpty())
        assertTrue(reader.read(emptyList()).isEmpty())
    }

    @Test
    fun `read combines open vulnerability signals with component and compliance rows`() = runBlocking {
        seedSignal(
            severity = "critical",
            entities = """
                {
                  "cve": " CVE-2026-0001 ",
                  "package": "glibc",
                  "fix_version": "2.39",
                  "cvss": "9.8",
                  "host": " Checkout-Host-01 ",
                  "target_type": "service",
                  "target_name": "Checkout-API"
                }
            """.trimIndent(),
        )
        seedSignal(
            severity = "high",
            entities = """
                {
                  "cve": "CVE-2026-0002",
                  "package": "openssl",
                  "fix_version": "3.0.14",
                  "cvss": "8.1",
                  "host": "checkout-host-01",
                  "image_name": "$IMAGE_KEY",
                  "target_type": "Service",
                  "target_name": "checkout-api"
                }
            """.trimIndent(),
        )
        seedSignal(
            severity = "medium",
            entities = """{"cve":"CVE-2026-0003","package":"curl","image_name":"$IMAGE_KEY"}""",
        )
        seedSignal(
            severity = "low",
            entities = """
                {
                  "advisory_id": "ADV-1",
                  "package": "zlib",
                  "target_type": "service",
                  "target_name": "$SERVICE_KEY"
                }
            """.trimIndent(),
        )
        seedSignal(severity = "info", entities = """{"host":"$HOST_KEY"}""")
        seedSignal(severity = "critical", status = SignalStatus.ARCHIVED, entities = """{"host":"$HOST_KEY"}""")
        seedSignal(severity = "high", source = SignalSource.DETECTION, entities = """{"host":"$HOST_KEY"}""")
        seedSignal(organizationId = otherOrgId, severity = "high", entities = """{"host":"$HOST_KEY"}""")
        seedSignal(severity = "high", entities = "{not-json")

        val queries = mutableListOf<String>()
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            val sql = firstArg<String>()
            queries.add(sql)
            okClickHouseResponse(
                if (sql.contains("security_package_inventory")) {
                    componentRows()
                } else {
                    complianceRows()
                },
            )
        }

        val snapshot = reader.read(listOf(orgId))

        val vulnerabilities = snapshot.vulnerabilities.associateBy { it.scope to it.key }
        val hostVulns = assertNotNull(vulnerabilities[SecurityScope.HOST to HOST_KEY])
        assertEquals(1, hostVulns.critical)
        assertEquals(1, hostVulns.high)
        assertEquals("critical", hostVulns.topFindings.first().severity)
        assertEquals("CVE-2026-0001", hostVulns.topFindings.first().id)
        assertEquals("2.39", hostVulns.topFindings.first().fixedVersion)
        assertEquals(9.8, hostVulns.topFindings.first().cvss)

        val imageVulns = assertNotNull(vulnerabilities[SecurityScope.IMAGE to IMAGE_KEY])
        assertEquals(1, imageVulns.high)
        assertEquals(1, imageVulns.medium)

        val serviceVulns = assertNotNull(vulnerabilities[SecurityScope.SERVICE to SERVICE_KEY])
        assertEquals(1, serviceVulns.critical)
        assertEquals(1, serviceVulns.high)
        assertEquals(1, serviceVulns.low)

        val components = snapshot.components.associateBy { it.scope to it.key }
        assertEquals(12, components[SecurityScope.HOST to HOST_KEY]?.components)
        assertEquals(4, components[SecurityScope.IMAGE to IMAGE_KEY]?.components)
        assertEquals(7, components[SecurityScope.SERVICE to SERVICE_KEY]?.components)

        val posture = snapshot.compliance.associateBy { it.resourceName to it.framework }
        assertEquals(true, posture[HOST_KEY to "cis"]?.passed)
        assertEquals(false, posture[SERVICE_KEY to "pci"]?.passed)

        assertTrue(queries.single { it.contains("security_package_inventory") }.contains("toUInt64($orgId)"))
        assertTrue(queries.single { it.contains("compliance_findings") }.contains("toUInt64($orgId)"))
    }

    @Test
    fun `read degrades ClickHouse errors to empty component and compliance data`() = runBlocking {
        coEvery { ClickHouseClient.execute(any()) } coAnswers {
            if (firstArg<String>().contains("security_package_inventory")) {
                statusClickHouseResponse(HttpStatusCode.InternalServerError)
            } else {
                throw IllegalStateException("boom")
            }
        }

        val snapshot = reader.read(listOf(orgId))

        assertTrue(snapshot.vulnerabilities.isEmpty())
        assertTrue(snapshot.components.isEmpty())
        assertTrue(snapshot.compliance.isEmpty())
    }

    private fun componentRows(): String =
        """
        {"scope":"host","k":" Checkout-Host-01 ","c":"12"}
        {"scope":"image","k":"$IMAGE_KEY","c":4}
        {"scope":"service","k":"Checkout-API","c":7}
        {"scope":"unknown","k":"ignored","c":99}
        {"scope":"host","k":"","c":99}
        not-json
        """.trimIndent()

    private fun complianceRows(): String =
        """
        {"rt":"host","rn":" Checkout-Host-01 ","framework":"cis","failed":0}
        {"rt":"service","rn":"Checkout-API","framework":"pci","failed":"2"}
        {"rt":"host","rn":"","framework":"ignored","failed":0}
        {"rt":"host","rn":"checkout-host-01","failed":0}
        """.trimIndent()

    private fun okClickHouseResponse(body: String): HttpResponse {
        val response = mockk<HttpResponse>()
        every { response.status } returns HttpStatusCode.OK
        coEvery { response.bodyAsText(any()) } returns body
        return response
    }

    private fun statusClickHouseResponse(status: HttpStatusCode): HttpResponse {
        val response = mockk<HttpResponse>()
        every { response.status } returns status
        return response
    }

    private fun seedOrg(slug: String): Int =
        transaction {
            Organizations.insert {
                it[name] = "Org $slug"
                it[Organizations.slug] = "$slug-${System.nanoTime()}"
            } get Organizations.id
        }

    private fun seedSignal(
        severity: String,
        entities: String,
        organizationId: Int = orgId,
        source: SignalSource = SignalSource.VULNERABILITY,
        status: SignalStatus = SignalStatus.OPEN,
    ) {
        transaction {
            SecuritySignals.insert {
                it[SecuritySignals.organizationId] = organizationId
                it[signalSource] = source.wire
                it[ruleId] = "rule-${System.nanoTime()}"
                it[ruleName] = "Rule $severity"
                it[SecuritySignals.severity] = severity
                it[SecuritySignals.status] = status.wire
                it[dedupKey] = "dedup-${System.nanoTime()}"
                it[SecuritySignals.entities] = entities
                it[sampleCount] = 1
                it[tags] = "[]"
                it[firstSeen] = observedAt
                it[lastSeen] = observedAt
                it[createdAt] = observedAt
                it[updatedAt] = observedAt
            }
        }
    }
}
