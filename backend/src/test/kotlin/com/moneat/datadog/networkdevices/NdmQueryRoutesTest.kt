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

package com.moneat.datadog.networkdevices

import com.moneat.testsupport.RouteTestSupport
import com.moneat.testsupport.RouteTestSupport.installJwtAuth
import com.moneat.testsupport.startTestKoin
import com.moneat.testsupport.stopTestKoin
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NdmQueryRoutesTest {

    @BeforeTest
    fun setupKoin() {
        startTestKoin()
    }

    @AfterTest
    fun teardownKoin() {
        stopTestKoin()
    }

    @Test
    fun `network devices list returns 401 without jwt`() = testApplication {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { ndmQueryRoutes() }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/network-devices").status)
    }

    @Test
    fun `network device detail returns 401 without jwt`() = testApplication {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { ndmQueryRoutes() }
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/network-devices/device-1").status
        )
    }

    @Test
    fun `network traps returns 401 without jwt`() = testApplication {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { ndmQueryRoutes() }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/network-devices/traps").status)
    }

    @Test
    fun `network flows returns 401 without jwt`() = testApplication {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { ndmQueryRoutes() }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/network-devices/flows").status)
    }

    @Test
    fun `network paths returns 401 without jwt`() = testApplication {
        application {
            with(RouteTestSupport) { installJwtAuth() }
            routing { ndmQueryRoutes() }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/network-devices/paths").status)
    }
}
