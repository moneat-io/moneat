package com.moneat.config

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import java.io.File

object EnvConfig {
    private val dotenv: Dotenv? by lazy {
        val workingDir = File(System.getProperty("user.dir"))
        
        // Try multiple locations: current dir, parent dir, or project root
        val possibleLocations = listOf(
            workingDir,                          // Current directory
            workingDir.parentFile ?: workingDir, // Parent directory (if running from backend/)
            File(workingDir, "../").canonicalFile // Explicit parent
        )
        
        val envFile = possibleLocations
            .map { File(it, ".env") }
            .firstOrNull { it.exists() && it.canRead() }
        
        if (envFile != null) {
            dotenv {
                directory = envFile.parent
                filename = ".env"
                ignoreIfMalformed = true
                ignoreIfMissing = true
            }
        } else {
            null
        }
    }

    /**
     * Initialize by loading .env values into system properties
     * so they're available to application.conf and other config sources
     */
    fun initialize() {
        dotenv?.entries()?.forEach { entry ->
            // Only set if not already present in system environment
            if (System.getenv(entry.key) == null) {
                System.setProperty(entry.key, entry.value)
            }
        }
    }

    fun get(key: String): String? {
        // First try system environment variables, then fall back to .env file
        return System.getenv(key) ?: dotenv?.get(key)
    }

    fun get(key: String, default: String): String {
        return get(key) ?: default
    }

    fun isLoaded(): Boolean = dotenv != null

    // Demo mode configuration
    object Demo {
        val enabled: Boolean
            get() = get("DEMO_ENABLED", "false").toBoolean()

        val epochMs: Long
            get() = get("DEMO_EPOCH_MS")?.toLongOrNull() ?: 0L

        // Demo user/org/project IDs are fixed in migration V50__add_demo_user.sql
        const val ORG_ID = -1L
        const val PROJECT_ID = -1L
        const val USER_ID = -1L
        const val USER_EMAIL = "demo@moneat.dev"
    }
}
