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
