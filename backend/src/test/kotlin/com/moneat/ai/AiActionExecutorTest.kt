package com.moneat.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class AiActionExecutorTest {
    @Test
    fun `execute returns success contract for placeholder executor`() = runBlocking {
        val result = AiActionExecutor().execute(
            orgId = 11,
            userId = 22,
            actionId = "open-issue",
            params = mapOf("issueId" to "abc")
        )

        assertTrue(result.success)
        assertTrue(result.message.contains("Action submitted successfully"))
    }
}
