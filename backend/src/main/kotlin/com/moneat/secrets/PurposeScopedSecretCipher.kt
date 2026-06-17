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

package com.moneat.secrets

import com.moneat.config.EnvConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope encryption for organization-scoped connector secrets.
 *
 * The stored envelope intentionally contains only version, key id, IVs, wrapped
 * DEK, and ciphertext. Purpose and organization scope are authenticated through
 * the derived wrapping key, so a secret encrypted for one purpose or organization
 * cannot be decrypted in another.
 */
class PurposeScopedSecretCipher(
    private val purpose: SecretVaultPurpose,
    val activeKeyId: String,
    keksByKeyId: Map<String, ByteArray>
) {
    private val keksByKeyId: Map<String, ByteArray> = keksByKeyId.mapValues { (_, kek) -> kek.copyOf() }

    init {
        require(activeKeyId.isNotBlank()) { "activeKeyId must not be blank" }
        require(!activeKeyId.contains(SEPARATOR)) { "activeKeyId must not contain '$SEPARATOR'" }
        require(this.keksByKeyId.containsKey(activeKeyId)) { "No KEK material registered for the active keyId" }
        this.keksByKeyId.forEach { (id, kek) ->
            require(!id.contains(SEPARATOR)) { "KEK keyId '$id' must not contain '$SEPARATOR'" }
            require(kek.size == KEY_LENGTH) { "KEK '$id' must be $KEY_LENGTH bytes" }
        }
    }

    fun encrypt(plaintext: String, organizationId: Int): String {
        val random = SecureRandom()
        val dek = ByteArray(KEY_LENGTH).also { random.nextBytes(it) }

        try {
            val secretIv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
            val secretCiphertext = cipher(Cipher.ENCRYPT_MODE, dek, secretIv)
                .doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val wrappingKey = deriveWrappingKey(keksByKeyId.getValue(activeKeyId), activeKeyId, organizationId)
            val wrapIv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
            val wrappedDek = cipher(Cipher.ENCRYPT_MODE, wrappingKey, wrapIv).doFinal(dek)

            return listOf(VERSION, activeKeyId, b64(wrapIv), b64(wrappedDek), b64(secretIv), b64(secretCiphertext))
                .joinToString(SEPARATOR)
        } finally {
            Arrays.fill(dek, 0)
        }
    }

    fun decrypt(envelope: String, organizationId: Int): String {
        val parts = splitEnvelope(envelope)
        val keyId = parts[PART_KEY_ID]
        val kek = keksByKeyId[keyId]
            ?: throw IllegalStateException("No KEK material registered for the stored ciphertext keyId")

        val wrappingKey = deriveWrappingKey(kek, keyId, organizationId)
        val dek = cipher(Cipher.DECRYPT_MODE, wrappingKey, unb64(parts[PART_WRAP_IV]))
            .doFinal(unb64(parts[PART_WRAPPED_DEK]))
        try {
            val plaintext = cipher(Cipher.DECRYPT_MODE, dek, unb64(parts[PART_SECRET_IV]))
                .doFinal(unb64(parts[PART_SECRET_CT]))
            return String(plaintext, Charsets.UTF_8)
        } finally {
            Arrays.fill(dek, 0)
        }
    }

    fun keyIdOf(envelope: String): String = splitEnvelope(envelope)[PART_KEY_ID]

    fun rewrapToActive(envelope: String, organizationId: Int): String =
        encrypt(decrypt(envelope, organizationId), organizationId)

    private fun deriveWrappingKey(kek: ByteArray, keyId: String, organizationId: Int): ByteArray =
        hkdfSha256(
            ikm = kek,
            salt = keyId.toByteArray(Charsets.UTF_8),
            info = "${purpose.hkdfInfoPrefix}|org:$organizationId".toByteArray(Charsets.UTF_8),
            length = KEY_LENGTH
        )

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance(ALGORITHM).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH, iv))
        }

    private fun splitEnvelope(envelope: String): List<String> {
        val parts = envelope.split(SEPARATOR)
        require(parts.size == ENVELOPE_PARTS && parts[PART_VERSION] == VERSION) { "Malformed secret ciphertext" }
        return parts
    }

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH = 128
        private const val KEY_LENGTH = 32
        private const val MIN_SECRET_LENGTH = 32
        private const val VERSION = "v1"
        private const val SEPARATOR = ":"
        private const val DEFAULT_KEY_ID = "v1"

        private const val PART_VERSION = 0
        private const val PART_KEY_ID = 1
        private const val PART_WRAP_IV = 2
        private const val PART_WRAPPED_DEK = 3
        private const val PART_SECRET_IV = 4
        private const val PART_SECRET_CT = 5
        private const val ENVELOPE_PARTS = 6

        private val reservedSecretEnvVars: Set<String> =
            linkedSetOf(
                "JWT_SECRET",
                "DATA_SOURCE_ENCRYPTION_KEY",
                "WORKFLOWS_SIGNING_KEY",
                "WORKFLOWS_TEMPORAL_PAYLOAD_KEY"
            ) + SecretVaultPurpose.secretEnvVars
        private val b64Encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val b64Decoder: Base64.Decoder = Base64.getUrlDecoder()

        fun fromEnv(purpose: SecretVaultPurpose): PurposeScopedSecretCipher {
            val activeSecret = EnvConfig.get(purpose.activeSecretEnvVar)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "${purpose.activeSecretEnvVar} is required for the ${purpose.id} secret vault. " +
                        "Set it to a dedicated 32+ character secret; do not reuse another Moneat secret."
                )
            validateSecret(purpose.activeSecretEnvVar, activeSecret)
            val activeKeyId = EnvConfig.get(purpose.activeKeyIdEnvVar)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_KEY_ID

            val keks = linkedMapOf(activeKeyId to deriveKek(activeSecret))
            EnvConfig.get(purpose.previousSecretsEnvVar)
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                ?.forEach { entry ->
                    val pair = entry.split("=", limit = 2)
                    require(pair.size == 2) {
                        "${purpose.previousSecretsEnvVar} entries must be 'keyId=secret'"
                    }
                    val id = pair[0].trim()
                    val secret = pair[1].trim()
                    require(id.isNotBlank()) { "${purpose.previousSecretsEnvVar} keyId must not be blank" }
                    require(secret.isNotBlank()) { "${purpose.previousSecretsEnvVar} secret must not be blank" }
                    require(id != activeKeyId) {
                        "${purpose.previousSecretsEnvVar} must not redefine active keyId '$activeKeyId'"
                    }
                    validateSecret("${purpose.previousSecretsEnvVar}[$id]", secret)
                    keks[id] = deriveKek(secret)
                }
            return PurposeScopedSecretCipher(purpose, activeKeyId, keks)
        }

        fun deriveKek(secret: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)).copyOf(KEY_LENGTH)

        private fun validateSecret(name: String, secret: String) {
            require(secret.length >= MIN_SECRET_LENGTH) { "$name must be at least $MIN_SECRET_LENGTH characters" }
            reservedSecretEnvVars.forEach { reservedEnvVar ->
                val reservedValue = EnvConfig.get(reservedEnvVar)
                require(reservedValue.isNullOrBlank() || reservedValue != secret || reservedEnvVar == name) {
                    "$name must be distinct from $reservedEnvVar"
                }
            }
        }

        private fun b64(bytes: ByteArray): String = b64Encoder.encodeToString(bytes)

        private fun unb64(value: String): ByteArray = b64Decoder.decode(value)

        private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            val effectiveSalt = if (salt.isEmpty()) ByteArray(mac.macLength) else salt
            mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)

            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val output = ByteArray(length)
            var block = ByteArray(0)
            var position = 0
            var counter = 1
            while (position < length) {
                mac.reset()
                mac.update(block)
                mac.update(info)
                mac.update(counter.toByte())
                block = mac.doFinal()
                val toCopy = minOf(block.size, length - position)
                System.arraycopy(block, 0, output, position, toCopy)
                position += toCopy
                counter++
            }
            return output
        }
    }
}
