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
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowEgressActionExecutorTest {
    private val executor = WorkflowEgressActionExecutor()

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
