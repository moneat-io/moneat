// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.analytics.services

import com.moneat.config.RedisConfig
import mu.KotlinLogging
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

private val logger = KotlinLogging.logger {}

/**
 * Manages daily salt rotation and privacy-preserving session ID generation.
 *
 * Session IDs are SHA-256(dailySalt | domain | IP | userAgent).
 * The salt rotates daily and is stored in Redis with a 48-hour TTL
 * to handle timezone edge cases.
 */
class SessionHashService {

    companion object {
        private const val SALT_KEY_PREFIX = "moneat:analytics:salt:"
        private const val SALT_TTL_SECONDS = 48L * 3600 // 48 hours
        private const val SALT_BYTES = 32
        private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE
    }

    /**
     * Generate a privacy-preserving session ID.
     * IP and UA are used only for hashing — never stored.
     */
    fun generateSessionId(domain: String, ip: String, userAgent: String): String {
        val salt = getDailySalt()
        val input = "$salt|$domain|$ip|$userAgent"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun getDailySalt(): String {
        val today = LocalDate.now(ZoneOffset.UTC).format(dateFormat)
        val key = "$SALT_KEY_PREFIX$today"

        val existing = try {
            RedisConfig.sync().get(key)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to read daily salt from Redis, generating ephemeral salt" }
            null
        }
        if (existing != null) return existing

        // Generate new salt
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        val salt = Base64.getEncoder().encodeToString(bytes)

        try {
            // SET NX to avoid race conditions between workers
            val wasSet = RedisConfig.sync().setnx(key, salt)
            RedisConfig.sync().expire(key, SALT_TTL_SECONDS)
            if (wasSet) {
                logger.info { "Generated new daily analytics salt for $today" }
                return salt
            }
            // Another worker won the race — read their salt
            return RedisConfig.sync().get(key) ?: salt
        } catch (e: Exception) {
            logger.warn(e) { "Failed to store daily salt in Redis, using ephemeral salt" }
            return salt
        }
    }
}
