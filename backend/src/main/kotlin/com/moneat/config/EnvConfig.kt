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
            get() = get("DEMO_ENABLED", "true").toBoolean()

        /**
         * Demo epoch timestamp in milliseconds.
         * This "freezes" demo data at a specific point in time so events always look recent.
         * Defaults to current time if not set - data will be generated relative to "now".
         */
        val epochMs: Long
            get() = get("DEMO_EPOCH_MS")?.toLongOrNull() ?: System.currentTimeMillis()

        // Demo user/org/project IDs are fixed in migration V50__add_demo_user.sql
        const val ORG_ID = -1L
        const val PROJECT_ID = -1L
        const val USER_ID = -1L
        const val USER_EMAIL = "demo@moneat.dev"
    }
}
