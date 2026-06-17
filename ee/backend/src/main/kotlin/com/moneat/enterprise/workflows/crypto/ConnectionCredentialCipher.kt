// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.crypto

import com.moneat.secrets.PurposeScopedSecretCipher
import com.moneat.secrets.SecretVaultPurpose

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
class ConnectionCredentialCipher private constructor(
    private val delegate: PurposeScopedSecretCipher
) {
    constructor(
        activeKeyId: String,
        keksByKeyId: Map<String, ByteArray>
    ) : this(PurposeScopedSecretCipher(SecretVaultPurpose.WORKFLOW_EGRESS, activeKeyId, keksByKeyId))

    val activeKeyId: String
        get() = delegate.activeKeyId

    /** Encrypt [plaintext] for [organizationId], returning a self-describing envelope string. */
    fun encrypt(plaintext: String, organizationId: Int): String = delegate.encrypt(plaintext, organizationId)

    /** Decrypt an [envelope] produced by [encrypt] for [organizationId]. */
    fun decrypt(envelope: String, organizationId: Int): String = delegate.decrypt(envelope, organizationId)

    /** The keyId embedded in a stored ciphertext (for rotation bookkeeping). */
    fun keyIdOf(envelope: String): String = delegate.keyIdOf(envelope)

    /** Re-encrypt an existing envelope under the active keyId (used by KEK rotation). */
    fun rewrapToActive(envelope: String, organizationId: Int): String =
        delegate.rewrapToActive(envelope, organizationId)

    companion object {
        /**
         * Build the cipher from environment configuration:
         *  - `WORKFLOWS_CONNECTION_KEK`          (required) the active key-encryption-key secret
         *  - `WORKFLOWS_CONNECTION_KEK_ID`       (optional) active keyId label (default "v1")
         *  - `WORKFLOWS_CONNECTION_KEK_PREVIOUS` (optional) "id1=secret1,id2=secret2" for rotation overlap
         */
        fun fromEnv(): ConnectionCredentialCipher =
            ConnectionCredentialCipher(PurposeScopedSecretCipher.fromEnv(SecretVaultPurpose.WORKFLOW_EGRESS))

        /** Derive a 256-bit KEK from a secret string (mirrors the datasource credential scheme). */
        fun deriveKek(secret: String): ByteArray =
            PurposeScopedSecretCipher.deriveKek(secret)
    }
}
