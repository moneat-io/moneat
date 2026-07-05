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

package com.moneat.datadog.auth

import com.moneat.datadog.models.DdApiKeys
import com.moneat.datadog.services.DatadogService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.verify
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import io.ktor.serialization.kotlinx.json.json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

class DatadogAuthMiddlewareTest {

    private fun fillCache(fieldName: String, count: Int) {
        val field = DatadogAuthMiddleware::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
        }

        @Suppress("UNCHECKED_CAST")
        val cache = field.get(DatadogAuthMiddleware) as MutableMap<String, Any>
        cache.clear()
        val entryClass = DatadogAuthMiddleware::class.nestedClasses.single {
            it.simpleName == when (fieldName) {
                "cache" -> "CachedKey"
                else -> "CachedContext"
            }
        }.java
        val ctor = entryClass.declaredConstructors.single().apply { isAccessible = true }
        repeat(count) { index ->
            cache["cached-$fieldName-$index"] = when (fieldName) {
                "cache" -> ctor.newInstance(index, 0L)
                else -> ctor.newInstance(DatadogAuthContext(index, index), 0L)
            }
        }
    }

    private fun putExpiredCacheEntry(fieldName: String, key: String, organizationId: Int = 7) {
        val field = DatadogAuthMiddleware::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
        }

        @Suppress("UNCHECKED_CAST")
        val cache = field.get(DatadogAuthMiddleware) as MutableMap<String, Any>
        val entryClass = DatadogAuthMiddleware::class.nestedClasses.single {
            it.simpleName == when (fieldName) {
                "cache" -> "CachedKey"
                else -> "CachedContext"
            }
        }.java
        val ctor = entryClass.declaredConstructors.single().apply { isAccessible = true }
        cache[key] = when (fieldName) {
            "cache" -> ctor.newInstance(organizationId, 0L)
            else -> ctor.newInstance(DatadogAuthContext(organizationId, 12), 0L)
        }
    }

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:test_dd_auth;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver"
        )
        transaction {
            exec("DROP ALL OBJECTS")
            SchemaUtils.create(DdApiKeys)
        }
        DatadogAuthMiddleware.clearCache()
    }

    @Test
    fun `validateApiKey caching returns same org after first call`() {
        val created = DatadogService.createApiKey(
            organizationId = 1,
            name = "Cache Key",
            userId = 1
        )

        // First validation hits DB
        val orgId1 = DatadogService.validateApiKey(created.key)
        assertNotNull(orgId1)

        // Second should also work (verifies key is still valid)
        val orgId2 = DatadogService.validateApiKey(created.key)
        assertNotNull(orgId2)
        assertEquals(orgId1, orgId2)
    }

    @Test
    fun `validateApiKey returns null for revoked key`() {
        val created = DatadogService.createApiKey(
            organizationId = 1,
            name = "Revoke Cache",
            userId = 1
        )

        val orgId1 = DatadogService.validateApiKey(created.key)
        assertNotNull(orgId1)

        DatadogService.revokeApiKey(created.id, 1)

        // After clearing cache, revoked key should fail
        DatadogAuthMiddleware.clearCache()
        val orgId2 = DatadogService.validateApiKey(created.key)
        assertNull(orgId2)
    }

    @Test
    fun `clearCache empties the cache`() {
        val created = DatadogService.createApiKey(
            organizationId = 1,
            name = "Clear Key",
            userId = 1
        )

        // Populate
        DatadogService.validateApiKey(created.key)

        // Clear
        DatadogAuthMiddleware.clearCache()

        // Should still work (re-validates from DB)
        val orgId = DatadogService.validateApiKey(created.key)
        assertNotNull(orgId)
    }

    @Test
    fun `authenticate returns organization id when key is valid`() = testApplication {
        mockkObject(DatadogService)
        every { DatadogService.validateApiKey("valid-key") } returns 11

        application {
            routing {
                get("/probe") {
                    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return@get
                    call.respondText(orgId.toString())
                }
            }
        }

        val response = client.get("/probe") { header("api-key", "valid-key") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("11", response.bodyAsText())

        val secondResponse = client.get("/probe") { header("api-key", "valid-key") }
        assertEquals(HttpStatusCode.OK, secondResponse.status)
        assertEquals("11", secondResponse.bodyAsText())
        verify(exactly = 1) { DatadogService.validateApiKey("valid-key") }
    }

    @Test
    fun `authenticateContext returns cached context after first validation`() = testApplication {
        mockkObject(DatadogService)
        every { DatadogService.validateApiKeyContext("valid-context-key") } returns
            DatadogService.ApiKeyValidation(organizationId = 1, projectId = 2)

        application {
            routing {
                get("/probe") {
                    val context = DatadogAuthMiddleware.authenticateContext(call) ?: return@get
                    call.respondText(context.projectId.toString())
                }
            }
        }

        val first = client.get("/probe") { header("api-key", "valid-context-key") }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("2", first.bodyAsText())

        val second = client.get("/probe") { header("api-key", "valid-context-key") }
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals("2", second.bodyAsText())
        verify(exactly = 1) { DatadogService.validateApiKeyContext("valid-context-key") }
    }

    @Test
    fun `resolveOrgId uses cache and skips duplicate validation`() {
        mockkObject(DatadogService)
        every { DatadogService.validateApiKey("cache-hit") } returns 77

        val first = DatadogAuthMiddleware.resolveOrgId("cache-hit")
        val second = DatadogAuthMiddleware.resolveOrgId("cache-hit")

        assertEquals(77, first)
        assertEquals(77, second)
        verify(exactly = 1) { DatadogService.validateApiKey("cache-hit") }
    }

    @Test
    fun `resolveOrgId still resolves when cache is full`() {
        mockkObject(DatadogService)
        every { DatadogService.validateApiKey(any()) } returns 7

        repeat(10_001) { index ->
            val resolved = DatadogAuthMiddleware.resolveOrgId("key-$index")
            assertEquals(7, resolved)
        }
        val resolved = DatadogAuthMiddleware.resolveOrgId("key-1")
        assertEquals(7, resolved)
    }

    @Test
    fun `authenticate accepts alternate Datadog API key header names`() = testApplication {
        val created = DatadogService.createApiKey(
            organizationId = 1,
            name = "Trace Writer Key",
            userId = 1
        )
        application {
            routing {
                get("/probe") {
                    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return@get
                    call.respondText(orgId.toString())
                }
            }
        }

        listOf("X-Datadog-API-Key", "Api-Key", "api-key").forEach { headerName ->
            val response = client.get("/probe") {
                header(headerName, created.key)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("1", response.bodyAsText())
        }
    }

    @Test
    fun `extractApiKey accepts shared Datadog key locations`() = testApplication {
        application {
            routing {
                get("/probe") {
                    call.respondText(DatadogAuthMiddleware.extractApiKey(call) ?: "missing")
                }
            }
        }

        val cases = listOf(
            "/probe" to "X-Datadog-API-Key",
            "/probe" to "Api-Key",
            "/probe" to "api-key",
        )
        cases.forEach { (path, headerName) ->
            val response = client.get(path) {
                header(headerName, "header-value")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("header-value", response.bodyAsText())
        }

        assertEquals("query-value", client.get("/probe?api_key=query-value").bodyAsText())
        assertEquals("dash-query-value", client.get("/probe?api-key=dash-query-value").bodyAsText())
        assertEquals("dd-query-value", client.get("/probe?dd-api-key=dd-query-value").bodyAsText())
    }

    @Test
    fun `authenticate rejects missing api key`() = testApplication {
        application {
            install(ContentNegotiation) {
                json()
            }
            routing {
                get("/probe") {
                    val orgId = DatadogAuthMiddleware.authenticate(call)
                    if (orgId != null) {
                        call.respondText(orgId.toString())
                    } else {
                        return@get
                    }
                }
            }
        }

        val response = client.get("/probe")
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("API key is missing or empty"))
    }

    @Test
    fun `authenticate evicts expired cache entries before validation`() = testApplication {
        mockkObject(DatadogService)
        every { DatadogService.validateApiKey("expired-key") } returns 77

        putExpiredCacheEntry("cache", "expired-key")

        application {
            routing {
                get("/probe") {
                    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return@get
                    call.respondText(orgId.toString())
                }
            }
        }

        val response = client.get("/probe") { header("api-key", "expired-key") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("77", response.bodyAsText())
        verify(exactly = 1) { DatadogService.validateApiKey("expired-key") }
    }

    @Test
    fun `authenticate skips cache insert when cache is full`() = testApplication {
        mockkObject(DatadogService)
        every { DatadogService.validateApiKey(any()) } returns 88

        fillCache("cache", 10_000)

        application {
            routing {
                get("/probe") {
                    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return@get
                    call.respondText(orgId.toString())
                }
            }
        }

        val response = client.get("/probe") { header("api-key", "full-cache-key") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("88", response.bodyAsText())
        verify(exactly = 1) { DatadogService.validateApiKey("full-cache-key") }
    }

    @Test
    fun `authenticateContext evicts expired context cache entries`() = testApplication {
        mockkObject(DatadogService)
        every { DatadogService.validateApiKeyContext("expired-context") } returns
            DatadogService.ApiKeyValidation(11, 22)

        putExpiredCacheEntry("contextCache", "expired-context")

        application {
            routing {
                get("/probe") {
                    val context = DatadogAuthMiddleware.authenticateContext(call) ?: return@get
                    call.respondText("${context.organizationId}-${context.projectId}")
                }
            }
        }

        val response = client.get("/probe") { header("api-key", "expired-context") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("11-22", response.bodyAsText())
    }

    @Test
    fun `authenticateContext skips context cache insert when context cache is full`() = testApplication {
        mockkObject(DatadogService)
        every { DatadogService.validateApiKeyContext("full-context-key") } returns
            DatadogService.ApiKeyValidation(33, 44)

        fillCache("contextCache", 10_000)

        application {
            routing {
                get("/probe") {
                    val context = DatadogAuthMiddleware.authenticateContext(call) ?: return@get
                    call.respondText("${context.organizationId}-${context.projectId}")
                }
            }
        }

        val response = client.get("/probe") { header("api-key", "full-context-key") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("33-44", response.bodyAsText())
    }

    @Test
    fun `resolveOrgId evicts expired cached entries before lookup`() {
        mockkObject(DatadogService)
        every { DatadogService.validateApiKey("resolve-expired") } returns 55

        putExpiredCacheEntry("cache", "resolve-expired")

        val resolved = DatadogAuthMiddleware.resolveOrgId("resolve-expired")
        assertEquals(55, resolved)
        verify(exactly = 1) { DatadogService.validateApiKey("resolve-expired") }
        assertNotEquals(0, resolved)
    }
}
