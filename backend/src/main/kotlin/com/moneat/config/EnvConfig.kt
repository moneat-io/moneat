package com.moneat.config

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import java.io.File

object EnvConfig {
    private val dotenv: Dotenv? by lazy {
        val rootDir = File(System.getProperty("user.dir"))
        val envFile = File(rootDir.parentFile ?: rootDir, ".env")
        
        if (envFile.exists()) {
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
}
