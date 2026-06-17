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

package com.moneat.routes

import com.moneat.auth.services.AuthTokenService
import com.moneat.billing.models.BillingUsageResponse
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.QuotaReservationResult
import com.moneat.events.models.SourceMapFileResponse
import com.moneat.events.routes.releaseRoutes
import com.moneat.events.services.AssembledDif
import com.moneat.events.services.EventService
import com.moneat.events.services.ReleaseService
import com.moneat.plugins.AuthTokenPrincipal
import com.moneat.shared.models.AuthTokens
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseRoutesTest {
    private val testBearerToken = "test-bearer-token-releases"
    private val testCombinedToken = "test-combined-token-releases"
    private val testSourceMapToken = "test-source-map-token-releases"

    companion object {
        private const val PROGUARD_DEBUG_ID = "523fb246-631d-5716-8ac6-6fd116137be7"
        private var dbInitialized = false
        private var testUserId = -1
        private const val TEST_ORG_ID = 7
        private const val TEST_PROJECT_ID = 42L

        private fun resourceId(id: Long): String =
            "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"
    }

    @BeforeTest
    fun setupDatabase() {
        startTestKoin()
        if (!dbInitialized) {
            Database.connect(
                url =
                "jdbc:h2:mem:moneat_release_routes;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            dbInitialized = true
        }

        // Ensure schema exists (idempotent in H2) and clean between tests
        TestDatabaseHelper.resetSchema(Users, Organizations, Memberships, AuthTokens)

        testUserId =
            transaction {
                Users.insert {
                    it[email] = "release-test@test.com"
                    it[password_hash] = "hash"
                    it[email_verified] = true
                } get Users.id
            }
    }

    private fun Application.installAuth() {
        install(Authentication) {
            bearer("auth-bearer") {
                authenticate { credential ->
                    if (credential.token == testBearerToken) {
                        AuthTokenPrincipal(
                            userId = testUserId,
                            scopes = AuthTokenService.VALID_SCOPES.toList(),
                            tokenId = 1
                        )
                    } else {
                        null
                    }
                }
            }
            bearer("auth-combined") {
                authenticate { credential ->
                    when (credential.token) {
                        testCombinedToken ->
                            AuthTokenPrincipal(
                                userId = testUserId,
                                scopes = listOf("project:read"), // intentionally missing releases:write
                                tokenId = 1
                            )

                        testSourceMapToken ->
                            AuthTokenPrincipal(
                                userId = testUserId,
                                scopes = listOf("sourcemaps:write", "sourcemaps:read"),
                                tokenId = 2
                            )

                        else -> null
                    }
                }
            }
        }
    }

    @Test
    fun `GET api 0 returns 200 with auth info for valid bearer token`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes() }
            }

            val response =
                client.get("/api/0/") {
                    header(HttpHeaders.Authorization, "Bearer $testBearerToken")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("release-test@test.com"))
        }

    @Test
    fun `GET api 0 returns 401 without authentication`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes() }
            }

            val response = client.get("/api/0/")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `POST releases returns 403 when releases write scope is missing`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes() }
            }

            val response =
                client.post("/api/0/organizations/my-org/releases/") {
                    header(HttpHeaders.Authorization, "Bearer $testCombinedToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"version":"1.0.0"}""")
                }

            // Token has project:read only, not releases:write → 403
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("releases:write"))
        }

    @Test
    fun `POST releases returns 401 without authentication`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes() }
            }

            val response =
                client.post("/api/0/organizations/my-org/releases/") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"version":"1.0.0"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `POST source map upload reserves quota and stores file`() =
        testApplication {
            val releaseService = sourceMapReleaseService()
            every {
                releaseService.uploadSourceMap(TEST_PROJECT_ID, "1.0.0", "~/app.js.map", any())
            } returns SourceMapFileResponse(resourceId(10), "~/app.js.map", "2026-05-23T00:00:00Z")

            val quotaService = allowingQuotaService()
            val eventService = projectOrgEventService()

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes(releaseService, AuthTokenService(), quotaService, eventService) }
            }

            val response =
                client.post("/api/0/projects/my-org/my-project/releases/1.0.0/files/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("name", "~/app.js.map")
                                appendFile("file", "app.js.map", "source map content".toByteArray())
                            }
                        )
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("~/app.js.map"))
            verify { quotaService.reserveUnits(TEST_ORG_ID, 1, "sourcemap", "source map content".length.toLong()) }
        }

    @Test
    fun `POST source map upload returns 429 when quota reservation is rejected`() =
        testApplication {
            val releaseService = sourceMapReleaseService()
            val quotaService = rejectingQuotaService()
            val eventService = projectOrgEventService()

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes(releaseService, AuthTokenService(), quotaService, eventService) }
            }

            val response =
                client.post("/api/0/projects/my-org/my-project/releases/1.0.0/files/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("name", "~/app.js.map")
                                appendFile("file", "app.js.map", "source map content".toByteArray())
                            }
                        )
                    )
                }

            assertEquals(HttpStatusCode.TooManyRequests, response.status, response.bodyAsText())
            verify(exactly = 0) { releaseService.uploadSourceMap(any(), any(), any(), any()) }
        }

    @Test
    fun `POST source map upload refunds quota when storing file fails`() =
        testApplication {
            val releaseService = sourceMapReleaseService()
            every {
                releaseService.uploadSourceMap(TEST_PROJECT_ID, "1.0.0", "~/app.js.map", any())
            } throws IllegalArgumentException("Release not found")

            val quotaService = allowingQuotaService()
            val eventService = projectOrgEventService()

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes(releaseService, AuthTokenService(), quotaService, eventService) }
            }

            val response =
                client.post("/api/0/projects/my-org/my-project/releases/1.0.0/files/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("name", "~/app.js.map")
                                appendFile("file", "app.js.map", "source map content".toByteArray())
                            }
                        )
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            verify { quotaService.refundUnits(TEST_ORG_ID, 1, "sourcemap", "source map content".length.toLong()) }
        }

    @Test
    fun `POST chunk upload reserves quota and stores gzip chunk`() =
        testApplication {
            val releaseService = mockk<ReleaseService>()
            every { releaseService.hasOrgAccess(testUserId, "my-org") } returns true
            every { releaseService.getOrganizationIdBySlug("my-org") } returns TEST_ORG_ID
            every { releaseService.storeChunk("abc123", "chunk content".toByteArray()) } just Runs

            val quotaService = allowingQuotaService()

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes(releaseService, AuthTokenService(), quotaService, projectOrgEventService()) }
            }

            val response =
                client.post("/api/0/organizations/my-org/chunk-upload/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                appendFile("file_gzip", "abc123", gzip("chunk content".toByteArray()))
                            }
                        )
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            verify { quotaService.reserveUnits(TEST_ORG_ID, 1, "sourcemap", "chunk content".length.toLong()) }
            verify { releaseService.storeChunk("abc123", "chunk content".toByteArray()) }
        }

    @Test
    fun `POST chunk upload refunds quota when storing chunk fails`() =
        testApplication {
            val releaseService = mockk<ReleaseService>()
            every { releaseService.hasOrgAccess(testUserId, "my-org") } returns true
            every { releaseService.getOrganizationIdBySlug("my-org") } returns TEST_ORG_ID
            every { releaseService.storeChunk("abc123", any()) } throws IOException("storage failed")

            val quotaService = allowingQuotaService()

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing { releaseRoutes(releaseService, AuthTokenService(), quotaService, projectOrgEventService()) }
            }

            val response =
                client.post("/api/0/organizations/my-org/chunk-upload/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                appendFile("file", "abc123", "chunk content".toByteArray())
                            }
                        )
                    )
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status, response.bodyAsText())
            verify { quotaService.refundUnits(TEST_ORG_ID, 1, "sourcemap", "chunk content".length.toLong()) }
        }

    @Test
    fun `POST difs assemble returns ok and records dif when chunks are present`() =
        testApplication {
            val releaseService = sourceMapReleaseService()
            every { releaseService.findMissingChunks(setOf("chunk-1")) } returns emptyList()
            every {
                releaseService.assembleProjectDif(
                    TEST_PROJECT_ID,
                    "sum-1",
                    listOf("chunk-1"),
                    "proguard/the-uuid.txt",
                    null
                )
            } returns AssembledDif(
                resourceId = resourceId(99),
                debugId = "the-uuid",
                objectName = "proguard/the-uuid.txt",
                checksum = "sum-1",
                size = 12L,
                dateCreated = "2026-05-23T00:00:00Z"
            )

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    releaseRoutes(releaseService, AuthTokenService(), allowingQuotaService(), projectOrgEventService())
                }
            }

            val response =
                client.post("/api/0/projects/my-org/my-project/files/difs/assemble/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"sum-1":{"name":"proguard/the-uuid.txt","chunks":["chunk-1"]}}""")
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = response.bodyAsText()
            assertTrue(body.contains("\"state\":\"ok\""), body)
            assertTrue(body.contains("the-uuid"), body)
            verify {
                releaseService.assembleProjectDif(
                    TEST_PROJECT_ID,
                    "sum-1",
                    listOf("chunk-1"),
                    "proguard/the-uuid.txt",
                    null
                )
            }
        }

    @Test
    fun `POST difs assemble returns not_found with missing chunks and does not assemble`() =
        testApplication {
            val releaseService = sourceMapReleaseService()
            every { releaseService.findMissingChunks(setOf("missing-chunk")) } returns listOf("missing-chunk")

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    releaseRoutes(releaseService, AuthTokenService(), allowingQuotaService(), projectOrgEventService())
                }
            }

            val response =
                client.post("/api/0/projects/my-org/my-project/files/difs/assemble/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"sum-2":{"name":"proguard/x.txt","chunks":["missing-chunk"]}}""")
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = response.bodyAsText()
            assertTrue(body.contains("not_found"), body)
            assertTrue(body.contains("missing-chunk"), body)
            verify(exactly = 0) { releaseService.assembleProjectDif(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `POST difs assemble returns 404 when project not found`() =
        testApplication {
            val releaseService = mockk<ReleaseService>()
            every { releaseService.getProjectBySlug("my-org", "missing") } returns null

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    releaseRoutes(releaseService, AuthTokenService(), allowingQuotaService(), projectOrgEventService())
                }
            }

            val response =
                client.post("/api/0/projects/my-org/missing/files/difs/assemble/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"sum-3":{"chunks":["c"]}}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
        }

    @Test
    fun `POST difs assemble returns 403 when sourcemaps write scope is missing`() =
        testApplication {
            val releaseService = mockk<ReleaseService>()

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    releaseRoutes(releaseService, AuthTokenService(), allowingQuotaService(), projectOrgEventService())
                }
            }

            val response =
                client.post("/api/0/projects/my-org/my-project/files/difs/assemble/") {
                    header(HttpHeaders.Authorization, "Bearer $testCombinedToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"sum-x":{"chunks":["c"]}}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("sourcemaps:write"), response.bodyAsText())
        }

    @Test
    fun `POST legacy dsyms upload stores proguard zip entries`() =
        testApplication {
            val releaseService = sourceMapReleaseService()
            val mappingName = "proguard/$PROGUARD_DEBUG_ID.txt"
            val mappingBytes =
                """
                # compiler: R8
                com.example.Foo -> a:
                    1:1:void doThing():10:10 -> a
                """.trimIndent().toByteArray()
            val checksum = sha1(mappingBytes)
            val storedBytes = slot<ByteArray>()

            every { releaseService.storeChunk(checksum, capture(storedBytes)) } just Runs
            every {
                releaseService.assembleProjectDif(
                    TEST_PROJECT_ID,
                    checksum,
                    listOf(checksum),
                    mappingName,
                    null
                )
            } returns AssembledDif(
                resourceId = resourceId(101),
                debugId = PROGUARD_DEBUG_ID,
                objectName = mappingName,
                checksum = checksum,
                size = mappingBytes.size.toLong(),
                dateCreated = "2026-05-23T00:00:00Z"
            )

            val quotaService = allowingQuotaService()
            val eventService = projectOrgEventService()

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    releaseRoutes(releaseService, AuthTokenService(), quotaService, eventService)
                }
            }

            val response =
                client.post("/api/0/projects/my-org/my-project/files/dsyms/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                appendFile("file", "proguard.zip", zipProguardMapping(mappingName, mappingBytes))
                            }
                        )
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = response.bodyAsText()
            assertTrue(body.contains(PROGUARD_DEBUG_ID), body)
            assertTrue(body.contains("\"objectName\":\"$mappingName\""), body)
            assertTrue(body.contains("\"cpuName\":\"any\""), body)
            assertTrue(body.contains("\"sha1\":\"$checksum\""), body)
            assertTrue(storedBytes.captured.contentEquals(mappingBytes))
            verify { quotaService.reserveUnits(TEST_ORG_ID, 1, "sourcemap", mappingBytes.size.toLong()) }
        }

    @Test
    fun `POST legacy dsyms upload refunds only uncommitted mappings when later entry fails`() =
        testApplication {
            val releaseService = sourceMapReleaseService()
            val firstName = "proguard/$PROGUARD_DEBUG_ID.txt"
            val secondDebugId = "623fb246-631d-5716-8ac6-6fd116137be7"
            val secondName = "proguard/$secondDebugId.txt"
            val firstBytes = "first mapping".toByteArray()
            val secondBytes = "second mapping".toByteArray()
            val firstChecksum = sha1(firstBytes)
            val secondChecksum = sha1(secondBytes)

            every { releaseService.storeChunk(firstChecksum, any()) } just Runs
            every {
                releaseService.assembleProjectDif(
                    TEST_PROJECT_ID,
                    firstChecksum,
                    listOf(firstChecksum),
                    firstName,
                    null
                )
            } returns AssembledDif(
                resourceId = resourceId(102),
                debugId = PROGUARD_DEBUG_ID,
                objectName = firstName,
                checksum = firstChecksum,
                size = firstBytes.size.toLong(),
                dateCreated = "2026-05-23T00:00:00Z"
            )
            every { releaseService.storeChunk(secondChecksum, any()) } throws IOException("storage failed")

            val quotaService = allowingQuotaService()

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    releaseRoutes(releaseService, AuthTokenService(), quotaService, projectOrgEventService())
                }
            }

            val response =
                client.post("/api/0/projects/my-org/my-project/files/dsyms/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                appendFile(
                                    "file",
                                    "proguard.zip",
                                    zipProguardMappings(firstName to firstBytes, secondName to secondBytes)
                                )
                            }
                        )
                    )
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status, response.bodyAsText())
            verify {
                quotaService.reserveUnits(
                    TEST_ORG_ID,
                    2,
                    "sourcemap",
                    firstBytes.size.toLong() + secondBytes.size.toLong()
                )
            }
            verify { quotaService.refundUnits(TEST_ORG_ID, 1, "sourcemap", secondBytes.size.toLong()) }
        }

    @Test
    fun `POST reprocessing returns ok for sentry cli compatibility`() =
        testApplication {
            val releaseService = sourceMapReleaseService()

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    releaseRoutes(
                        releaseService,
                        AuthTokenService(),
                        allowingQuotaService(),
                        projectOrgEventService()
                    )
                }
            }

            val response =
                client.post("/api/0/projects/my-org/my-project/reprocessing/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals("[]", response.bodyAsText())
        }

    @Test
    fun `GET difs returns stored difs for the project`() =
        testApplication {
            val releaseService = sourceMapReleaseService()
            every { releaseService.listProjectDifs(TEST_PROJECT_ID, emptySet(), emptySet()) } returns
                listOf(
                    AssembledDif(
                        resourceId = resourceId(7),
                        debugId = "the-uuid",
                        objectName = "proguard/the-uuid.txt",
                        checksum = "sum-1",
                        size = 12L,
                        dateCreated = "2026-05-23T00:00:00Z"
                    )
                )

            application {
                install(ContentNegotiation) { json() }
                installAuth()
                routing {
                    releaseRoutes(releaseService, AuthTokenService(), allowingQuotaService(), projectOrgEventService())
                }
            }

            val response =
                client.get("/api/0/projects/my-org/my-project/files/difs/") {
                    header(HttpHeaders.Authorization, "Bearer $testSourceMapToken")
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("the-uuid"), response.bodyAsText())
        }

    private fun sourceMapReleaseService(): ReleaseService {
        val releaseService = mockk<ReleaseService>()
        every { releaseService.getProjectBySlug("my-org", "my-project") } returns TEST_PROJECT_ID
        every { releaseService.hasProjectAccess(testUserId, TEST_PROJECT_ID) } returns true
        return releaseService
    }

    private fun projectOrgEventService(): EventService {
        val eventService = mockk<EventService>()
        every { eventService.getOrganizationIdForProject(TEST_PROJECT_ID) } returns TEST_ORG_ID
        return eventService
    }

    private fun allowingQuotaService(): BillingQuotaService {
        val quotaService = mockk<BillingQuotaService>(relaxed = true)
        every { quotaService.isEnforcementEnabled() } returns true
        every {
            quotaService.reserveUnits(any(), any(), any(), any())
        } returns QuotaReservationResult(allowed = true, usage = quotaUsage())
        return quotaService
    }

    private fun rejectingQuotaService(): BillingQuotaService {
        val quotaService = mockk<BillingQuotaService>(relaxed = true)
        every { quotaService.isEnforcementEnabled() } returns true
        every {
            quotaService.reserveUnits(any(), any(), any(), any())
        } returns QuotaReservationResult(
            allowed = false,
            reason = "gb_quota_exceeded",
            usage = quotaUsage()
        )
        return quotaService
    }

    private fun quotaUsage(): BillingUsageResponse {
        return BillingUsageResponse(
            organizationId = resourceId(TEST_ORG_ID.toLong()),
            periodStart = "2026-05-01",
            periodEnd = "2026-05-31",
            retentionDays = 30,
            apmTraceRetentionDays = 30,
            usedUnits = 0,
            usedErrors = 0,
            errorLimit = 0,
            usedTransactions = 0,
            transactionLimit = 0,
            usedReplays = 0,
            replayLimit = 0,
            usedFeedback = 0,
            feedbackLimit = 0,
            usedBytes = 0,
            bytesLimit = 0,
            baseLimitUnits = 0,
            paygLimitUnits = 0,
            totalLimitUnits = 0,
            paygBudgetCents = 0,
            paygUsedUnits = 0,
            paygUsedCentsEstimate = 0,
            plan = "PRO",
            status = "active",
            withinQuota = true
        )
    }

    private fun FormBuilder.appendFile(name: String, fileName: String, bytes: ByteArray) {
        append(
            name,
            bytes,
            Headers.build {
                append(HttpHeaders.ContentDisposition, "form-data; name=\"$name\"; filename=\"$fileName\"")
                append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
            }
        )
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun zipProguardMapping(name: String, bytes: ByteArray): ByteArray {
        return zipProguardMappings(name to bytes)
    }

    private fun zipProguardMappings(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }
}
