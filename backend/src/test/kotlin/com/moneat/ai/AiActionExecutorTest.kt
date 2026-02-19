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

package com.moneat.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class AiActionExecutorTest {
    @Test
    fun `execute returns success contract for placeholder executor`() =
        runBlocking {
            val result =
                AiActionExecutor().execute(
                    orgId = 11,
                    userId = 22,
                    actionId = "open-issue",
                    params = mapOf("issueId" to "abc")
                )

            assertTrue(result.success)
            assertTrue(result.message.contains("Action submitted successfully"))
        }
}
