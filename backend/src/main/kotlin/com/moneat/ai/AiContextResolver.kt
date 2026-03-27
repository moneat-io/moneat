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

import kotlinx.serialization.SerializationException
import java.io.IOException

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

object AiContextResolver {

    private val docCache = ConcurrentHashMap<String, String>()

    private val pageToDocMapping =
        listOf(
            Regex("^/uptime") to listOf("monitors"),
            Regex("^/status-pages") to listOf("status-pages"),
            Regex("^/projects/[^/]+/logs") to listOf("logs"),
            Regex("^/projects/[^/]+/settings") to listOf("settings"),
            Regex("^/projects") to listOf("projects"),
            Regex("^/issues") to listOf("issues", "performance"),
            Regex("^/performance") to listOf("performance"),
            Regex("^/on-call") to listOf("on-call"),
            Regex("^/monitoring") to listOf("monitoring"),
            Regex("^/settings") to listOf("settings"),
            Regex("^/releases") to listOf("projects"),
            Regex("^/replays") to listOf("projects"),
            Regex("^/$") to listOf("projects", "issues")
        )

    fun resolveDocsForPage(currentPage: String?): List<String> {
        if (currentPage.isNullOrBlank()) return emptyList()
        for ((pattern, docNames) in pageToDocMapping) {
            if (pattern.containsMatchIn(currentPage)) {
                return docNames
            }
        }
        return emptyList()
    }

    fun loadDoc(name: String): String? {
        return docCache.getOrPut(name) {
            val path = "docs/api/$name.md"
            try {
                val content =
                    javaClass.classLoader
                        .getResourceAsStream(path)
                        ?.bufferedReader()
                        ?.readText()
                if (content != null) {
                    logger.debug { "Loaded AI context doc: $path" }
                    content
                } else {
                    logger.warn { "AI context doc not found: $path" }
                    return null
                }
            } catch (e: SerializationException) {
                logger.warn { "Failed to load AI context doc $path: ${e.message}" }
                return null
            } catch (e: IOException) {
                logger.warn { "Failed to load AI context doc $path: ${e.message}" }
                return null
            } catch (e: IllegalStateException) {
                logger.warn { "Failed to load AI context doc $path: ${e.message}" }
                return null
            } catch (e: IllegalArgumentException) {
                logger.warn { "Failed to load AI context doc $path: ${e.message}" }
                return null
            }
        }
    }

    fun loadDocs(names: List<String>): String {
        return names.mapNotNull { loadDoc(it) }.joinToString("\n\n---\n\n")
    }

    fun loadSystemPrompt(): String {
        return javaClass.classLoader
            .getResourceAsStream("ai_system_prompt.txt")
            ?.bufferedReader()
            ?.readText()
            ?: throw RuntimeException("ai_system_prompt.txt not found in resources")
    }
}
