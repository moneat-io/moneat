package com.moneat.ai

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

object AiContextResolver {

    private val docCache = ConcurrentHashMap<String, String>()

    private val pageToDocMapping = listOf(
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
                val content = javaClass.classLoader.getResourceAsStream(path)
                    ?.bufferedReader()?.readText()
                if (content != null) {
                    logger.debug { "Loaded AI context doc: $path" }
                    content
                } else {
                    logger.warn { "AI context doc not found: $path" }
                    return null
                }
            } catch (e: Exception) {
                logger.warn { "Failed to load AI context doc $path: ${e.message}" }
                return null
            }
        }
    }

    fun loadDocs(names: List<String>): String {
        return names.mapNotNull { loadDoc(it) }.joinToString("\n\n---\n\n")
    }

    fun loadSystemPrompt(): String {
        return javaClass.classLoader.getResourceAsStream("ai_system_prompt.txt")
            ?.bufferedReader()?.readText()
            ?: throw RuntimeException("ai_system_prompt.txt not found in resources")
    }
}
