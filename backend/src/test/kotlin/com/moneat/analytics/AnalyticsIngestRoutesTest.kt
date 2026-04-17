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

package com.moneat.analytics

import com.moneat.analytics.routes.analyticsIngestRoutes
import com.moneat.analytics.routes.extractAnalyticsPublicKey
import com.moneat.analytics.routes.extractPathname
import com.moneat.analytics.routes.extractUtmParams
import com.moneat.analytics.services.GeoIpService
import com.moneat.analytics.services.SessionHashService
import com.moneat.events.services.EventService
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.koin.core.context.GlobalContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsIngestRoutesTest {

    @BeforeTest
    fun setupKoin() {
        startTestKoin()
    }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }

    @Test
    fun `extractAnalyticsPublicKey reads sentry_key from X-Sentry-Auth header`() {
        val header =
            "Sentry sentry_version=7, sentry_key=abc123def, sentry_client=test/1"
        assertEquals("abc123def", extractAnalyticsPublicKey(header, null))
    }

    @Test
    fun `extractAnalyticsPublicKey matches sentry_key prefix case insensitively`() {
        val header = "SENTRY sentry_key=abc123"
        assertEquals("abc123", extractAnalyticsPublicKey(header, null))
    }

    @Test
    fun `extractAnalyticsPublicKey falls back to query param`() {
        assertEquals("pubkey999", extractAnalyticsPublicKey(null, "pubkey999"))
    }

    @Test
    fun `extractAnalyticsPublicKey prefers header over query param`() {
        val key = extractAnalyticsPublicKey("Sentry sentry_key=fromheader", "fromquery")
        assertEquals("fromheader", key)
    }

    @Test
    fun `extractAnalyticsPublicKey returns null when missing or invalid`() {
        assertNull(extractAnalyticsPublicKey(null, null))
        assertNull(extractAnalyticsPublicKey("no key here", null))
        assertNull(extractAnalyticsPublicKey(null, "bad/key"))
    }

    @Test
    fun `extractPathname returns path from absolute URL`() {
        assertEquals("/blog/post", extractPathname("https://example.com/blog/post?x=1#frag"))
    }

    @Test
    fun `extractPathname returns slash on invalid URL`() {
        assertEquals("/", extractPathname("not a valid uri with spaces only"))
    }

    @Test
    fun `extractUtmParams collects utm query pairs`() {
        val url = "https://x.test/page?utm_source=news&utm_medium=email&utm_campaign=spring&other=1"
        val m = extractUtmParams(url)
        assertEquals("news", m["utm_source"])
        assertEquals("email", m["utm_medium"])
        assertEquals("spring", m["utm_campaign"])
        assertEquals(3, m.size)
    }

    @Test
    fun `extractUtmParams decodes percent and plus encoding in values`() {
        val m = extractUtmParams("https://x.test/?utm_term=a+b&utm_content=hello%20world")
        assertEquals("a b", m["utm_term"])
        assertEquals("hello world", m["utm_content"])
    }

    @Test
    fun `extractUtmParams returns empty map for URL without utm`() {
        assertTrue(extractUtmParams("https://example.com/").isEmpty())
        assertTrue(extractUtmParams("https://example.com/page?foo=bar").isEmpty())
    }

    @Test
    fun `extractUtmParams returns empty map for invalid URL`() {
        assertTrue(extractUtmParams("not a valid uri with utm_source=x").isEmpty())
    }

    @Test
    fun `POST domain analytics event without sentry_key returns 401`() = testApplication {
        application {
            routing {
                analyticsIngestRoutes(
                    sessionHashService = SessionHashService(),
                    geoIpService = GeoIpService(),
                    eventService = GlobalContext.get().get<EventService>(),
                    enqueueEvent = { },
                )
            }
        }
        val response = client.post("/api/myapp.example/analytics/event") {
            contentType(ContentType.Application.Json)
            setBody("""{"n":"pageview","u":"https://myapp.example/","d":"myapp.example"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("sentry_key"))
    }

    @Test
    fun `POST project analytics event without auth returns 401`() = testApplication {
        application {
            routing {
                analyticsIngestRoutes(
                    sessionHashService = SessionHashService(),
                    geoIpService = GeoIpService(),
                    eventService = GlobalContext.get().get<EventService>(),
                    enqueueEvent = { },
                )
            }
        }
        val response = client.post("/api/123/analytics/event") {
            contentType(ContentType.Application.Json)
            setBody("""{"n":"pageview","u":"https://x.test/","d":"x.test"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET tracking script returns JavaScript`() = testApplication {
        application {
            routing {
                analyticsIngestRoutes(
                    sessionHashService = SessionHashService(),
                    geoIpService = GeoIpService(),
                    eventService = GlobalContext.get().get<EventService>(),
                    enqueueEvent = { },
                )
            }
        }
        val response = client.get("/js/m.js")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.JavaScript, response.contentType()?.withoutParameters())
        val body = response.bodyAsText()
        assertTrue(body.contains("function"))
        assertTrue(body.contains("moneat"))
    }
}
