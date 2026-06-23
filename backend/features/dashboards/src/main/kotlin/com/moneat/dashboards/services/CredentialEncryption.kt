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

package com.moneat.dashboards.services

import com.moneat.config.EnvConfig
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for custom data source credentials.
 * Key is derived from DATA_SOURCE_ENCRYPTION_KEY env var.
 * Format: Base64(IV + ciphertext + tag)
 */
object CredentialEncryption {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 128
    private const val KEY_LENGTH = 32

    private val secretKey: SecretKey by lazy {
        val keyStr = EnvConfig.get("DATA_SOURCE_ENCRYPTION_KEY")
            ?: throw IllegalStateException(
                "DATA_SOURCE_ENCRYPTION_KEY is required for custom data source credentials. " +
                    "Set it to a 32+ character secret (e.g. openssl rand -base64 32)."
            )

        // Derive a 256-bit key from the secret using SHA-256
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(keyStr.toByteArray())
        SecretKeySpec(hash.copyOf(KEY_LENGTH), "AES")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ciphertext
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(encrypted: String): String {
        val combined = Base64.getDecoder().decode(encrypted)
        require(combined.size > IV_LENGTH) { "Invalid encrypted data" }

        val iv = combined.copyOfRange(0, IV_LENGTH)
        val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
