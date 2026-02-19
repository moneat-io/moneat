package com.moneat.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiContextResolverTest {
    @Test
    fun `resolveDocsForPage returns mapped docs`() {
        assertEquals(listOf("logs"), AiContextResolver.resolveDocsForPage("/projects/123/logs"))
        assertEquals(listOf("status-pages"), AiContextResolver.resolveDocsForPage("/status-pages"))
        assertEquals(emptyList(), AiContextResolver.resolveDocsForPage("/unknown/page"))
        assertEquals(emptyList(), AiContextResolver.resolveDocsForPage(null))
    }

    @Test
    fun `loadDoc and loadDocs read bundled markdown resources`() {
        val logsDoc = AiContextResolver.loadDoc("logs")
        assertNotNull(logsDoc)
        assertTrue(logsDoc.isNotBlank())

        val docs = AiContextResolver.loadDocs(listOf("logs", "projects"))
        assertTrue(docs.contains("---"))
        assertTrue(docs.contains("logs", ignoreCase = true))
    }

    @Test
    fun `loadDoc returns null for missing document`() {
        assertNull(AiContextResolver.loadDoc("does-not-exist"))
    }

    @Test
    fun `loadSystemPrompt returns non-empty prompt`() {
        val prompt = AiContextResolver.loadSystemPrompt()
        assertTrue(prompt.isNotBlank())
    }
}
