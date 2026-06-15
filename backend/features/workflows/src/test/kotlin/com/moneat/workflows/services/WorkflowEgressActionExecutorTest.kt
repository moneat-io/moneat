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

package com.moneat.workflows.services

import com.moneat.workflows.engine.temporal.HTTP_REQUEST_ACTION
import com.moneat.workflows.engine.temporal.TRANSFORM_GRAALJS_ACTION
import com.moneat.workflows.engine.temporal.WORKFLOWS_EGRESS_ENABLED_ENV
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowEgressActionExecutorTest {
    private val executor = WorkflowEgressActionExecutor()
    private var previousEgressValue: String? = null

    @BeforeTest
    fun enableEgress() {
        previousEgressValue = System.getProperty(WORKFLOWS_EGRESS_ENABLED_ENV)
        System.setProperty(WORKFLOWS_EGRESS_ENABLED_ENV, "true")
    }

    @AfterTest
    fun resetEgress() {
        previousEgressValue?.let { value ->
            System.setProperty(WORKFLOWS_EGRESS_ENABLED_ENV, value)
        } ?: System.clearProperty(WORKFLOWS_EGRESS_ENABLED_ENV)
    }

    @Test
    fun `execute refuses egress actions when the feature is disabled`() {
        System.clearProperty(WORKFLOWS_EGRESS_ENABLED_ENV)
        val error =
            assertFailsWith<IllegalArgumentException> {
                executor.execute(
                    stepName = TRANSFORM_GRAALJS_ACTION,
                    params = mapOf("script" to "return 1;"),
                    scope = emptyMap()
                )
            }
        assertTrue(error.message.orEmpty().contains("disabled"))
    }

    @Test
    fun `http request rejects unique-local IPv6 targets`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                executor.execute(
                    stepName = HTTP_REQUEST_ACTION,
                    params = mapOf("url" to "http://[fd00::1]/data"),
                    scope = emptyMap()
                )
            }
        assertTrue(error.message.orEmpty().contains("Private"))
    }

    @Test
    fun `http request requires an egress proxy for public targets`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                executor.execute(
                    stepName = HTTP_REQUEST_ACTION,
                    params = mapOf("url" to "http://93.184.216.34/data"),
                    scope = emptyMap()
                )
            }

        assertTrue(error.message.orEmpty().contains("WORKFLOWS_EGRESS_PROXY_URL"))
    }

    @Test
    fun `transform rejects output larger than the cap`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                executor.execute(
                    stepName = TRANSFORM_GRAALJS_ACTION,
                    params = mapOf("script" to "return 'x'.repeat(70000);"),
                    scope = emptyMap()
                )
            }
        assertTrue(error.message.orEmpty().contains("exceeds"))
    }

    @Test
    fun `transform action runs GraalJS without host access`() {
        val result =
            executor.execute(
                stepName = TRANSFORM_GRAALJS_ACTION,
                params = mapOf("script" to "return Number(scope.count) + 1;"),
                scope = mapOf("count" to "41")
            )

        assertEquals(JsonPrimitive(42), result["result"])
    }

    @Test
    fun `transform action blocks Java host access`() {
        assertFailsWith<RuntimeException> {
            executor.execute(
                stepName = TRANSFORM_GRAALJS_ACTION,
                params = mapOf("script" to "return Java.type('java.lang.System').getenv();"),
                scope = emptyMap()
            )
        }
    }

    @Test
    fun `http request action rejects private targets before sending`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                executor.execute(
                    stepName = HTTP_REQUEST_ACTION,
                    params = mapOf("url" to "http://127.0.0.1:8080/health"),
                    scope = emptyMap()
                )
            }

        assertTrue(error.message.orEmpty().contains("Private"))
    }
}
