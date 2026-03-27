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

package com.moneat.shared.services

import com.moneat.config.RedisConfig
import io.lettuce.core.SetArgs
import mu.KotlinLogging
import java.security.SecureRandom
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Manages a daily rotating salt for analytics session ID generation.
 *
 * The salt is stored in Redis under `analytics:daily_salt:{date}` with a 48-hour TTL.
 * This means:
 *   - The same salt is used across all instances and restarts on the same day.
 *   - SET NX ensures exactly one salt is created even under concurrent first-request races.
 *   - The 48-hour TTL keeps yesterday's salt alive briefly (useful for late-night sessions),
 *     then expires automatically without manual cleanup.
 *
 * If Redis is unavailable the date string itself is used as a fallback — sessions still
 * rotate daily, but the hash is no longer brute-force resistant.
 */
object AnalyticsSaltService {

    private const val SALT_TTL_SECONDS = 48L * 60 * 60 // 48 hours

    fun getDailySalt(): String {
        val date = LocalDate.now().toString()
        val key = "analytics:daily_salt:$date"

        return try {
            val redis = RedisConfig.sync()

            // Fast path: salt already exists for today.
            redis.get(key) ?: run {
                val candidate = generateSalt()
                // SET NX EX atomically — only writes if the key is absent.
                // If another instance beat us to it, we just discard candidate and
                // read back the authoritative value.
                redis.set(key, candidate, SetArgs.Builder.nx().ex(SALT_TTL_SECONDS))
                redis.get(key) ?: candidate
            }
        } catch (e: Exception) {
            logger.warn { "Redis unavailable for analytics salt, falling back to date: ${e.message}" }
            date
        }
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
