// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.crypto

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
 * Envelope encryption for workflow connection secrets.
 *
 * Reuses the AES-256-GCM scheme of the audited datasource credential encryption,
 * but is keyed INDEPENDENTLY by a dedicated `WORKFLOWS_CONNECTION_KEK` — never
 * `DATA_SOURCE_ENCRYPTION_KEY` or `JWT_SECRET`.
 *
 * Each secret is encrypted with a fresh random data key (DEK); the DEK is wrapped
 * by a per-organization wrapping key derived from the KEK via HKDF-SHA256 with the
 * organization id mixed in, so a single leaked ciphertext has a bounded, per-tenant
 * blast radius. Ciphertext is tagged with a versioned `keyId` so the KEK can be
 * rotated (re-wrap DEKs) without re-encrypting every secret; overlapping keyIds are
 * supported during rotation.
 *
 * Envelope format (binary fields Base64url, no padding):
 * `v1:<keyId>:<wrapIv>:<wrappedDek>:<secretIv>:<secretCiphertext>`
 */
class ConnectionCredentialCipher(
    val activeKeyId: String,
    keksByKeyId: Map<String, ByteArray>
) {
    private val keksByKeyId: Map<String, ByteArray> = keksByKeyId.mapValues { (_, kek) -> kek.copyOf() }

    init {
        require(activeKeyId.isNotBlank()) { "activeKeyId must not be blank" }
        require(this.keksByKeyId.containsKey(activeKeyId)) { "No KEK material registered for the active keyId" }
        this.keksByKeyId.forEach { (id, kek) ->
            require(kek.size == KEY_LENGTH) { "KEK '$id' must be $KEY_LENGTH bytes" }
        }
    }

    /** Encrypt [plaintext] for [organizationId], returning a self-describing envelope string. */
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

    /** Decrypt an [envelope] produced by [encrypt] for [organizationId]. */
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

    /** The keyId embedded in a stored ciphertext (for rotation bookkeeping). */
    fun keyIdOf(envelope: String): String = splitEnvelope(envelope)[PART_KEY_ID]

    /** Re-encrypt an existing envelope under the active keyId (used by KEK rotation). */
    fun rewrapToActive(envelope: String, organizationId: Int): String =
        encrypt(decrypt(envelope, organizationId), organizationId)

    private fun deriveWrappingKey(kek: ByteArray, keyId: String, organizationId: Int): ByteArray =
        hkdfSha256(
            ikm = kek,
            salt = keyId.toByteArray(Charsets.UTF_8),
            info = "$HKDF_INFO_PREFIX|org:$organizationId".toByteArray(Charsets.UTF_8),
            length = KEY_LENGTH
        )

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance(ALGORITHM).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH, iv))
        }

    private fun splitEnvelope(envelope: String): List<String> {
        val parts = envelope.split(SEPARATOR)
        require(parts.size == ENVELOPE_PARTS && parts[PART_VERSION] == VERSION) { "Malformed connection ciphertext" }
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
        private const val HKDF_INFO_PREFIX = "moneat-workflows-connection-kek"
        private const val DEFAULT_KEY_ID = "v1"

        // Positions of the fields within a split envelope string.
        private const val PART_VERSION = 0
        private const val PART_KEY_ID = 1
        private const val PART_WRAP_IV = 2
        private const val PART_WRAPPED_DEK = 3
        private const val PART_SECRET_IV = 4
        private const val PART_SECRET_CT = 5
        private const val ENVELOPE_PARTS = 6
        private val RESERVED_SECRET_ENV_VARS = listOf("DATA_SOURCE_ENCRYPTION_KEY", "JWT_SECRET")

        private val b64Encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val b64Decoder: Base64.Decoder = Base64.getUrlDecoder()

        private fun b64(bytes: ByteArray): String = b64Encoder.encodeToString(bytes)
        private fun unb64(value: String): ByteArray = b64Decoder.decode(value)

        /**
         * Build the cipher from environment configuration:
         *  - `WORKFLOWS_CONNECTION_KEK`          (required) the active key-encryption-key secret
         *  - `WORKFLOWS_CONNECTION_KEK_ID`       (optional) active keyId label (default "v1")
         *  - `WORKFLOWS_CONNECTION_KEK_PREVIOUS` (optional) "id1=secret1,id2=secret2" for rotation overlap
         */
        fun fromEnv(): ConnectionCredentialCipher {
            val activeSecret = EnvConfig.get("WORKFLOWS_CONNECTION_KEK")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "WORKFLOWS_CONNECTION_KEK is required for the workflow connection vault. " +
                        "Set it to a dedicated 32+ character secret (e.g. openssl rand -base64 32); " +
                        "do NOT reuse DATA_SOURCE_ENCRYPTION_KEY or JWT_SECRET."
                )
            validateConnectionKekSecret("WORKFLOWS_CONNECTION_KEK", activeSecret)
            val activeKeyId = EnvConfig.get("WORKFLOWS_CONNECTION_KEK_ID")?.takeIf { it.isNotBlank() }
                ?: DEFAULT_KEY_ID

            val keks = linkedMapOf(activeKeyId to deriveKek(activeSecret))
            EnvConfig.get("WORKFLOWS_CONNECTION_KEK_PREVIOUS")
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                ?.forEach { entry ->
                    val pair = entry.split("=", limit = 2)
                    require(pair.size == 2) { "WORKFLOWS_CONNECTION_KEK_PREVIOUS entries must be 'keyId=secret'" }
                    val id = pair[0].trim()
                    val secret = pair[1].trim()
                    if (id.isNotBlank() && secret.isNotBlank() && id != activeKeyId) {
                        validateConnectionKekSecret("WORKFLOWS_CONNECTION_KEK_PREVIOUS[$id]", secret)
                        keks[id] = deriveKek(secret)
                    }
                }
            return ConnectionCredentialCipher(activeKeyId, keks)
        }

        /** Derive a 256-bit KEK from a secret string (mirrors the datasource credential scheme). */
        fun deriveKek(secret: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)).copyOf(KEY_LENGTH)

        private fun validateConnectionKekSecret(name: String, secret: String) {
            require(secret.length >= MIN_SECRET_LENGTH) { "$name must be at least $MIN_SECRET_LENGTH characters" }
            RESERVED_SECRET_ENV_VARS.forEach { reservedEnvVar ->
                val reservedValue = EnvConfig.get(reservedEnvVar)
                require(reservedValue.isNullOrBlank() || reservedValue != secret) {
                    "$name must be distinct from $reservedEnvVar"
                }
            }
        }

        /** RFC 5869 HKDF-SHA256 (extract + expand). */
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
